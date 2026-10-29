package com.ronald.chordfinder

import android.content.Context
import com.ronald.chordfinder.music.SongParser
import com.ronald.chordfinder.music.TextSearch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Song(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var source: String = "",
    var raw: String,
    /** Kunci asli (pitch class 0-11), -1 = belum ditentukan */
    var key: Int = -1,
    var transpose: Int = 0,
    var starred: Boolean = false,
    var updated: Long = System.currentTimeMillis(),
    /** Kata kunci pencarian YouTube (judul + penyanyi dari halaman sumber) */
    var query: String = "",
    /** Modulasi manual: indeks baris -> Do baru (-1 = abaikan penanda di baris itu) */
    var mods: MutableMap<Int, Int> = HashMap(),
    /** Saran modulasi yang sudah ditolak (indeks baris) */
    var modDismissed: MutableSet<Int> = HashSet(),
    var ytId: String = "",
    var ytTitle: String = "",
    var ytChannel: String = "",
) {
    @Transient private var index: String? = null

    /** Judul + lirik yang dinormalisasi, untuk pencarian potongan kalimat. */
    fun searchIndex(): String =
        index ?: TextSearch.normalize(title + " " + SongParser.lyricText(raw)).also { index = it }

    fun invalidate() { index = null }

    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("source", source); put("raw", raw)
        put("key", key); put("transpose", transpose); put("starred", starred); put("updated", updated)
        put("query", query)
        put("mods", JSONObject().apply { mods.forEach { (k, v) -> put(k.toString(), v) } })
        put("modDismissed", JSONArray().apply { modDismissed.forEach { put(it) } })
        put("ytId", ytId); put("ytTitle", ytTitle); put("ytChannel", ytChannel)
    }

    companion object {
        fun fromJson(o: JSONObject) = Song(
            id = o.getString("id"),
            title = o.optString("title"),
            source = o.optString("source"),
            raw = o.optString("raw"),
            key = o.optInt("key", -1),
            transpose = o.optInt("transpose", 0),
            starred = o.optBoolean("starred", false),
            updated = o.optLong("updated", 0L),
            query = o.optString("query"),
            mods = HashMap<Int, Int>().apply {
                o.optJSONObject("mods")?.let { m -> m.keys().forEach { k -> put(k.toInt(), m.getInt(k)) } }
            },
            modDismissed = HashSet<Int>().apply {
                o.optJSONArray("modDismissed")?.let { a -> for (i in 0 until a.length()) add(a.getInt(i)) }
            },
            ytId = o.optString("ytId"),
            ytTitle = o.optString("ytTitle"),
            ytChannel = o.optString("ytChannel"),
        )
    }
}

/** Koleksi lagu tersimpan offline di penyimpanan internal aplikasi. */
object SongStore {
    private var cache: MutableList<Song>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "songs.json")

    fun all(ctx: Context): MutableList<Song> {
        cache?.let { return it }
        val list = ArrayList<Song>()
        val f = file(ctx)
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) list.add(Song.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                // File rusak: simpan cadangan supaya data tidak hilang begitu saja
                f.copyTo(File(ctx.filesDir, "songs.broken.${System.currentTimeMillis()}.json"), true)
            }
        }
        cache = list
        return list
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun findBySource(ctx: Context, url: String): Song? =
        if (url.isBlank()) null else all(ctx).firstOrNull { it.source == url }

    fun upsert(ctx: Context, song: Song) {
        val list = all(ctx)
        song.updated = System.currentTimeMillis()
        song.invalidate()
        val i = list.indexOfFirst { it.id == song.id }
        if (i >= 0) list[i] = song else list.add(0, song)
        persist(ctx)
    }

    fun delete(ctx: Context, id: String) {
        all(ctx).removeAll { it.id == id }
        persist(ctx)
    }

    fun clearStars(ctx: Context) {
        all(ctx).forEach { it.starred = false }
        persist(ctx)
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        all(ctx).forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "songs.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    fun numberMode(ctx: Context) = sp(ctx).getString("numMode", "INLINE")!!
    fun setNumberMode(ctx: Context, v: String) = sp(ctx).edit().putString("numMode", v).apply()
    fun fontSize(ctx: Context) = sp(ctx).getFloat("font", 15f)
    fun setFontSize(ctx: Context, v: Float) = sp(ctx).edit().putFloat("font", v).apply()
    fun source(ctx: Context) = sp(ctx).getInt("source", 0)
    fun setSource(ctx: Context, v: Int) = sp(ctx).edit().putInt("source", v).apply()
}
