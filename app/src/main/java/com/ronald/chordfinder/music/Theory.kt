package com.ronald.chordfinder.music

/**
 * Teori dasar: nada, chord, transpose, dan notasi angka.
 * Murni Kotlin (tanpa Android) supaya bisa diuji di JVM.
 */
object Notes {
    private val SHARP = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** Kunci (do) yang lazim ditulis dengan mol (b). */
    private val FLAT_KEYS = setOf(5, 10, 3, 8, 1) // F, Bb, Eb, Ab, Db

    fun pc(letter: Char, accidental: String): Int {
        val base = when (letter) {
            'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11
            else -> throw IllegalArgumentException("Bukan nada: $letter")
        }
        val acc = when (accidental) { "#" -> 1; "b" -> -1; else -> 0 }
        return mod12(base + acc)
    }

    fun parseNote(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty() || t[0] !in "ABCDEFG") return null
        val acc = if (t.length > 1 && (t[1] == '#' || t[1] == 'b')) t[1].toString() else ""
        if (t.length != 1 + acc.length) return null
        return pc(t[0], acc)
    }

    fun useFlats(keyPc: Int) = mod12(keyPc) in FLAT_KEYS

    fun name(pc: Int, flats: Boolean): String = if (flats) FLAT[mod12(pc)] else SHARP[mod12(pc)]

    /** Nama kunci yang paling umum dipakai (Bb bukan A#, F# bukan Gb, dst). */
    fun keyName(pc: Int): String = name(pc, useFlats(pc))

    /** Notasi angka relatif terhadap do. */
    fun degree(notePc: Int, keyPc: Int): String = when (mod12(notePc - keyPc)) {
        0 -> "1"; 1 -> "b2"; 2 -> "2"; 3 -> "b3"; 4 -> "3"; 5 -> "4"
        6 -> "#4"; 7 -> "5"; 8 -> "b6"; 9 -> "6"; 10 -> "b7"; else -> "7"
    }

    fun mod12(x: Int) = ((x % 12) + 12) % 12
}

enum class Quality { MAJOR, MINOR, DIM, NEUTRAL }

data class Chord(val root: Int, val suffix: String, val bass: Int?) {

    val quality: Quality
        get() {
            val s = suffix
            return when {
                s.startsWith("dim") || s.startsWith("°") || s.startsWith("ø") -> Quality.DIM
                s.startsWith("m") && !s.startsWith("maj") -> Quality.MINOR
                s.startsWith("min") -> Quality.MINOR
                s.startsWith("sus") || s == "5" -> Quality.NEUTRAL
                else -> Quality.MAJOR
            }
        }

    fun transpose(semi: Int) = Chord(
        Notes.mod12(root + semi), suffix, bass?.let { Notes.mod12(it + semi) }
    )

    fun format(flats: Boolean): String {
        val b = bass?.let { "/" + Notes.name(it, flats) } ?: ""
        return Notes.name(root, flats) + suffix + b
    }

    /** Contoh kunci A: Bm -> "2", A/C# -> "1/3". */
    fun number(keyPc: Int): String {
        val r = Notes.degree(root, keyPc)
        return if (bass != null) r + "/" + Notes.degree(bass, keyPc) else r
    }

    companion object {
        private val ROOT = Regex("^([A-G])([#b]?)(.*)$")
        private val SUFFIX = Regex(
            "^(?:maj|min|dim|aug|sus|add|no|omit|m|M|[0-9]|#|b|\\+|-|°|ø|\\(|\\)|,|\\^)*$"
        )

        fun parse(token: String): Chord? {
            if (token.isEmpty() || token.length > 14) return null
            val slash = token.indexOf('/')
            val main = if (slash >= 0) token.substring(0, slash) else token
            val bassStr = if (slash >= 0) token.substring(slash + 1) else null
            val m = ROOT.matchEntire(main) ?: return null
            val suffix = m.groupValues[3]
            if (!SUFFIX.matches(suffix)) return null
            // Kurung harus seimbang, mis. "Asus(4)" boleh, "A(" tidak
            if (suffix.count { it == '(' } != suffix.count { it == ')' }) return null
            val bass = if (bassStr != null) {
                // Mendukung "C/E" dan bentuk angka seperti "C/9" (dianggap bukan bass)
                Notes.parseNote(bassStr) ?: if (bassStr.all { it.isDigit() }) null else return null
            } else null
            val fullSuffix = if (bassStr != null && bass == null) "$suffix/$bassStr" else suffix
            return Chord(Notes.pc(m.groupValues[1][0], m.groupValues[2]), fullSuffix, bass)
        }
    }
}
