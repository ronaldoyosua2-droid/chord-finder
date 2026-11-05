package com.ronald.chordfinder

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.ronald.chordfinder.music.BeatPost
import com.ronald.chordfinder.music.LogMel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deteksi tempo dengan AI "Beat This!" (small0, MIT, © JKU Linz) yang berjalan di HP lewat ONNX Runtime.
 * Masukan: audio mono 22050 Hz.
 */
object TempoAi {
    private const val BORDER = 6
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var fb: FloatArray? = null

    data class Result(val bpm: Int, val exact: Double, val beats: Int, val melMs: Long, val modelMs: Long)

    @Synchronized
    private fun load(ctx: Context) {
        if (session != null) return
        val e = OrtEnvironment.getEnvironment()
        val bytes = ctx.assets.open("ai/beat_this_small.onnx").use { it.readBytes() }
        session = e.createSession(bytes, OrtSession.SessionOptions())
        env = e
        val raw = ctx.assets.open("ai/mel_fb.bin").use { it.readBytes() }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        fb = FloatArray(b.remaining()).also { b.get(it) }
    }

    /** Jalankan di thread latar. Null kalau AI tidak menemukan ketukan yang cukup. */
    fun detect(ctx: Context, audio: FloatArray): Result? {
        load(ctx)
        val t0 = System.currentTimeMillis()
        val mel = LogMel.compute(audio, fb!!)
        val t1 = System.currentTimeMillis()
        val frames = mel.size + 2 * BORDER
        val flat = FloatArray(frames * LogMel.MELS)
        for (t in mel.indices) System.arraycopy(mel[t], 0, flat, (t + BORDER) * LogMel.MELS, LogMel.MELS)
        var logits = FloatArray(0)
        OnnxTensor.createTensor(env!!, FloatBuffer.wrap(flat), longArrayOf(1, frames.toLong(), LogMel.MELS.toLong())).use { input ->
            session!!.run(mapOf("spect" to input)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val beat = (r.get(0).value as Array<FloatArray>)[0]
                logits = beat.copyOfRange(BORDER, beat.size - BORDER)
            }
        }
        val t2 = System.currentTimeMillis()
        val times = BeatPost.beatTimes(logits)
        val bpm = BeatPost.bpm(times) ?: return null.also {
            AppLog.ai("Model: ${t2 - t1} ms · ${times.size} ketukan (terlalu sedikit)")
        }
        AppLog.ai("Mel: ${t1 - t0} ms · Model: ${t2 - t1} ms · ${times.size} ketukan")
        return Result(bpm.roundToInt(), bpm, times.size, t1 - t0, t2 - t1)
    }

    /** Kerasnya suara rata-rata dalam dB (0 dB = maksimum). */
    fun loudnessDb(x: FloatArray): Double {
        if (x.isEmpty()) return -120.0
        var s = 0.0
        for (v in x) s += v * v
        val rms = sqrt(s / x.size)
        return if (rms <= 1e-9) -120.0 else 20 * log10(rms)
    }
}
