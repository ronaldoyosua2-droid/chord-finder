package com.ronald.chordfinder.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spektrogram log-mel persis seperti Beat This! (torchaudio MelSpectrogram):
 * 22050 Hz, n_fft 1024, hop 441 (50 frame/detik), Hann, center + reflect pad,
 * normalized = "frame_length" (÷√1024), power 1, 128 mel (slaney, 30–11000 Hz), lalu log1p(1000·x).
 * [fb] = filterbank 513 × 128 (baris = bin frekuensi), dari assets/ai/mel_fb.bin.
 */
object LogMel {
    const val SR = 22050
    const val N_FFT = 1024
    const val HOP = 441
    const val BINS = N_FFT / 2 + 1
    const val MELS = 128

    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) }
    // normalized="frame_length" → torch.stft(normalized=True): dibagi √n_fft
    private val norm = sqrt(N_FFT.toDouble())

    fun frames(n: Int) = 1 + n / HOP

    fun compute(audio: FloatArray, fb: FloatArray): Array<FloatArray> {
        require(fb.size == BINS * MELS)
        val n = audio.size
        val pad = N_FFT / 2
        fun sample(i: Int): Double {
            // reflect padding tanpa mengulang titik tepi (seperti torch "reflect")
            var j = i - pad
            if (n == 1) return audio[0].toDouble()
            while (j < 0 || j >= n) {
                if (j < 0) j = -j
                if (j >= n) j = 2 * (n - 1) - j
            }
            return audio[j].toDouble()
        }
        val t = frames(n)
        val out = Array(t) { FloatArray(MELS) }
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val mag = DoubleArray(BINS)
        for (f in 0 until t) {
            val start = f * HOP
            for (k in 0 until N_FFT) { re[k] = sample(start + k) * window[k]; im[k] = 0.0 }
            Fft.transform(re, im)
            for (k in 0 until BINS) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]) / norm
            val row = out[f]
            for (m in 0 until MELS) {
                var s = 0.0
                var idx = m
                for (k in 0 until BINS) { s += mag[k] * fb[idx]; idx += MELS }
                row[m] = ln(1.0 + 1000.0 * s).toFloat()
            }
        }
        return out
    }
}

/** FFT radix-2 di tempat (panjang pangkat 2). */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Pasca-proses "minimal" Beat This!: puncak lokal ±3 frame dengan logit > 0, lalu gabungkan puncak bertetangga. */
object BeatPost {
    const val FPS = 50.0

    fun beatTimes(logits: FloatArray): DoubleArray {
        val n = logits.size
        val peaks = ArrayList<Int>()
        for (i in 0 until n) {
            var mx = Float.NEGATIVE_INFINITY
            for (k in maxOf(0, i - 3)..minOf(n - 1, i + 3)) if (logits[k] > mx) mx = logits[k]
            if (logits[i] == mx && logits[i] > 0f) peaks.add(i)
        }
        // deduplicate_peaks(width = 1): rata-rata kelompok frame yang berjarak ≤ 1
        val res = ArrayList<Double>()
        if (peaks.isNotEmpty()) {
            var p = peaks[0].toDouble()
            var c = 1
            for (q in peaks.drop(1)) {
                if (q - p <= 1) { c++; p += (q - p) / c }
                else { res.add(p); p = q.toDouble(); c = 1 }
            }
            res.add(p)
        }
        return DoubleArray(res.size) { res[it] / FPS }
    }

    /** Garis ketukan rapi: waktu ketukan ke-0 [a] dan jarak antar-ketukan [period] (detik). */
    data class Grid(val a: Double, val period: Double, val index: IntArray)

    /**
     * Cocokkan semua ketukan ke garis lurus t = a + period·k. Nomor ketukan k ditebak dari median jarak,
     * jadi satu-dua ketukan yang terlewat tidak merusak hasil.
     */
    fun grid(times: DoubleArray): Grid? {
        if (times.size < 4) return null
        val d = DoubleArray(times.size - 1) { times[it + 1] - times[it] }.sorted()
        val med = if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
        if (med <= 0) return null
        // nomor ketukan dari jarak ke ketukan sebelumnya (lompat 2 kalau ada yang terlewat)
        val idx = IntArray(times.size)
        for (i in 1 until times.size) idx[i] = idx[i - 1] + Math.round((times[i] - times[i - 1]) / med).toInt().coerceAtLeast(1)
        val n = times.size
        val mx = idx.average()
        val my = times.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (idx[i] - mx) * (times[i] - my); sxx += (idx[i] - mx) * (idx[i] - mx) }
        if (sxx <= 0) return null
        val period = sxy / sxx
        if (period <= 0) return null
        return Grid(my - period * mx, period, idx)
    }

    /** Tempo (BPM) dari garis ketukan. Null kalau ketukannya terlalu sedikit. */
    fun bpm(times: DoubleArray): Double? {
        val g = grid(times) ?: return null
        val b = 60.0 / g.period
        return if (b in 30.0..260.0) b else null
    }

    /**
     * Ketukan 1 (awal birama) pertama: dari [beatsPerBar] kemungkinan posisi pada garis ketukan,
     * pilih yang paling sering bertepatan dengan tebakan ketukan-1 model ([downbeats]).
     * Hasil: waktu (detik) ketukan 1 pertama di dalam rekaman.
     */
    fun firstDownbeat(times: DoubleArray, downbeats: DoubleArray, beatsPerBar: Int): Double? {
        val g = grid(times) ?: return null
        val bpb = beatsPerBar.coerceAtLeast(1)
        val kMin = g.index.first()
        val score = IntArray(bpb)
        for (t in downbeats) {
            val k = Math.round((t - g.a) / g.period).toInt()
            if (Math.abs(g.a + k * g.period - t) < g.period * 0.3) score[Math.floorMod(k, bpb)]++
        }
        val best = score.indices.maxByOrNull { score[it] } ?: 0
        var k = kMin
        while (Math.floorMod(k, bpb) != best) k++
        return g.a + k * g.period
    }
}

/** Ubah laju sampel (interpolasi linear, cukup untuk deteksi ketukan). */
object Resample {
    fun to22k(x: FloatArray, rate: Int): FloatArray {
        if (rate == LogMel.SR) return x
        val ratio = rate.toDouble() / LogMel.SR
        // rata-rata sederhana sebelum diturunkan supaya tidak terlalu banyak aliasing
        val k = maxOf(1, (ratio).toInt())
        val smooth = if (k > 1) FloatArray(x.size) { i ->
            var s = 0f; var c = 0
            for (j in i until minOf(x.size, i + k)) { s += x[j]; c++ }
            s / c
        } else x
        val n = (x.size / ratio).toInt()
        return FloatArray(n) { i ->
            val p = i * ratio
            val a = p.toInt().coerceAtMost(smooth.size - 1)
            val b = (a + 1).coerceAtMost(smooth.size - 1)
            val f = (p - a).toFloat()
            smooth[a] * (1 - f) + smooth[b] * f
        }
    }
}
