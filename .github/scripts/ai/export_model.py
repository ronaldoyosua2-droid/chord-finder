"""
Ekspor model Beat This! (small0, MIT) ke ONNX untuk Chord Finder, plus data referensi
untuk unit test Kotlin (mel spektrogram + pasca-proses).
Dijalankan oleh workflow "Model" (butuh internet untuk unduh checkpoint).
"""
import json, os, shutil, time
import numpy as np
import torch, torchaudio
import onnxruntime as ort
from beat_this.inference import load_model
from beat_this.preprocessing import LogMelSpect
from beat_this.model.postprocessor import Postprocessor

SR = 22050
BORDER = 6
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
ASSETS = os.path.join(ROOT, "app/src/main/assets/ai")
TESTRES = os.path.join(ROOT, "app/src/test/resources/ai")
os.makedirs(ASSETS, exist_ok=True)
os.makedirs(TESTRES, exist_ok=True)

model = load_model("small0", "cpu")
print("params:", sum(p.numel() for p in model.parameters()))


class Wrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        o = self.m(x)
        return o["beat"], o["downbeat"]


w = Wrap(model).eval()
onnx_path = os.path.join(ASSETS, "beat_this_small.onnx")
x = torch.randn(1, 751 + 2 * BORDER, 128)
torch.onnx.export(
    w, (x,), onnx_path, input_names=["spect"], output_names=["beat", "downbeat"],
    dynamic_axes={"spect": {1: "time"}, "beat": {1: "time"}, "downbeat": {1: "time"}},
    opset_version=17, do_constant_folding=True,
)
print("onnx size MB:", os.path.getsize(onnx_path) / 1e6)

# Filterbank mel (513 x 128), float32 little-endian
fb = torchaudio.functional.melscale_fbanks(513, 30.0, 11000.0, 128, SR, norm=None, mel_scale="slaney")
fb.numpy().astype("<f4").tofile(os.path.join(ASSETS, "mel_fb.bin"))
shutil.copy(os.path.join(os.path.dirname(__import__("beat_this").__file__), "..", "LICENSE")
            if os.path.exists(os.path.join(os.path.dirname(__import__("beat_this").__file__), "..", "LICENSE"))
            else os.path.join(ROOT, ".github/scripts/ai/LICENSE-beat-this.txt"),
            os.path.join(ASSETS, "LICENSE-beat-this.txt"))

spect_fn = LogMelSpect()
sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
post = Postprocessor("minimal")


def drums(bpm, seconds, seed=0):
    """Pola drum + bass + pad sintetis (kick 1&3, snare 2&4, hi-hat 1/8)."""
    rng = np.random.default_rng(seed)
    n = int(SR * seconds)
    t = np.arange(n) / SR
    y = 0.05 * np.sin(2 * np.pi * 220 * t) + 0.04 * np.sin(2 * np.pi * 277.2 * t) + 0.03 * np.sin(2 * np.pi * 329.6 * t)
    beat = 60.0 / bpm
    k = 0
    while k * beat / 2 < seconds:
        i = int(k * beat / 2 * SR)
        L = min(n - i, int(0.25 * SR))
        tt = np.arange(L) / SR
        if k % 2 == 0:
            b = k // 2
            if b % 2 == 0:  # kick
                y[i:i + L] += 0.9 * np.sin(2 * np.pi * (50 + 80 * np.exp(-tt * 30)) * tt) * np.exp(-tt * 12)
            else:  # snare
                y[i:i + L] += 0.5 * rng.standard_normal(L) * np.exp(-tt * 20)
        Lh = min(n - i, int(0.05 * SR))
        y[i:i + Lh] += 0.15 * rng.standard_normal(Lh) * np.exp(-np.arange(Lh) / SR * 80)
        k += 1
    return (y / np.max(np.abs(y)) * 0.8).astype(np.float32)


def run(audio):
    spect = spect_fn(torch.tensor(audio))
    padded = torch.nn.functional.pad(spect, (0, 0, BORDER, BORDER))
    with torch.inference_mode():
        tb, td = w(padded.unsqueeze(0))
    t0 = time.time()
    ob, od = sess.run(None, {"spect": padded.unsqueeze(0).numpy()})
    dt = time.time() - t0
    diff = float(np.max(np.abs(ob - tb.numpy())))
    beat = torch.tensor(ob[0, BORDER:-BORDER])
    down = torch.tensor(od[0, BORDER:-BORDER])
    bt, dbt = post(beat, down)
    bpm = 60.0 / float(np.median(np.diff(bt))) if len(bt) > 2 else 0.0
    return spect, beat, down, bt, bpm, diff, dt


report = {}
for bpm in (68, 76, 100, 128, 150):
    for secs in (15.0, 12.0):
        _, _, _, bt, est, diff, dt = run(drums(bpm, secs, seed=bpm))
        report[f"{bpm}bpm_{int(secs)}s"] = {"est": round(est, 2), "beats": len(bt), "max_diff_torch_onnx": diff, "ort_sec": round(dt, 3)}
        print(bpm, secs, "->", round(est, 2), "beats", len(bt), "diff", diff, "ort", round(dt, 3))

# Referensi untuk unit test Kotlin
ref_audio = drums(100, 15.0, seed=7)
spect, beat, down, bt, est, diff, _ = run(ref_audio)
ref_audio[: SR * 3].astype("<f4").tofile(os.path.join(TESTRES, "ref_audio_3s.bin"))
spect_fn(torch.tensor(ref_audio[: SR * 3])).numpy().astype("<f4").tofile(os.path.join(TESTRES, "ref_mel_3s.bin"))
beat.numpy().astype("<f4").tofile(os.path.join(TESTRES, "ref_beat_logits.bin"))
down.numpy().astype("<f4").tofile(os.path.join(TESTRES, "ref_down_logits.bin"))
json.dump({"beats": [float(v) for v in bt], "bpm": est, "frames": int(beat.shape[0])},
          open(os.path.join(TESTRES, "ref_post.json"), "w"), indent=1)
json.dump(report, open(os.path.join(ROOT, ".github/scripts/ai/export_report.json"), "w"), indent=1)
print("ref bpm", est, "frames", beat.shape[0])
