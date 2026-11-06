package com.ronald.chordfinder

import com.ronald.chordfinder.music.BeatPost
import com.ronald.chordfinder.music.LogMel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dibandingkan dengan keluaran Python asli Beat This! (dibuat oleh workflow "Model"). */
class BeatAiTest {
    private fun floats(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }
    private fun res(name: String) = javaClass.getResourceAsStream("/ai/$name")!!.readBytes()
    private fun fb() = floats(File("src/main/assets/ai/mel_fb.bin").readBytes())

    @Test fun melSamaDenganTorch() {
        val audio = floats(res("ref_audio_3s.bin"))
        val ref = floats(res("ref_mel_3s.bin"))
        val mel = LogMel.compute(audio, fb())
        assertEquals(ref.size / 128, mel.size)
        var maxDiff = 0f
        for (t in mel.indices) for (m in 0 until 128) maxDiff = maxOf(maxDiff, kotlin.math.abs(mel[t][m] - ref[t * 128 + m]))
        assertTrue("selisih mel $maxDiff", maxDiff < 2e-3f)
    }

    @Test fun puncakKetukanSamaDenganPython() {
        val logits = floats(res("ref_beat_logits.bin"))
        val ref = org.json.JSONObject(String(res("ref_post.json")))
        val beats = ref.getJSONArray("beats")
        val got = BeatPost.beatTimes(logits)
        assertEquals(beats.length(), got.size)
        for (i in got.indices) assertEquals(beats.getDouble(i), got[i], 1e-6)
        assertEquals(100.0, BeatPost.bpm(got)!!, 0.6)
    }

    @Test fun bpmDariKetukanRapi() {
        // 128 BPM, waktu dibulatkan ke frame 20 ms seperti keluaran model
        val t = DoubleArray(30) { Math.round(it * 60.0 / 128 * 50) / 50.0 }
        assertEquals(128.0, BeatPost.bpm(t)!!, 0.5)
        assertEquals(null, BeatPost.bpm(doubleArrayOf(0.0, 0.5, 1.0)))
    }

    @Test fun bpmTetapPasKalauAdaKetukanTerlewat() {
        val t = DoubleArray(30) { Math.round(it * 60.0 / 128 * 50) / 50.0 }.filterIndexed { i, _ -> i != 7 && i != 15 }.toDoubleArray()
        assertEquals(128.0, BeatPost.bpm(t)!!, 0.5)
    }

    @Test fun ketukanSatuDariTebakanModel() {
        // 120 BPM mulai 0,3 dtk; ketukan 1 (birama 4/4) jatuh di ketukan ke-2, 6, 10, ...
        val beats = DoubleArray(24) { 0.3 + it * 0.5 }
        val downs = doubleArrayOf(1.31, 3.29, 5.3, 7.3)
        assertEquals(1.3, BeatPost.firstDownbeat(beats, downs, 4)!!, 0.02)
    }
}
