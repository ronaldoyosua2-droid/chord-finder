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
    /**
     * Penanda modulasi. [target] = Do baru (0-11) jika tertulis, [delta] = naik/turun semitone jika tertulis.
     * Keduanya null -> Do baru ditebak dari chord sesudahnya.
     */
    class KeyChange(val text: String, val target: Int?, val delta: Int?) : Line()
}

object SongParser {

    private val SECTION_WORD = Regex(
        "(?i)^(intro|verse|vers|bait|reff?|refrain|chorus|korus|bridge|pre-?chorus|pre-?reff?|" +
            "interlude|int|outro|ending|coda|musik|music|instrumen(tal)?|inst|tag|solo|turnaround|" +
            "overtone|ulang|repeat|fill|break)$"
    )
    private val SECTION_LINE = Regex(
        "(?i)^\\s*[\\[(]?\\s*(intro|verse|vers|bait|reff?|refrain|chorus|korus|bridge|pre-?chorus|pre-?reff?|" +
            "interlude|outro|ending|coda|musik|music|instrumen(tal)?|inst|tag|solo|overtone|" +
            "ulang|repeat)\\b[^\\]):]{0,24}[\\])]?\\s*:?\\s*$"
    )
    private val SYMBOL = Regex("^(?:[|/\\\\\\-.:•%~*_=>()\\[\\]{}]+|(?i:x?\\d{1,2}x?)|(?i:\\(?x?\\d{1,2}x?\\)?))$")
    private val LEAD = "([{|"
    private val TRAIL = ")]}|,.:;"
    private val TOKEN = Regex("\\S+")
    private val CHORDPRO = Regex("\\[([^\\]\\s]{1,14})]")
    private val MOD_WORD = Regex("(?i)^[\\[(]?(modulasi|modulation|modulate|mod|key-?change)[\\])]?:?$")
    private val MOD_LINE = Regex(
        "(?i)^\\s*[\\[(]?\\s*(modulasi|modulation|modulate|key\\s*change|pindah\\s+nada|naik\\s+nada|turun\\s+nada|" +
            "naik\\s+(?:\\d|½|setengah|1/2)|turun\\s+(?:\\d|½|setengah|1/2))\\b.{0,30}$"
    )

    fun parse(raw: String): List<Line> {
        val out = ArrayList<Line>()
        for (rawLine in normalize(raw).split('\n')) {
            val expanded = expandChordPro(rawLine)
            if (expanded != null) {
                out.add(expanded.first)
                out.add(Line.Lyric(expanded.second))
                continue
            }
            val c = classify(rawLine)
            if (c is Line.Chords) {
                val first = c.items.firstOrNull()
                if (first is Item.TextItem && MOD_WORD.matches(first.text)) {
                    out.add(Line.KeyChange(first.text.trim(), null, null))
                    out.add(Line.Chords(c.items.drop(1)))
                    continue
                }
            }
            out.add(c)
        }
        dropChordPickers(out)
        // Rapikan: buang baris kosong berlebihan di awal/akhir dan berurutan
        val cleaned = ArrayList<Line>()
        for (l in out) {
            // "Do = A" / "Key: A" di awal lagu sudah ditampilkan di kepala lagu
            if (l is Line.KeyChange && l.target != null && cleaned.none { it is Line.Lyric || it is Line.Chords }) continue
            if (l is Line.Blank && (cleaned.isEmpty() || cleaned.last() is Line.Blank)) continue
            cleaned.add(l)
        }
        while (cleaned.isNotEmpty() && cleaned.last() is Line.Blank) cleaned.removeAt(cleaned.size - 1)
        return cleaned
    }

