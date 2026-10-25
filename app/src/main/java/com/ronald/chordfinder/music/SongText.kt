package com.ronald.chordfinder.music

/** Potongan pada baris chord: chord, atau teks pendamping (label, garis birama, "2x"). */
sealed class Item(val col: Int) {
    class ChordItem(col: Int, val chord: Chord) : Item(col)
    class TextItem(col: Int, val text: String) : Item(col)
}

sealed class Line {
    object Blank : Line()
    class Section(val text: String) : Line()
    class Lyric(val text: String) : Line()
    class Chords(val items: List<Item>) : Line()
}

object SongParser {

    private val SECTION_WORD = Regex(
        "(?i)^(intro|verse|vers|bait|reff?|refrain|chorus|korus|bridge|pre-?chorus|pre-?reff?|" +
            "interlude|int|outro|ending|coda|musik|music|instrumen(tal)?|inst|tag|solo|turnaround|" +
            "overtone|ulang|repeat|fill|break|modulasi|modulation)$"
    )
    private val SECTION_LINE = Regex(
        "(?i)^\\s*[\\[(]?\\s*(intro|verse|vers|bait|reff?|refrain|chorus|korus|bridge|pre-?chorus|pre-?reff?|" +
            "interlude|outro|ending|coda|musik|music|instrumen(tal)?|inst|tag|solo|overtone|" +
            "ulang|repeat|modulasi|modulation)\\b[^\\]):]{0,24}[\\])]?\\s*:?\\s*$"
    )
    private val SYMBOL = Regex("^(?:[|/\\\\\\-.:•%~*_=>()\\[\\]{}]+|(?i:x?\\d{1,2}x?)|(?i:\\(?x?\\d{1,2}x?\\)?))$")
    private val LEAD = "([{|"
    private val TRAIL = ")]}|,.:;"
    private val TOKEN = Regex("\\S+")
    private val CHORDPRO = Regex("\\[([^\\]\\s]{1,14})]")

    fun parse(raw: String): List<Line> {
        val out = ArrayList<Line>()
        for (rawLine in normalize(raw).split('\n')) {
            val expanded = expandChordPro(rawLine)
            if (expanded != null) {
                out.add(expanded.first)
                out.add(Line.Lyric(expanded.second))
                continue
            }
            out.add(classify(rawLine))
        }
        // Rapikan: buang baris kosong berlebihan di awal/akhir dan berurutan
        val cleaned = ArrayList<Line>()
        for (l in out) {
            if (l is Line.Blank && (cleaned.isEmpty() || cleaned.last() is Line.Blank)) continue
            cleaned.add(l)
        }
        while (cleaned.isNotEmpty() && cleaned.last() is Line.Blank) cleaned.removeAt(cleaned.size - 1)
        return cleaned
    }

    fun normalize(raw: String): String = raw
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace('\t', ' ')
        .replace('\u00A0', ' ')
        .replace('♯', '#').replace('♭', 'b')
        .lines().joinToString("\n") { it.trimEnd() }

    fun classify(line: String): Line {
        if (line.isBlank()) return Line.Blank
        chordItems(line)?.let { return Line.Chords(it) }
        if (SECTION_LINE.matches(line)) return Line.Section(line.trim())
        return Line.Lyric(line)
    }

    /** Mengembalikan item jika SEMUA token di baris adalah chord / simbol / label. */
    fun chordItems(line: String): List<Item>? {
        val items = ArrayList<Item>()
        var chords = 0
        for (m in TOKEN.findAll(line)) {
            val tok = m.value
            val col = m.range.first
            val classified = classifyToken(tok, col, labelAllowed = chords == 0) ?: return null
            chords += classified.count { it is Item.ChordItem }
            items.addAll(classified)
        }
        return if (chords > 0) items else null
    }

