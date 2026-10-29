package com.ronald.chordfinder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ronald.chordfinder.music.BeatDetector
import com.ronald.chordfinder.music.Ev
import com.ronald.chordfinder.music.MetroConfig
import com.ronald.chordfinder.music.Timeline
import java.io.DataInputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Metronom berbasis AudioTrack: semua bunyi (klik, hitungan, aba-aba) dicampur langsung
 * di buffer audio, jadi waktunya tepat sampai hitungan sampel.
 */
class MetronomeEngine(private val ctx: Context) {

    interface Listener {
        fun onBeat(beat: Int, bar: Int, counting: Boolean) {}
        fun onSection(index: Int) {}
        fun onEnd() {}
    }

    companion object {
        const val SR = 44100
        val SOUNDS = listOf("kayu" to "Kayu", "beep" to "Beep", "hihat" to "Hi-hat")
        val VOICES = listOf("A" to "Suara A", "B" to "Suara B")
        private val voiceCache = HashMap<String, Map<String, FloatArray>>()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    // ----------------------------------------------------------- bunyi

    private fun env(n: Int, decay: Double) = DoubleArray(n) { exp(-it / (SR * decay)) }

    private fun click(kind: String, level: Int): FloatArray {
        // level: 2 = aksen, 1 = biasa, 0 = pecahan ketukan
        val n = (SR * 0.09).toInt()
        val out = FloatArray(n)
        when (kind) {
            "beep" -> {
                val f = when (level) { 2 -> 1760.0; 1 -> 880.0; else -> 1320.0 }
                val e = env(n, 0.03)
                for (i in 0 until n) {
                    val t = i.toDouble() / SR
                    out[i] = if (t < 0.05) (sin(2 * PI * f * t) * e[i]).toFloat() else 0f
                }
            }
            "hihat" -> {
                val e = env(n, if (level == 2) 0.02 else 0.012)
                val rnd = java.util.Random(7)
                var prev = 0.0
                for (i in 0 until n) {
                    val w = rnd.nextDouble() * 2 - 1
                    out[i] = ((w - prev) * e[i]).toFloat()  // high-pass sederhana
                    prev = w
                }
            }
            else -> { // kayu
                val f = when (level) { 2 -> 1700.0; 1 -> 1150.0; else -> 1400.0 }
                val e1 = env(n, 0.012)
                val e2 = env(n, 0.006)
                for (i in 0 until n) {
                    val t = i.toDouble() / SR
                    out[i] = (sin(2 * PI * f * t) * e1[i] + 0.4 * sin(2 * PI * f * 2.7 * t) * e2[i]).toFloat()
                }
            }
        }
        val gain = when (level) { 2 -> 0.85f; 1 -> 0.55f; else -> 0.3f }
        var peak = 1e-6f
        for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
        for (i in out.indices) out[i] = out[i] / peak * gain
        return out
    }

    /** Baca semua kata untuk suara A/B dari assets/voice (WAV 16-bit mono 22.05 kHz). */
    fun voice(label: String): Map<String, FloatArray> = voiceCache.getOrPut(label) {
        val map = HashMap<String, FloatArray>()
        val files = ctx.assets.list("voice/$label") ?: emptyArray()
        for (f in files) {
            if (!f.endsWith(".wav")) continue
            try {
                ctx.assets.open("voice/$label/$f").use { map[f.removeSuffix(".wav")] = readWav(DataInputStream(it)) }
            } catch (_: Exception) { }
        }
        map
    }

    private fun readWav(input: DataInputStream): FloatArray {
        val bytes = input.readBytes()
        // cari chunk "data"
        var p = 12
        var rate = 22050
        var dataStart = 44
        var dataLen = bytes.size - 44
        fun le32(o: Int) = (bytes[o].toInt() and 255) or ((bytes[o + 1].toInt() and 255) shl 8) or
            ((bytes[o + 2].toInt() and 255) shl 16) or ((bytes[o + 3].toInt() and 255) shl 24)
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val len = le32(p + 4)
            if (id == "fmt ") rate = le32(p + 12)
            if (id == "data") { dataStart = p + 8; dataLen = minOf(len, bytes.size - dataStart); break }
            p += 8 + len
        }
        val n = dataLen / 2
        val src = FloatArray(n) {
            val lo = bytes[dataStart + 2 * it].toInt() and 255
            val hi = bytes[dataStart + 2 * it + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
        if (rate == SR) return src
        // ubah ke 44.1 kHz (interpolasi linear)
        val outN = (n.toLong() * SR / rate).toInt()
        return FloatArray(outN) {
            val x = it.toDouble() * rate / SR
            val i = x.toInt()
            val fr = (x - i).toFloat()
            val a = src[minOf(i, n - 1)]
            val b = src[minOf(i + 1, n - 1)]
            a + (b - a) * fr
        }
    }

    // ------------------------------------------------------------ main

    fun start(config: MetroConfig, sound: String, voiceLabel: String, listener: Listener) {
        stop()
        running = true
        val clicks = arrayOf(click(sound, 0), click(sound, 1), click(sound, 2))
        val words = voice(voiceLabel)
        thread = Thread({ loop(config, clicks, words, listener) }, "metronom").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private class Playing(val data: FloatArray, var pos: Int, val gain: Float)

    private fun loop(c: MetroConfig, clicks: Array<FloatArray>, words: Map<String, FloatArray>, l: Listener) {
        val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = try { builder.build() } catch (e: Exception) { running = false; return }

        val tl = Timeline(c)
        val chunk = 512
        val mix = FloatArray(chunk)
        val pcm = ShortArray(chunk)
        val active = ArrayList<Playing>()
        val pending = ArrayList<Ev>()
        var nextBar = if (c.countIn) -1 else 0
        var frame = 0L
        var endFrame = Long.MAX_VALUE
        var timelineDone = false
        val latencyMs = (track.bufferSizeInFrames.toLong() * 1000 / SR)
        track.play()
        try {
            while (running) {
                // isi kejadian untuk 2 bar ke depan
                while (!timelineDone && (pending.isEmpty() || (pending.last().t * SR).toLong() < frame + chunk + SR)) {
                    val evs = tl.eventsForBar(nextBar)
                    pending.addAll(evs)
                    nextBar++
                    if (evs.any { it is Ev.End }) timelineDone = true
                }
                val chunkEnd = frame + chunk
                val it = pending.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val ef = (e.t * SR).toLong()
                    if (ef >= chunkEnd) break
                    it.remove()
                    val offset = (ef - frame).toInt().coerceAtLeast(0)
                    val delay = (ef - frame) * 1000 / SR + latencyMs
                    when (e) {
                        is Ev.Click -> active.add(Playing(clicks[if (e.sub) 0 else if (e.accent) 2 else 1], -offset, 1f))
                        is Ev.Count -> words[e.n.toString()]?.let { w -> active.add(Playing(w, -offset, 0.95f)) }
                        is Ev.Cue -> words[e.word]?.let { w -> active.add(Playing(w, -offset, 0.95f)) }
                        is Ev.Beat -> main.postDelayed({ if (running) l.onBeat(e.beat, e.bar, e.counting) }, delay)
                        is Ev.Section -> main.postDelayed({ if (running) l.onSection(e.index) }, delay)
                        is Ev.End -> endFrame = ef + SR / 2
                    }
                }
                java.util.Arrays.fill(mix, 0f)
                val ai = active.iterator()
                while (ai.hasNext()) {
                    val p = ai.next()
                    for (i in 0 until chunk) {
                        val idx = p.pos + i
                        if (idx >= 0 && idx < p.data.size) mix[i] += p.data[idx] * p.gain
                    }
                    p.pos += chunk
                    if (p.pos >= p.data.size) ai.remove()
                }
                for (i in 0 until chunk) {
                    val v = mix[i].coerceIn(-1f, 1f)
                    pcm[i] = (v * 32767).toInt().toShort()
                }
                track.write(pcm, 0, chunk)
                frame = chunkEnd
                if (frame >= endFrame) {
                    running = false
                    main.postDelayed({ l.onEnd() }, latencyMs)
                }
            }
        } finally {
            try { track.stop() } catch (_: Exception) { }
            track.release()
        }
    }

    // ------------------------------------------------ deteksi tempo

    /** Rekam dari mikrofon selama [seconds] detik lalu tebak BPM. Pastikan izin RECORD_AUDIO sudah ada. */
    @SuppressLint("MissingPermission")
    fun detectTempo(seconds: Int, progress: (Float) -> Unit, done: (Int?) -> Unit) {
        Thread {
            val rate = 22050
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096) * 2)
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                main.post { done(null) }; return@Thread
            }
            val total = rate * seconds
            val all = FloatArray(total)
            val buf = ShortArray(2048)
            var got = 0
            rec.startRecording()
            try {
                while (got < total) {
                    val n = rec.read(buf, 0, minOf(buf.size, total - got))
                    if (n <= 0) break
                    for (i in 0 until n) all[got + i] = buf[i] / 32768f
                    got += n
                    val p = got.toFloat() / total
                    main.post { progress(p) }
                }
            } finally {
                rec.stop(); rec.release()
            }
            val bpm = BeatDetector.estimate(all.copyOf(got), rate)
            main.post { done(bpm) }
        }.start()
    }
}
