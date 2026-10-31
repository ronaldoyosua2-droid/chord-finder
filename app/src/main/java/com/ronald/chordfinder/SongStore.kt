package com.ronald.chordfinder

import android.content.Context
import com.ronald.chordfinder.music.Note
import com.ronald.chordfinder.music.SeqItem
import com.ronald.chordfinder.music.SongParser
import com.ronald.chordfinder.music.Step
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
    /** Catatan lagu (tampil di atas) */
    var songNote: String = "",
    /** Jembatan chord & catatan per baris: indeks baris -> daftar catatan */
    var notes: MutableMap<Int, MutableList<Note>> = HashMap(),
    // Metronom (0 = belum diatur)
    var bpm: Int = 0,
    var sigBeats: Int = 0,
    var sigUnit: Int = 4,
    var pattern: String = "",          // mis. "ANNN" (A = aksen, N = biasa, M = diam)
    var seq: MutableList<SeqItem> = ArrayList(),
    var useSeq: Boolean = false,
    // Sinkron metronom ke video: detik di video tempat bar 1 ketukan 1 dimulai (-1 = belum)
    var syncOffset: Double = -1.0,
    var syncBpm: Double = 0.0,
    var syncVideo: String = "",
    var syncOn: Boolean = true,
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
        put("songNote", songNote)
        put("notes", JSONObject().apply {
            notes.forEach { (k, list) ->
                put(k.toString(), JSONArray().apply {
                    list.forEach { n -> put(JSONObject().put("b", n.bridge).put("t", n.text)) }
                })
            }
        })
        put("bpm", bpm); put("sigBeats", sigBeats); put("sigUnit", sigUnit); put("pattern", pattern)
        put("seq", JSONArray().apply {
            seq.forEach { put(JSONObject().put("n", it.name).put("b", it.bars).put("c", it.cue).put("l", it.line)) }
        })
        put("useSeq", useSeq)
        put("syncOffset", syncOffset); put("syncBpm", syncBpm); put("syncVideo", syncVideo); put("syncOn", syncOn)
    }

    /** Sinkron hanya berlaku untuk video yang ditandai. */
    val hasSync get() = syncOffset >= 0 && syncVideo.isNotEmpty() && syncVideo == ytId && syncBpm > 0

    fun stepPattern(beats: Int): List<Step> = List(beats) { i ->
        when (pattern.getOrNull(i)) {
            'A' -> Step.ACCENT; 'M' -> Step.MUTE; 'N' -> Step.NORMAL
            else -> if (i == 0) Step.ACCENT else Step.NORMAL
        }
    }

    fun setStepPattern(p: List<Step>) {
        pattern = p.joinToString("") { when (it) { Step.ACCENT -> "A"; Step.MUTE -> "M"; Step.NORMAL -> "N" } }
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
            songNote = o.optString("songNote"),
            notes = HashMap<Int, MutableList<Note>>().apply {
                o.optJSONObject("notes")?.let { m ->
                    m.keys().forEach { k ->
                        val a = m.getJSONArray(k)
                        put(k.toInt(), MutableList(a.length()) { i ->
                            val n = a.getJSONObject(i); Note(n.optBoolean("b"), n.optString("t"))
                        })
                    }
                }
            },
            bpm = o.optInt("bpm", 0),
            sigBeats = o.optInt("sigBeats", 0),
            sigUnit = o.optInt("sigUnit", 4),
            pattern = o.optString("pattern"),
            seq = ArrayList<SeqItem>().apply {
                o.optJSONArray("seq")?.let { a ->
                    for (i in 0 until a.length()) {
                        val x = a.getJSONObject(i)
                        add(SeqItem(x.optString("n"), x.optInt("b", 4), x.optString("c"), x.optInt("l", -1)))
                    }
                }
            },
            useSeq = o.optBoolean("useSeq", false),
            syncOffset = o.optDouble("syncOffset", -1.0),
            syncBpm = o.optDouble("syncBpm", 0.0),
            syncVideo = o.optString("syncVideo"),
            syncOn = o.optBoolean("syncOn", true),
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
        SetlistStore.removeSong(ctx, id)
    }

    /** Untuk impor: simpan apa adanya (tanpa mengubah waktu 'updated'). */
    fun importSong(ctx: Context, song: Song) {
        val list = all(ctx)
        song.invalidate()
        val i = list.indexOfFirst { it.id == song.id }
        if (i >= 0) list[i] = song else list.add(song)
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
    // Metronom (pengaturan umum, berlaku untuk semua lagu)
    fun sound(ctx: Context) = sp(ctx).getString("mSound", "kayu")!!
    fun setSound(ctx: Context, v: String) = sp(ctx).edit().putString("mSound", v).apply()
    fun voice(ctx: Context) = sp(ctx).getString("mVoice", "A")!!
    fun setVoice(ctx: Context, v: String) = sp(ctx).edit().putString("mVoice", v).apply()
    fun countIn(ctx: Context) = sp(ctx).getBoolean("mCount", true)
    fun setCountIn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("mCount", v).apply()
    fun cues(ctx: Context) = sp(ctx).getBoolean("mCues", true)
    fun setCues(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("mCues", v).apply()
    fun subdivision(ctx: Context) = sp(ctx).getInt("mSub", 1)
    fun setSubdivision(ctx: Context, v: Int) = sp(ctx).edit().putInt("mSub", v).apply()
    fun followScroll(ctx: Context) = sp(ctx).getBoolean("mFollow", true)
    fun setFollowScroll(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("mFollow", v).apply()
    fun setSource(ctx: Context, v: Int) = sp(ctx).edit().putInt("source", v).apply()
}