    /**
     * Buang daftar tombol kunci dari situs (A, A#, B, C … satu chord per baris),
     * yaitu 5+ baris berturut-turut yang masing-masing hanya berisi satu chord mayor polos.
     */
    private fun dropChordPickers(lines: MutableList<Line>) {
        var i = 0
        while (i < lines.size) {
            var j = i
            val roots = HashSet<Int>()
            while (j < lines.size) {
                val l = lines[j] as? Line.Chords ?: break
                if (l.items.size != 1) break
                val c = (l.items[0] as? Item.ChordItem)?.chord ?: break
                if (c.suffix.isNotEmpty() || c.bass != null || !roots.add(c.root)) break
                j++
            }
            if (j - i >= 5) {
                repeat(j - i) { lines.removeAt(i) }
            } else i = maxOf(j, i + 1)
        }
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
        modulation(line)?.let { return it }
        if (SECTION_LINE.matches(line)) return Line.Section(line.trim())
        return Line.Lyric(line)
    }

    /** "Modulasi ke D", "Modulasi +1", "naik ½", "Do = D", "Key: D" -> KeyChange. */
    fun modulation(line: String): Line.KeyChange? {
        val t = line.trim()
        if (t.length > 40) return null
        val explicitKey = KeyDetector.explicit(t)
        if (explicitKey != null && !MOD_LINE.containsMatchIn(t)) {
            return Line.KeyChange(t, explicitKey, null)
        }
        if (!MOD_LINE.containsMatchIn(t)) return null
        val target = explicitKey ?: Regex("(?:\\bke|→|->|=|:)\\s*([A-G])([#b]?)(?![A-Za-z])").find(t)?.let {
            Notes.pc(it.groupValues[1][0], it.groupValues[2])
        }
        var delta: Int? = null
        Regex("([+-])\\s*(\\d{1,2})").find(t)?.let {
            delta = it.groupValues[2].toInt() * if (it.groupValues[1] == "-") -1 else 1
        }
        if (delta == null) {
            val down = Regex("(?i)\\bturun").containsMatchIn(t)
            val sign = if (down) -1 else 1
            when {
                Regex("(?i)(½|setengah|1/2)").containsMatchIn(t) -> delta = sign
                else -> Regex("(?i)(?:naik|turun)\\s+(\\d)").find(t)?.let { delta = sign * 2 * it.groupValues[1].toInt() }
            }
        }
        return Line.KeyChange(t, target, delta)
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

    fun fit(c: Chord, k: Int): Double {
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

/**
 * Menghitung Do yang berlaku di tiap baris (memperhitungkan modulasi).
 * Semua nilai dalam kunci ASLI lagu (sebelum transpose).
 */
object Modulation {

    /**
     * @param manual modulasi yang diatur sendiri: indeks baris -> Do baru.
     *               Nilai -1 berarti "abaikan penanda modulasi di baris ini".
     */
    fun keys(lines: List<Line>, startKey: Int, manual: Map<Int, Int> = emptyMap()): IntArray {
        val keys = IntArray(lines.size)
        var cur = startKey
        for (i in lines.indices) {
            val m = manual[i]
            if (m != null && m >= 0) {
                cur = m
            } else if (m == null) {
                val l = lines[i]
                if (l is Line.KeyChange) cur = resolve(l, lines, i, cur)
            }
            keys[i] = cur
        }
        return keys
    }

    /** Do baru untuk sebuah penanda modulasi. */
    fun resolve(l: Line.KeyChange, lines: List<Line>, idx: Int, cur: Int): Int {
        l.target?.let { return it }
        l.delta?.let { return Notes.mod12(cur + it) }
        // Tidak tertulis: tebak dari chord sesudahnya sampai penanda berikutnya
        val chords = ArrayList<Chord>()
        for (j in idx + 1 until lines.size) {
            val n = lines[j]
            if (n is Line.KeyChange) break
            if (n is Line.Chords) n.items.forEach { if (it is Item.ChordItem) chords.add(it.chord) }
        }
        if (chords.size < 3) return cur
        return KeyDetector.infer(chords)
    }

    /** Apakah baris ini penanda modulasi (dari teks atau diatur manual)? */
    fun isChangeAt(lines: List<Line>, manual: Map<Int, Int>, i: Int): Boolean {
        val m = manual[i]
        if (m != null) return m >= 0
        return lines[i] is Line.KeyChange
    }

    data class Suggestion(val lineIndex: Int, val newKey: Int, val sectionName: String)

    /**
     * Cari bagian (Reff, Bridge, ...) yang chord-chordnya jauh lebih cocok
     * dengan Do yang naik ½ atau 1 nada. Hanya menyarankan, tidak langsung dipakai.
     */
    fun suggest(lines: List<Line>, keys: IntArray, manual: Map<Int, Int>, dismissed: Set<Int>): Suggestion? {
        val starts = lines.indices.filter { lines[it] is Line.Section }
        for ((n, s) in starts.withIndex()) {
            if (n == 0 || s in dismissed || manual.containsKey(s)) continue
            val end = if (n + 1 < starts.size) starts[n + 1] else lines.size
            if ((s until end).any { lines[it] is Line.KeyChange || manual.containsKey(it) }) continue
            val chords = ArrayList<Chord>()
            for (j in s until end) {
                val l = lines[j]
                if (l is Line.Chords) l.items.forEach { if (it is Item.ChordItem) chords.add(it.chord) }
            }
            if (chords.size < 4) continue
            val cur = keys[s]
            val curScore = chords.sumOf { KeyDetector.fit(it, cur) }
            var best = -1
            var bestScore = curScore
            for (up in intArrayOf(1, 2)) {
                val k = Notes.mod12(cur + up)
                val sc = chords.sumOf { KeyDetector.fit(it, k) }
                if (sc > bestScore) { bestScore = sc; best = k }
            }
            if (best >= 0 && bestScore - curScore >= 2.5 && bestScore / chords.size >= 0.8) {
                return Suggestion(s, best, (lines[s] as Line.Section).text.trimEnd(':', ' '))
            }
        }
        return null
    }
}

enum class NumberMode { INLINE, BELOW, OFF }

data class Span(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind { CHORD, NUMBER, SECTION, LABEL, MODULATION }
}

/** @param src indeks baris asal di daftar Line (untuk tekan-lama atur modulasi) */
data class RenderedLine(val text: String, val spans: List<Span>, val src: Int = -1)

object Renderer {

    /**
     * @param keyPc kunci asli lagu (sebelum transpose)
     * @param transpose geser semitone
     */
    fun render(
        lines: List<Line>, keyPc: Int, transpose: Int, mode: NumberMode,
        manual: Map<Int, Int> = emptyMap(),
    ): List<RenderedLine> {
        val keys = Modulation.keys(lines, keyPc, manual)
        val out = ArrayList<RenderedLine>()
        for ((i, l) in lines.withIndex()) {
            val key = keys[i]
            val flats = Notes.useFlats(Notes.mod12(key + transpose))
            // Penanda modulasi (dari teks atau manual)
            if (Modulation.isChangeAt(lines, manual, i)) {
                val txt = "── Modulasi → Do = ${Notes.keyName(key + transpose)} ──"
                out.add(RenderedLine(txt, listOf(Span(0, txt.length, Span.Kind.MODULATION)), i))
                if (l is Line.KeyChange) continue
            } else if (l is Line.KeyChange) {
                // penanda yang diabaikan: tampilkan teks aslinya saja
                out.add(RenderedLine(l.text, listOf(Span(0, l.text.length, Span.Kind.LABEL)), i))
                continue
            }
            when (l) {
                is Line.Blank -> out.add(RenderedLine("", emptyList(), i))
                is Line.Lyric -> out.add(RenderedLine(l.text, emptyList(), i))
                is Line.Section -> out.add(RenderedLine(l.text, listOf(Span(0, l.text.length, Span.Kind.SECTION)), i))
                is Line.Chords -> renderChords(l, key, transpose, flats, mode).forEach { out.add(it.copy(src = i)) }
                is Line.KeyChange -> {}
            }
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
