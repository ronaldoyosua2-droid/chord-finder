package com.ronald.chordfinder

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ronald.chordfinder.music.Notes
import com.ronald.chordfinder.music.TextSearch
import java.util.Calendar

/** Satu daftar ibadah: urutan lagu untuk satu tanggal. */
class SetlistActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "setlist"
    }

    private lateinit var setlist: Setlist
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: Adapter
    private lateinit var touch: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = intent.getStringExtra(EXTRA_ID)?.let { SetlistStore.get(this, it) }
        if (s == null) { finish(); return }
        setlist = s
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi().apply { fitSystemBars() })
    }

    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) refresh()
    }

    private fun save() = SetlistStore.persist(this)

    private fun small(t: TextView) = t.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setPadding(dp(12), dp(9), dp(12), dp(9))
    }

    private fun buildUi(): View {
        val root = vbox().apply { setBackgroundColor(C.BG) }
        val top = hbox().apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        top.addView(label("←", 22f, C.TEXT).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { finish() }
        })
        val titles = vbox().apply { setOnClickListener { editDialog() } }
        titleView = label("", 18f, C.TEXT, bold = true)
        subView = label("", 12f, C.MUTED)
        titles.addView(titleView); titles.addView(subView)
        top.addView(titles, lp(0, WRAP, 1f))
        top.addView(label("⋮", 22f, C.TEXT).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { v -> menu(v) }
        })
        root.addView(top)

        empty = label("Belum ada lagu.\nTekan + Tambah lagu, atau salin dari minggu lalu.", 14.5f, C.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(40), dp(24), dp(16))
        }
        root.addView(empty)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SetlistActivity)
            setPadding(dp(12), 0, dp(12), dp(8)); clipToPadding = false
        }
        adapter = Adapter()
        list.adapter = adapter
        touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition; val to = b.bindingAdapterPosition
                val it = setlist.items.removeAt(from); setlist.items.add(to, it)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun isLongPressDragEnabled() = false
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh); save(); adapter.notifyDataSetChanged()
            }
        })
        touch.attachToRecyclerView(list)
        root.addView(list, lp(MATCH, 0, 1f))

        val bottom = vbox().apply { setPadding(dp(12), dp(6), dp(12), dp(12)) }
        val row = hbox()
        row.addView(small(pill("+ Tambah lagu") { addDialog() }), lp(0, WRAP, 1f))
        row.addView(small(pill("⧉ Salin dari minggu lalu") { copyPrevious() }), lp(0, WRAP, 1f).apply { leftMargin = dp(6) })
        bottom.addView(row)
        bottom.addView(label("▶ Mulai dari lagu 1", 15.5f, 0xFF0B2A14.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            background = rounded(C.GO, dpf(16f))
            isClickable = true
            setOnClickListener { open(0) }
        }, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        root.addView(bottom)
        refresh()
        return root
    }

    private fun refresh() {
        // Buang lagu yang sudah dihapus dari Koleksi
        if (setlist.items.removeAll { SongStore.get(this, it.songId) == null }) save()
        titleView.text = Dates.pretty(setlist.date)
        val d = Dates.daysFromToday(setlist.date)
        val tag = when { d == 0 -> " · HARI INI"; d == 1 -> " · BESOK"; else -> "" }
        subView.text = "${setlist.name} · ${setlist.items.size} lagu$tag · ketuk untuk ubah"
        empty.visibility = if (setlist.items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    fun open(index: Int) {
        if (setlist.items.isEmpty()) { addDialog(); return }
        val item = setlist.items.getOrNull(index) ?: return
        startActivity(Intent(this, SongActivity::class.java)
            .putExtra(SongActivity.EXTRA_ID, item.songId)
            .putExtra(SongActivity.EXTRA_SETLIST, setlist.id)
            .putExtra(SongActivity.EXTRA_INDEX, index))
    }

    private fun subtitle(item: SetItem, song: Song): String {
        val tr = item.transpose ?: song.transpose
        val key = if (song.key >= 0) "Do = " + Notes.keyName(song.key + tr) + (if (tr != 0) " (${if (tr > 0) "+" else ""}$tr)" else "") else "Kunci ?"
        val bpm = if (song.bpm > 0) " · ♩ ${song.bpm}" else ""
        val others = SetlistStore.containing(this, song.id).filter { it.id != setlist.id }
        val next = others.filter { it.date > setlist.date }.minByOrNull { it.date }
        val prev = others.filter { it.date < setlist.date }.maxByOrNull { it.date }
        val extra = when {
            next != null -> " · juga di ${Dates.short(next.date)}"
            prev != null -> " · pernah ${Dates.short(prev.date)}"
            else -> ""
        }
        return key + bpm + extra
    }

    private inner class Holder(v: LinearLayout) : RecyclerView.ViewHolder(v) {
        val num: TextView = label("", 13f, C.CHORD, bold = true).apply { gravity = Gravity.CENTER; background = rounded(C.SURFACE2, dpf(15f)) }
        val title: TextView = label("", 15.5f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub: TextView = label("", 12.5f, C.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val handle: TextView = label("≡", 22f, C.MUTED).apply { setPadding(dp(14), dp(6), dp(8), dp(6)) }
        init {
            v.addView(num, lp(dp(30), dp(30)))
            val t = vbox().apply { setPadding(dp(12), 0, 0, 0) }
            t.addView(title); t.addView(sub)
            v.addView(t, lp(0, WRAP, 1f))
            v.addView(handle)
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = setlist.items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = hbox().apply {
                setPadding(dp(12), dp(11), dp(4), dp(11))
                background = rounded(C.SURFACE, dpf(14f))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
            }
            return Holder(v)
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = setlist.items[position]
            val song = SongStore.get(this@SetlistActivity, item.songId) ?: return
            h.num.text = "${position + 1}"
            h.title.text = song.title
            h.sub.text = subtitle(item, song)
            h.itemView.setOnClickListener { open(h.bindingAdapterPosition) }
            h.itemView.setOnLongClickListener { itemMenu(h.bindingAdapterPosition, it); true }
            h.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touch.startDrag(h)
                false
            }
        }
    }

    private fun itemMenu(pos: Int, anchor: View) {
        val item = setlist.items.getOrNull(pos) ?: return
        val song = SongStore.get(this, item.songId) ?: return
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Buka")
        if (item.transpose != null) pm.menu.add(0, 2, 1, "Pakai kunci umum lagu (${Notes.keyName(song.key + song.transpose)})")
        pm.menu.add(0, 3, 2, "Hapus dari daftar ini")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(pos)
                2 -> { item.transpose = null; save(); refresh() }
                3 -> { setlist.items.removeAt(pos); save(); refresh() }
            }
            true
        }
        pm.show()
    }

    private fun menu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Ubah tanggal / nama")
        pm.menu.add(0, 2, 1, "Bagikan daftar (teks)")
        pm.menu.add(0, 3, 2, "Hapus daftar ini")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> editDialog()
                2 -> share()
                3 -> AlertDialog.Builder(this)
                    .setMessage("Hapus daftar ${Dates.pretty(setlist.date)}? Lagunya tetap ada di Koleksi.")
                    .setPositiveButton("Hapus") { _, _ -> SetlistStore.delete(this, setlist.id); finish() }
                    .setNegativeButton("Batal", null).show()
            }
            true
        }
        pm.show()
    }

    private fun share() {
        val sb = StringBuilder("${Dates.pretty(setlist.date)} · ${setlist.name}\n")
        setlist.items.forEachIndexed { i, it ->
            val s = SongStore.get(this, it.songId) ?: return@forEachIndexed
            val tr = it.transpose ?: s.transpose
            sb.append("${i + 1}. ${s.title}").append(if (s.key >= 0) " (Do = ${Notes.keyName(s.key + tr)})" else "").append('\n')
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString().trim())
        }, "Bagikan daftar"))
    }

    fun editDialog() = SetlistDialogs.edit(this, setlist) { save(); refresh() }

    private fun copyPrevious() {
        val prev = SetlistStore.previous(this, setlist.date, setlist.id)
        if (prev == null) { Toast.makeText(this, "Belum ada daftar sebelumnya", Toast.LENGTH_SHORT).show(); return }
        val names = prev.items.mapNotNull { SongStore.get(this, it.songId)?.title }
        val checked = BooleanArray(names.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Salin dari ${Dates.pretty(prev.date)}")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, w, on -> checked[w] = on }
            .setPositiveButton("Salin") { _, _ ->
                var n = 0
                prev.items.forEachIndexed { i, it ->
                    if (checked.getOrElse(i) { false } && setlist.items.none { x -> x.songId == it.songId }) {
                        setlist.items.add(it.copy()); n++
                    }
                }
                save(); refresh()
                Toast.makeText(this, "$n lagu disalin", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null).show()
    }

    /** Pilih lagu dari Koleksi (bisa cari judul/lirik, centang beberapa). */
    private fun addDialog() {
        val songs = SongStore.all(this).sortedBy { it.title.lowercase() }
        if (songs.isEmpty()) {
            Toast.makeText(this, "Koleksi masih kosong. Simpan lagu dulu dari Cari Online.", Toast.LENGTH_LONG).show(); return
        }
        val picked = LinkedHashSet<String>()
        val dlg = Dialog(this)
        val root = vbox().apply {
            setPadding(dp(14), dp(16), dp(14), dp(14))
            background = rounded(C.SURFACE, dpf(20f))
        }
        root.addView(label("Tambah lagu", 17f, C.TEXT, bold = true))
        val input = EditText(this).apply {
            hint = "Cari judul / lirik…"; setSingleLine()
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
            background = rounded(C.BG, dpf(12f), dp(1), C.LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(input, lp(MATCH, WRAP).apply { topMargin = dp(10); bottomMargin = dp(8) })
        val box = vbox()
        root.addView(ScrollView(this).apply { addView(box) }, lp(MATCH, (resources.displayMetrics.heightPixels * 0.5).toInt()))
        val done = small(pill("Tambahkan") {
            picked.forEach { id -> if (setlist.items.none { it.songId == id }) setlist.items.add(SetItem(id)) }
            save(); refresh(); dlg.dismiss()
        }).apply { setPill(true); small(this) }

        fun paint() {
            box.removeAllViews()
            val q = input.text.toString()
            songs.filter { TextSearch.matches(it.searchIndex(), q) }.forEach { s ->
                val already = setlist.items.any { it.songId == s.id }
                val on = s.id in picked || already
                val row = hbox().apply {
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    isClickable = !already
                    setOnClickListener {
                        if (s.id in picked) picked.remove(s.id) else picked.add(s.id)
                        paint()
                    }
                }
                row.addView(label(if (on) "✓" else "", 14f, C.BG, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = if (on) rounded(if (already) C.MUTED else C.CHORD, dpf(6f)) else rounded(0, dpf(6f), dp(2), C.LINE)
                }, lp(dp(22), dp(22)))
                val t = vbox().apply { setPadding(dp(12), 0, 0, 0) }
                t.addView(label(s.title, 14.5f, if (already) C.MUTED else C.TEXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                if (already) t.addView(label("sudah ada di daftar", 11.5f, C.MUTED))
                row.addView(t, lp(0, WRAP, 1f))
                box.addView(row)
            }
            done.text = if (picked.isEmpty()) "Tambahkan" else "Tambahkan ${picked.size} lagu"
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) = paint()
        })
        paint()
        val bottom = hbox().apply { setPadding(0, dp(10), 0, 0) }
        bottom.addView(small(pill("Batal") { dlg.dismiss() }))
        bottom.addView(View(this), lp(0, 1, 1f))
        bottom.addView(done)
        root.addView(bottom)
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }
}

object SetlistDialogs {
    /** Ubah tanggal & nama daftar. */
    fun edit(a: AppCompatActivity, s: Setlist, done: () -> Unit) {
        val box = a.vbox().apply { setPadding(a.dp(20), a.dp(8), a.dp(20), 0) }
        var date = s.date
        val dateBtn = a.pill(Dates.pretty(date)) {}.apply { setPill(true) }
        dateBtn.setOnClickListener {
            val c = Dates.parse(date) ?: Calendar.getInstance()
            DatePickerDialog(a, { _, y, m, d ->
                val nc = Calendar.getInstance().apply { set(y, m, d) }
                date = Dates.format(nc); dateBtn.text = Dates.pretty(date)
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        box.addView(a.label("Tanggal", 12f, C.MUTED))
        box.addView(dateBtn, lp(MATCH, WRAP).apply { topMargin = a.dp(4); bottomMargin = a.dp(12) })
        box.addView(a.label("Nama ibadah", 12f, C.MUTED))
        val name = EditText(a).apply { setText(s.name); setSingleLine(); setTextColor(C.TEXT) }
        box.addView(name, lp(MATCH, WRAP))
        val chips = a.hbox()
        listOf("Ibadah Raya", "Ibadah Pemuda", "Sekolah Minggu", "Doa Malam").forEach { n ->
            chips.addView(a.pill(n) { name.setText(n) }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f) },
                lp(WRAP, WRAP).apply { rightMargin = a.dp(5) })
        }
        box.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        AlertDialog.Builder(a).setTitle("Daftar ibadah").setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                s.date = date; s.name = name.text.toString().trim().ifEmpty { "Ibadah Raya" }; done()
            }
            .setNegativeButton("Batal", null).show()
    }

    /** Buat daftar baru (tanggal default: Minggu terdekat). */
    fun create(a: AppCompatActivity, withSong: String? = null, done: (Setlist) -> Unit) {
        val s = Setlist(date = Dates.nextSunday())
        if (withSong != null) s.items.add(SetItem(withSong))
        edit(a, s) { SetlistStore.upsert(a, s); done(s) }
    }
}
