package com.ronald.chordfinder.music

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Jenis ketukan pada pola (sequencer ketukan). */
enum class Step { ACCENT, NORMAL, MUTE }

/** Satu bagian di urutan lagu. [cue] = kata aba-aba (kunci file suara), "" = tanpa aba-aba. */
data class SeqItem(val name: String, val bars: Int, val cue: String, val line: Int = -1)

data class MetroConfig(
    val bpm: Int = 80,
    val beats: Int = 4,          // ketukan per bar (pembilang birama)
    val unit: Int = 4,           // 4 = seperempat, 8 = seperdelapan
    val pattern: List<Step> = listOf(Step.ACCENT, Step.NORMAL, Step.NORMAL, Step.NORMAL),
    val subdivision: Int = 1,    // 1, 2 (♪♪), 3 (triplet), 4 (♬♬)
    val countIn: Boolean = true,
    val cues: Boolean = true,
    val sequence: List<SeqItem> = emptyList(),
    val useSequence: Boolean = false,
) {
    val label get() = "$beats/$unit"
    fun patternFor(n: Int): List<Step> =
        List(n) { i -> pattern.getOrNull(i) ?: if (i == 0) Step.ACCENT else Step.NORMAL }

    companion object {
        val SIGNATURES = listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8, 12 to 8)
        fun defaultPattern(beats: Int, unit: Int): List<Step> = List(beats) { i ->
            when {
                i == 0 -> Step.ACCENT
                // 6/8 dan 12/8: aksen kecil tiap 3 ketukan
                unit == 8 && i % 3 == 0 -> Step.ACCENT
                else -> Step.NORMAL
            }
        }
    }
}

/** Kejadian di garis waktu metronom, dalam detik sejak tombol Mulai. */
sealed class Ev(val t: Double) {
    class Click(t: Double, val accent: Boolean, val sub: Boolean) : Ev(t)
    class Count(t: Double, val n: Int) : Ev(t)            // hitungan masuk "one".."six"
    class Cue(t: Double, val word: String) : Ev(t)        // aba-aba bagian berikutnya
    class Beat(t: Double, val beat: Int, val bar: Int, val counting: Boolean) : Ev(t)  // untuk tampilan
    class Section(t: Double, val index: Int) : Ev(t)
    class End(t: Double) : Ev(t)
}

/**
 * Menghasilkan kejadian metronom secara berurutan (tanpa batas kalau tidak pakai urutan lagu).
 * Bar 0 = bar pertama lagu; hitungan masuk berada di bar -1.
 */
class Timeline(private val c: MetroConfig) {
    val beatSec = 60.0 / c.bpm
    private val pattern = c.patternFor(c.beats)
    private val countBars = if (c.countIn) 1 else 0
    private val seq = if (c.useSequence) c.sequence.filter { it.bars > 0 } else emptyList()
    private val seqStarts: IntArray = IntArray(seq.size).also { a ->
        var b = 0
        for (i in seq.indices) { a[i] = b; b += seq[i].bars }
    }
    val totalBars: Int? = if (seq.isEmpty()) null else seq.sumOf { it.bars }

    /** Semua kejadian untuk bar ke-[bar] (bar bisa -1 untuk hitungan masuk). */
    fun eventsForBar(bar: Int): List<Ev> {
        val out = ArrayList<Ev>()
        val barStart = (bar + countBars) * c.beats * beatSec
        if (totalBars != null && bar >= totalBars) {
            out.add(Ev.End(barStart))
            return out
        }
        if (bar >= 0) {
            val si = seqStarts.indexOfFirst { it == bar }
            if (si >= 0) out.add(Ev.Section(barStart, si))
            // Aba-aba di ketukan 1 bar terakhir sebelum bagian berikutnya
            if (c.cues) {
                val next = seqStarts.indexOfFirst { it == bar + 1 }
                if (next >= 0 && seq[next].cue.isNotEmpty()) out.add(Ev.Cue(barStart, seq[next].cue))
            }
        }
        for (b in 0 until c.beats) {
            val t = barStart + b * beatSec
            val counting = bar < 0
            out.add(Ev.Beat(t, b, bar, counting))
            if (counting) {
                // 6/8 & 12/8: hitung setiap ketukan; lainnya juga
                out.add(Ev.Count(t, b + 1))
                out.add(Ev.Click(t, b == 0, false))
            } else {
                when (pattern[b]) {
                    Step.MUTE -> {}
                    Step.ACCENT -> out.add(Ev.Click(t, true, false))
                    Step.NORMAL -> out.add(Ev.Click(t, false, false))
                }
            }
            if (!counting && c.subdivision > 1 && pattern[b] != Step.MUTE) {
                for (s in 1 until c.subdivision) {
                    out.add(Ev.Click(t + s * beatSec / c.subdivision, false, true))
                }
            }
        }
        return out.sortedBy { it.t }
    }

    /** Bar dan bagian yang sedang berjalan pada waktu [t] (detik). */
    fun barAt(t: Double): Int = ((t / (c.beats * beatSec)).toInt()) - countBars

    fun sectionAt(bar: Int): Int {
        if (seq.isEmpty() || bar < 0) return -1
        var r = -1
        for (i in seqStarts.indices) if (seqStarts[i] <= bar) r = i
        return r
    }
}

object SequenceBuilder {

