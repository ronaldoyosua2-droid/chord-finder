package com.ronald.chordfinder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** Satu lagu di daftar ibadah. [transpose] = kunci khusus untuk tanggal ini (null = ikut lagu). */
data class SetItem(val songId: String, var transpose: Int? = null)

/** Daftar ibadah untuk satu tanggal. [date] format yyyy-MM-dd. */
data class Setlist(
    val id: String = UUID.randomUUID().toString(),
    var date: String,
    var name: String = "Ibadah Raya",
    val items: MutableList<SetItem> = ArrayList(),
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("date", date); put("name", name)
        put("items", JSONArray().apply {
            items.forEach { put(JSONObject().put("s", it.songId).apply { it.transpose?.let { t -> put("t", t) } }) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject) = Setlist(
            id = o.getString("id"),
            date = o.optString("date"),
            name = o.optString("name", "Ibadah Raya"),
            items = ArrayList<SetItem>().apply {
                o.optJSONArray("items")?.let { a ->
                    for (i in 0 until a.length()) {
                        val x = a.getJSONObject(i)
                        add(SetItem(x.getString("s"), if (x.has("t")) x.getInt("t") else null))
                    }
                }
            },
        )
    }
}

object Dates {
    private val ISO = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val ID = Locale("in", "ID")

    fun today(): String = ISO.format(Calendar.getInstance().time)

    /** Minggu terdekat mulai hari ini. */
    fun nextSunday(): String {
        val c = Calendar.getInstance()
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, 1)
        return ISO.format(c.time)
    }

    fun parse(d: String): Calendar? = try {
        Calendar.getInstance().apply { time = ISO.parse(d)!! }
    } catch (e: Exception) { null }

    fun format(c: Calendar): String = ISO.format(c.time)

    /** "Minggu, 2 Nov 2026" */
    fun pretty(d: String): String {
        val c = parse(d) ?: return d
        return SimpleDateFormat("EEEE, d MMM yyyy", ID).format(c.time)
    }

    /** "2 Nov" */
    fun short(d: String): String {
        val c = parse(d) ?: return d
        return SimpleDateFormat("d MMM", ID).format(c.time)
    }

    /** Selisih hari dari hari ini (0 = hari ini, 1 = besok, negatif = lewat). */
    fun daysFromToday(d: String): Int {
        val c = parse(d) ?: return 0
        val t = parse(today())!!
        return ((c.timeInMillis - t.timeInMillis) / 86_400_000L).toInt()
    }
}

object SetlistStore {
    private var cache: MutableList<Setlist>? = null
    private fun file(ctx: Context) = File(ctx.filesDir, "setlists.json")

    fun all(ctx: Context): MutableList<Setlist> {
        cache?.let { return it }
        val list = ArrayList<Setlist>()
        val f = file(ctx)
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) list.add(Setlist.fromJson(arr.getJSONObject(i)))
            } catch (e: Exception) {
                f.copyTo(File(ctx.filesDir, "setlists.broken.${System.currentTimeMillis()}.json"), true)
            }
        }
        cache = list
        return list
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    /** Akan datang (terdekat dulu), lalu yang sudah lewat (terbaru dulu). */
    fun sorted(ctx: Context): Pair<List<Setlist>, List<Setlist>> {
        val (up, past) = all(ctx).partition { Dates.daysFromToday(it.date) >= 0 }
        return up.sortedBy { it.date } to past.sortedByDescending { it.date }
    }

    fun upsert(ctx: Context, s: Setlist) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        persist(ctx)
    }

    fun delete(ctx: Context, id: String) {
        all(ctx).removeAll { it.id == id }
        persist(ctx)
    }

    /** Hapus lagu dari semua daftar (dipanggil saat lagu dihapus dari Koleksi). */
    fun removeSong(ctx: Context, songId: String) {
        var changed = false
        all(ctx).forEach { s -> if (s.items.removeAll { it.songId == songId }) changed = true }
        if (changed) persist(ctx)
    }

    fun containing(ctx: Context, songId: String) = all(ctx).filter { s -> s.items.any { it.songId == songId } }

    /** Daftar terbaru sebelum tanggal [date] (untuk "Salin dari minggu lalu"). */
    fun previous(ctx: Context, date: String, exceptId: String) =
        all(ctx).filter { it.id != exceptId && it.date < date && it.items.isNotEmpty() }.maxByOrNull { it.date }

    fun persist(ctx: Context) {
        val arr = JSONArray()
        all(ctx).forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "setlists.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    fun replaceAll(ctx: Context, list: List<Setlist>) {
        val c = all(ctx); c.clear(); c.addAll(list); persist(ctx)
    }
}

/** Ekspor/impor seluruh Koleksi (lagu + daftar ibadah) ke satu file JSON. */
object Backup {
    fun export(ctx: Context): String = JSONObject().apply {
        put("app", "chord-finder")
        put("version", 1)
        put("exported", System.currentTimeMillis())
        put("songs", JSONArray().apply { SongStore.all(ctx).forEach { put(it.toJson()) } })
        put("setlists", JSONArray().apply { SetlistStore.all(ctx).forEach { put(it.toJson()) } })
    }.toString(1)

    data class Result(val songsAdded: Int, val songsUpdated: Int, val setlistsAdded: Int, val setlistsUpdated: Int)

    /** Gabungkan: lagu/daftar dengan id sama diganti versi file kalau lebih baru, sisanya ditambahkan. */
    fun import(ctx: Context, text: String): Result {
        val o = JSONObject(text)
        require(o.optString("app") == "chord-finder") { "Bukan file ekspor Chord Finder" }
        var sa = 0; var su = 0; var la = 0; var lu = 0
        val songs = o.optJSONArray("songs") ?: JSONArray()
        for (i in 0 until songs.length()) {
            val s = Song.fromJson(songs.getJSONObject(i))
            val cur = SongStore.get(ctx, s.id)
            when {
                cur == null -> { SongStore.importSong(ctx, s); sa++ }
                s.updated > cur.updated -> { SongStore.importSong(ctx, s); su++ }
            }
        }
        val lists = o.optJSONArray("setlists") ?: JSONArray()
        val all = SetlistStore.all(ctx)
        for (i in 0 until lists.length()) {
            val s = Setlist.fromJson(lists.getJSONObject(i))
            val idx = all.indexOfFirst { it.id == s.id }
            if (idx >= 0) { all[idx] = s; lu++ } else { all.add(s); la++ }
        }
        SetlistStore.persist(ctx)
        return Result(sa, su, la, lu)
    }
}