    private fun classifyToken(tok: String, col: Int, labelAllowed: Boolean): List<Item>? {
        if (SYMBOL.matches(tok)) return listOf(Item.TextItem(col, tok))
        // pisahkan tanda baca di depan/belakang: "(Am)" "|G" "D|" "Em,"
        var s = 0
        var e = tok.length
        while (s < e && tok[s] in LEAD) s++
        while (e > s && tok[e - 1] in TRAIL) e--
        val core = tok.substring(s, e)
        val chord = Chord.parse(core)
        if (chord != null) {
            val list = ArrayList<Item>(3)
            if (s > 0) list.add(Item.TextItem(col, tok.substring(0, s)))
            list.add(Item.ChordItem(col + s, chord))
            if (e < tok.length) list.add(Item.TextItem(col + e, tok.substring(e)))
            return list
        }
        if (labelAllowed) {
            val bare = core.trimEnd(':')
            if (tok.endsWith(":") || SECTION_WORD.matches(bare) ||
                (tok.startsWith("[") && tok.endsWith("]")) || bare.matches(Regex("\\d{1,2}"))
            ) return listOf(Item.TextItem(col, tok))
        }
        return null
    }

    /** "[G]Kasih [D]setia" -> baris chord di atas lirik. */
    private fun expandChordPro(line: String): Pair<Line.Chords, String>? {
        val matches = CHORDPRO.findAll(line).toList()
        if (matches.isEmpty()) return null
        if (matches.any { Chord.parse(it.groupValues[1]) == null }) return null
        val lyric = StringBuilder()
        val items = ArrayList<Item>()
        var last = 0
        for (m in matches) {
            lyric.append(line, last, m.range.first)
            var col = lyric.length
            val prev = items.lastOrNull()
            if (prev is Item.ChordItem) {
                val minCol = prev.col + prev.chord.format(false).length + 1
                if (col < minCol) {
                    while (lyric.length < minCol) lyric.append(' ')
                    col = minCol
                }
            }
            items.add(Item.ChordItem(col, Chord.parse(m.groupValues[1])!!))
            last = m.range.last + 1
        }
        lyric.append(line.substring(last))
        val text = lyric.toString()
        // Hanya chord tanpa lirik? biarkan sebagai baris chord saja
        return Pair(Line.Chords(items), text)
    }

    fun allChords(lines: List<Line>): List<Chord> =
        lines.filterIsInstance<Line.Chords>().flatMap { l ->
            l.items.filterIsInstance<Item.ChordItem>().map { it.chord }
        }

    /** Teks lirik saja (untuk pencarian potongan kalimat). */
    fun lyricText(raw: String): String =
        parse(raw).filterIsInstance<Line.Lyric>().joinToString(" ") { it.text }
}

object KeyDetector {

    private val EXPLICIT = Regex(
        "(?:[Kk]ey(?:\\s+of)?|KEY|[Kk]unci|KUNCI|[Nn]ada\\s*[Dd]asar|NADA\\s*DASAR|[Dd]o|DO|[Bb]ase\\s*[Kk]ey)" +
            "\\s*[:=]?\\s*=?\\s*([A-G])([#b]?)(m(?!aj)|min)?(?![A-Za-z#])"
    )

    data class Result(val keyPc: Int, val explicit: Boolean)

    fun detect(raw: String, lines: List<Line>): Result? {
        explicit(raw)?.let { return Result(it, true) }
        val chords = SongParser.allChords(lines)
        if (chords.isEmpty()) return null
        return Result(infer(chords), false)
    }

    fun explicit(raw: String): Int? {
        val head = raw.lines().take(20)
        for (l in head) {
            val m = EXPLICIT.find(l) ?: continue
            // "Do = G" butuh pemisah agar kata "do" di lirik tidak tertangkap
            val label = m.value.substringBefore(m.groupValues[1]).trim()
            val isDo = label.startsWith("do", true) && label.length <= 4
            if (isDo && !label.contains('=') && !label.contains(':')) continue
            var pc = Notes.pc(m.groupValues[1][0], m.groupValues[2])
            if (m.groupValues[3].isNotEmpty()) pc = Notes.mod12(pc + 3) // minor -> do mayor relatif
            return pc
        }
        return null
    }

