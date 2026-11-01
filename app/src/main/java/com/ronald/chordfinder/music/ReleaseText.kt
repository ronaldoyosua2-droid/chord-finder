package com.ronald.chordfinder.music

/** Membaca info rilis GitHub (tanpa Android, supaya bisa diuji). */
object ReleaseText {
    /** "v1.0.10" -> 10 (nomor build = versionCode). */
    fun buildOf(tag: String): Int? = Regex("^v?\\d+\\.\\d+\\.(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Ambil bagian "What's new" dari deskripsi rilis (sampai garis --- atau judul berikutnya). */
    fun whatsNew(body: String): String {
        val lines = body.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("#") && it.contains("What's new", true) }
        if (start < 0) return ""
        val out = ArrayList<String>()
        for (l in lines.drop(start + 1)) {
            val t = l.trim()
            if (t == "---" || t.startsWith("#")) break
            out.add(l)
        }
        return out.joinToString("\n").trim()
    }

    /** Markdown sederhana -> teks: "- " jadi "• ", tanda ** dibuang. */
    fun plain(md: String): String = md.lines().joinToString("\n") { l ->
        val t = l.trimEnd()
        val b = if (t.trimStart().startsWith("- ") || t.trimStart().startsWith("* ")) "• " + t.trimStart().drop(2) else t
        b.replace("**", "").replace(Regex("`([^`]*)`"), "$1")
    }.trim()
}