    /** Kata aba-aba dari nama bagian (Reff -> Chorus, Bait -> Verse, ...). */
    fun cueFor(name: String): String {
        val n = name.lowercase()
        return when {
            Regex("pre-?\\s*(chorus|reff?|korus)").containsMatchIn(n) -> "prechorus"
            Regex("last\\s*(chorus|reff)").containsMatchIn(n) -> "lastchorus"
            Regex("(chorus|reff?|refrain|korus)").containsMatchIn(n) -> "chorus"
            Regex("(verse|vers|bait)").containsMatchIn(n) -> "verse"
            n.contains("bridge") -> "bridge"
            Regex("(interlude|musik|music|\\bint\\b)").containsMatchIn(n) -> "interlude"
            Regex("(instrumen|\\binst\\b|solo)").containsMatchIn(n) -> "instrumental"
            n.contains("intro") -> "intro"
            n.contains("tag") -> "tag"
            Regex("(outro|coda)").containsMatchIn(n) -> "outro"
            n.contains("ending") -> "ending"
            Regex("(ulang|repeat)").containsMatchIn(n) -> "repeat"
            else -> ""
        }
    }

    private val REPEAT = Regex("(?i)(?:x\\s*(\\d)|(\\d)\\s*x)")

    /**
     * Urutan lagu otomatis dari bagian-bagian yang tertulis.
     * Jumlah bar ditebak dari banyaknya chord (± 1 chord per bar), bisa dikoreksi.
     */
    fun auto(lines: List<Line>, manual: Map<Int, Int> = emptyMap()): List<SeqItem> {
        val out = ArrayList<SeqItem>()
        val starts = lines.indices.filter { lines[it] is Line.Section }
        if (starts.isEmpty()) {
            val n = SongParser.allChords(lines).size
            return if (n == 0) emptyList() else listOf(SeqItem("Lagu", n.coerceIn(1, 64), ""))
        }
        // Chord sebelum bagian pertama dianggap intro
        val pre = SongParser.allChords(lines.subList(0, starts[0])).size
        if (pre > 0) out.add(SeqItem("Intro", pre.coerceIn(1, 16), "intro", 0))
        for ((k, s) in starts.withIndex()) {
            val end = if (k + 1 < starts.size) starts[k + 1] else lines.size
            val body = lines.subList(s, end)
            val chords = SongParser.allChords(body).size
            val name = (lines[s] as Line.Section).text.trim().trim('[', ']', '(', ')').trimEnd(':', ' ')
            if (chords == 0) continue
            val rep = REPEAT.find(name)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() } ?: 1
            // Modulasi tepat sebelum/di awal bagian ini -> aba-aba "Up!"
            val modHere = (maxOf(0, s - 1)..minOf(s + 1, lines.size - 1)).any { Modulation.isChangeAt(lines, manual, it) }
            val cue = if (modHere) "up" else cueFor(name)
            out.add(SeqItem(name, (chords * rep).coerceIn(1, 64), cue, s))
        }
        return out
    }
}

object TempoText {
    private val BPM = Regex("(?i)(?:tempo|bpm)\\s*[:=]?\\s*(\\d{2,3})|(\\d{2,3})\\s*bpm")
    private val SIG = Regex("(?<![\\d/])(2|3|4|6|12)\\s*/\\s*(4|8)(?![\\d/])")

    fun bpm(text: String): Int? {
        val m = BPM.find(text) ?: return null
        val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
        return if (v in 30..260) v else null
    }

    /** Birama hanya dari baris pendek (supaya "C/4" atau tanggal tidak tertangkap). */
    fun signature(text: String): Pair<Int, Int>? {
        for (l in text.lines().take(60)) {
            val t = l.trim()
            if (t.length > 30) continue
            if (SongParser.chordItems(t) != null) continue
            val m = SIG.find(t) ?: continue
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        return null
    }
}

/**
 * Tebak BPM dari rekaman (mono, -1..1). Cara: amplop onset (kenaikan energi),
 * lalu autokorelasi pada rentang 60–180 BPM, dengan sedikit preferensi di sekitar 100 BPM.
 */
object BeatDetector {
    fun estimate(samples: FloatArray, sampleRate: Int): Int? {
        val hop = 256
        val frames = samples.size / hop
        if (frames < 200) return null
        val env = DoubleArray(frames)
        var prev = 0.0
        for (f in 0 until frames) {
            var e = 0.0
            val o = f * hop
            for (i in 0 until hop) { val v = samples[o + i]; e += v * v }
            e = sqrt(e / hop)
            env[f] = max(0.0, e - prev)
            prev = e
        }
        // normalisasi & buang rata-rata
        val mean = env.average()
        for (i in env.indices) env[i] -= mean
        val fps = sampleRate.toDouble() / hop
        var bestLag = -1
        var best = Double.NEGATIVE_INFINITY
        val minLag = (fps * 60 / 180).toInt()
        val maxLag = (fps * 60 / 60).toInt()
        val ac = DoubleArray(maxLag * 2 + 2)
        for (lag in 1 until ac.size) {
            if (lag >= frames) break
            var v = 0.0
            for (i in 0 until frames - lag) v += env[i] * env[i + lag]
            ac[lag] = v / (frames - lag)
        }
        for (lag in minLag..maxLag) {
            // Puncak di lag ini harus didukung juga di kelipatannya (2x, 3x):
            // tempo asli punya pola berulang, setengah-tempo hanya cocok dengan sebagian ketukan.
            var s = ac[lag]
            val l2 = lag * 2
            if (l2 < ac.size) s += 0.5 * ac[l2]
            val half = lag / 2
            // kalau lag/2 juga kuat, mungkin tempo sebenarnya lebih cepat -> kurangi skor lag ini
            if (half >= minLag) s -= 0.6 * max(0.0, ac[half])
            val bpm = 60 * fps / lag
            val w = 1.0 - 0.2 * min(1.0, abs(bpm - 110) / 90)
            val score = s * w
            if (score > best) { best = score; bestLag = lag }
        }
        if (bestLag <= 0 || best <= 0) return null
        return (60 * fps / bestLag).roundToInt()
    }
}