    /** Pilih kunci mayor yang paling cocok dengan chord-chord diatonis. */
    fun infer(chords: List<Chord>): Int {
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (k in 0 until 12) {
            var score = 0.0
            for (c in chords) score += fit(c, k)
            if (chords.first().root == k) score += 3.0
            if (chords.last().root == k && chords.last().quality != Quality.MINOR) score += 3.0
            // chord 1 mayor sering muncul
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best
    }

    private fun fit(c: Chord, k: Int): Double {
        val d = Notes.mod12(c.root - k)
        val q = c.quality
        val expected = when (d) {
            0, 5, 7 -> Quality.MAJOR
            2, 4, 9 -> Quality.MINOR
            11 -> Quality.DIM
            else -> null
        }
        return when {
            expected == null -> when (d) { 10 -> 0.2; else -> -1.0 } // b7 cukup umum
            q == Quality.NEUTRAL -> 0.8
            q == expected -> if (d == 0) 1.5 else 1.0
            d == 4 && q == Quality.MAJOR -> 0.5 // III mayor (dominan sekunder)
            d == 2 && q == Quality.MAJOR -> 0.4 // II mayor
            d == 9 && q == Quality.MAJOR -> 0.3
            else -> 0.1
        }
    }
}

enum class NumberMode { INLINE, BELOW, OFF }

data class Span(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind { CHORD, NUMBER, SECTION, LABEL }
}

data class RenderedLine(val text: String, val spans: List<Span>)

object Renderer {

    /**
     * @param keyPc kunci asli lagu (sebelum transpose)
     * @param transpose geser semitone
     */
    fun render(lines: List<Line>, keyPc: Int, transpose: Int, mode: NumberMode): List<RenderedLine> {
        val targetKey = Notes.mod12(keyPc + transpose)
        val flats = Notes.useFlats(targetKey)
        val out = ArrayList<RenderedLine>()
        for (l in lines) when (l) {
            is Line.Blank -> out.add(RenderedLine("", emptyList()))
            is Line.Lyric -> out.add(RenderedLine(l.text, emptyList()))
            is Line.Section -> out.add(RenderedLine(l.text, listOf(Span(0, l.text.length, Span.Kind.SECTION))))
            is Line.Chords -> out.addAll(renderChords(l, keyPc, transpose, flats, mode))
        }
        return out
    }

    private fun renderChords(
        l: Line.Chords, keyPc: Int, transpose: Int, flats: Boolean, mode: NumberMode
    ): List<RenderedLine> {
        val sb = StringBuilder()
        val spans = ArrayList<Span>()
        val numbers = ArrayList<Pair<Int, String>>() // kolom, angka (untuk mode BELOW)
        for (it in l.items) {
            val minCol = if (sb.isEmpty()) 0 else sb.length + if (needsGap(sb)) 1 else 0
            val col = maxOf(it.col, minCol)
            while (sb.length < col) sb.append(' ')
            when (it) {
                is Item.TextItem -> {
                    val s = sb.length
                    sb.append(it.text)
                    spans.add(Span(s, sb.length, Span.Kind.LABEL))
                }
                is Item.ChordItem -> {
                    val s = sb.length
                    sb.append(it.chord.transpose(transpose).format(flats))
                    spans.add(Span(s, sb.length, Span.Kind.CHORD))
                    val num = it.chord.number(keyPc)
                    when (mode) {
                        NumberMode.INLINE -> {
                            val ns = sb.length
                            sb.append('(').append(num).append(')')
                            spans.add(Span(ns, sb.length, Span.Kind.NUMBER))
                        }
                        NumberMode.BELOW -> numbers.add(s to num)
                        NumberMode.OFF -> {}
                    }
                }
            }
        }
        val first = RenderedLine(sb.toString(), spans)
        if (mode != NumberMode.BELOW || numbers.isEmpty()) return listOf(first)
        val nb = StringBuilder()
        val nspans = ArrayList<Span>()
        for ((col, num) in numbers) {
            val c = maxOf(col, if (nb.isEmpty()) 0 else nb.length + 1)
            while (nb.length < c) nb.append(' ')
            val s = nb.length
            nb.append(num)
            nspans.add(Span(s, nb.length, Span.Kind.NUMBER))
        }
        return listOf(first, RenderedLine(nb.toString(), nspans))
    }

    private fun needsGap(sb: StringBuilder) = sb.isNotEmpty() && sb.last() != ' '
}

object TextSearch {
    fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[’'`´‘]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    /** Semua kata dalam query harus ada (urutan bebas), cocok untuk potongan lirik. */
    fun matches(haystackNormalized: String, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        if (haystackNormalized.contains(q)) return true
        return q.split(' ').all { haystackNormalized.contains(it) }
    }
}
