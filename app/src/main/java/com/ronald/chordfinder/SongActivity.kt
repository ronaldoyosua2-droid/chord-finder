package com.ronald.chordfinder

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ronald.chordfinder.music.KeyDetector
import com.ronald.chordfinder.music.Line
import com.ronald.chordfinder.music.Notes
import com.ronald.chordfinder.music.NumberMode
import com.ronald.chordfinder.music.Renderer
import com.ronald.chordfinder.music.SongParser
import com.ronald.chordfinder.music.Span

class SongActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOURCE = "source"
        private val SPEEDS = intArrayOf(1, 2, 3, 4, 6)
    }

    private lateinit var song: Song
    private var saved = false
    private var lines: List<Line> = emptyList()
    private var keyExplicit = false

    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var starView: TextView
    private lateinit var body: TextView
    private lateinit var keyBtn: TextView
    private lateinit var numBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var scroll: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private var scrolling = false
    private var speedIdx = 1
    private val scroller = object : Runnable {
        override fun run() {
            if (!scrolling) return
            scroll.scrollBy(0, SPEEDS[speedIdx])
            if (!scroll.canScrollVertically(1)) { setScrolling(false); return }
            handler.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra(EXTRA_ID)
        val existing = id?.let { SongStore.get(this, it) }
        if (existing != null) {
            song = existing
            saved = true
        } else {
            song = Song(
                title = intent.getStringExtra(EXTRA_TITLE) ?: "Tanpa judul",
                raw = intent.getStringExtra(EXTRA_TEXT) ?: "",
                source = intent.getStringExtra(EXTRA_SOURCE) ?: "",
            )
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi().apply { fitSystemBars() })
        reparse()
    }

    override fun onPause() {
        super.onPause()
        setScrolling(false)
    }

    private fun reparse() {
        lines = SongParser.parse(song.raw)
        val det = KeyDetector.detect(song.raw, lines)
        keyExplicit = det?.explicit == true
        if (song.key < 0) song.key = det?.keyPc ?: 0
        render()
    }

    private fun persistIfSaved() { if (saved) SongStore.upsert(this, song) }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = vbox().apply { setBackgroundColor(C.BG) }

        // Bar atas
        val top = hbox().apply { setPadding(dp(4), dp(8), dp(4), dp(8)) }
        top.addView(iconBtn("←") { finish() })
        val titles = vbox()
        titleView = label(song.title, 18f, C.TEXT, bold = true).apply { maxLines = 2 }
        subView = label("", 12f, C.MUTED).apply { maxLines = 1 }
        titles.addView(titleView)
        titles.addView(subView)
        titles.setOnClickListener { renameDialog() }
        top.addView(titles, lp(0, WRAP, 1f))
        starView = iconBtn("☆") {
            song.starred = !song.starred
            if (song.starred && !saved) save()
            persistIfSaved()
            updateTop()
        }
        top.addView(starView)
        top.addView(iconBtn("⋮") { v -> menu(v) })
        root.addView(top)

        // Isi chord (gulir dua arah supaya baris panjang tidak patah dan tetap sejajar)
        scroll = ScrollView(this).apply { isFillViewport = true }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(C.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Prefs.fontSize(this@SongActivity))
            setLineSpacing(0f, 1.12f)
            setPadding(dp(16), dp(8), dp(24), dp(120))
            setTextIsSelectable(false)
        }
        hs.addView(body)
        scroll.addView(hs)
        // Ketuk teks untuk jeda/lanjut auto-scroll
        body.setOnClickListener { if (scrolling) setScrolling(false) }
        root.addView(scroll, lp(MATCH, 0, 1f))

        // Panel kontrol bawah
        val panel = vbox().apply {
            setBackgroundColor(C.SURFACE)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        val row1 = hbox()
        row1.addView(pill("−") { transpose(-1) })
        keyBtn = pill("Do = ?") { keyDialog() }.apply { setPill(true) }
        row1.addView(keyBtn, lp(0, WRAP, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        row1.addView(pill("+") { transpose(1) })
        numBtn = pill("") { cycleNumberMode() }
        row1.addView(numBtn, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        panel.addView(row1)

        val row2 = hbox().apply { setPadding(0, dp(8), 0, 0) }
        row2.addView(pill("A−") { font(-1f) })
        row2.addView(pill("A+") { font(1f) }, lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        playBtn = pill("▶ Scroll") { setScrolling(!scrolling) }
        row2.addView(playBtn, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        speedBtn = pill("") { speedIdx = (speedIdx + 1) % SPEEDS.size; updateSpeed() }
        row2.addView(speedBtn, lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        row2.addView(View(this), lp(0, 1, 1f))
        saveBtn = pill("Simpan") { if (!saved) save() }
        row2.addView(saveBtn)
        panel.addView(row2)
        root.addView(panel)

        updateTop()
        updateSpeed()
        return root
    }

    private fun iconBtn(text: String, onClick: (View) -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(C.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isClickable = true
        setOnClickListener(onClick)
    }

    private fun updateTop() {
        titleView.text = song.title
        val host = song.source.takeIf { it.isNotBlank() }?.let { Uri.parse(it).host?.removePrefix("www.") } ?: "chord manual"
        subView.text = host
        starView.text = if (song.starred) "★" else "☆"
        starView.setTextColor(if (song.starred) C.STAR else C.TEXT)
        saveBtn.text = if (saved) "✓ Tersimpan" else "Simpan"
        saveBtn.setPill(!saved)
    }

    private fun mode(): NumberMode = NumberMode.valueOf(Prefs.numberMode(this))

    private fun render() {
        val target = Notes.mod12(song.key + song.transpose)
        val shift = when {
            song.transpose == 0 -> ""
            song.transpose > 0 -> "  (+${song.transpose})"
            else -> "  (${song.transpose})"
        }
        keyBtn.text = "Do = " + Notes.keyName(target) + shift
        numBtn.text = when (mode()) {
            NumberMode.INLINE -> "Angka: Bm(2)"
            NumberMode.BELOW -> "Angka: bawah"
            NumberMode.OFF -> "Angka: off"
        }

        val sb = SpannableStringBuilder()
        val header = "Do = ${Notes.keyName(target)}" + (if (song.transpose != 0) "   (asli: ${Notes.keyName(song.key)})" else "") +
            (if (!keyExplicit) "   · kunci terdeteksi otomatis" else "")
        sb.append(header, ForegroundColorSpan(C.MUTED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n\n")

        val rendered = Renderer.render(lines, song.key, song.transpose, mode())
        if (rendered.none { it.spans.any { s -> s.kind == Span.Kind.CHORD } }) {
            sb.append("(Tidak ada chord yang terbaca di teks ini.)\n\n", ForegroundColorSpan(C.MUTED), 0)
        }
        for (rl in rendered) {
            val base = sb.length
            sb.append(rl.text)
            for (s in rl.spans) {
                val a = base + s.start
                val b = base + s.end
                when (s.kind) {
                    Span.Kind.CHORD -> {
                        sb.setSpan(ForegroundColorSpan(C.CHORD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    Span.Kind.NUMBER -> {
                        sb.setSpan(ForegroundColorSpan(C.NUMBER), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (mode() == NumberMode.INLINE) sb.setSpan(RelativeSizeSpan(0.85f), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    Span.Kind.SECTION -> {
                        sb.setSpan(ForegroundColorSpan(C.SECTION), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    Span.Kind.LABEL -> sb.setSpan(ForegroundColorSpan(C.MUTED), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            sb.append('\n')
        }
        body.text = sb
    }

    // ---------------------------------------------------------- Aksi

    private fun transpose(d: Int) {
        var t = song.transpose + d
        if (t > 11) t -= 12
        if (t < -11) t += 12
        song.transpose = t
        persistIfSaved()
        render()
    }

    private fun cycleNumberMode() {
        val next = when (mode()) {
            NumberMode.INLINE -> NumberMode.BELOW
            NumberMode.BELOW -> NumberMode.OFF
            NumberMode.OFF -> NumberMode.INLINE
        }
        Prefs.setNumberMode(this, next.name)
        render()
    }

    private fun font(d: Float) {
        val v = (Prefs.fontSize(this) + d).coerceIn(9f, 30f)
        Prefs.setFontSize(this, v)
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, v)
    }

    private fun setScrolling(on: Boolean) {
        scrolling = on
        handler.removeCallbacks(scroller)
        if (on) handler.post(scroller)
        playBtn.text = if (on) "❚❚ Jeda" else "▶ Scroll"
        playBtn.setPill(on)
    }

    private fun updateSpeed() { speedBtn.text = "${speedIdx + 1}x" }

    private fun save() {
        saved = true
        SongStore.upsert(this, song)
        updateTop()
        Toast.makeText(this, "Disimpan ke Koleksi", Toast.LENGTH_SHORT).show()
    }

    private fun keyDialog() {
        val names = Array(12) { i ->
            val n = Notes.keyName(i)
            if (i == song.key) "$n   (sekarang)" else n
        }
        AlertDialog.Builder(this)
            .setTitle("Nada dasar asli lagu")
            .setItems(names) { _, which ->
                song.key = which
                keyExplicit = true
                persistIfSaved()
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
        Toast.makeText(this, "Pilih kunci asli kalau deteksi otomatis salah. Untuk pindah nada, pakai − / +", Toast.LENGTH_LONG).show()
    }

    private fun renameDialog() {
        val input = EditText(this).apply {
            setText(song.title)
            setSingleLine()
            setTextColor(C.TEXT)
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, lp(MATCH, WRAP)) }
        AlertDialog.Builder(this)
            .setTitle("Ganti judul")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                song.title = input.text.toString().ifBlank { song.title }
                persistIfSaved()
                updateTop()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun editTextDialog() {
        val input = EditText(this).apply {
            setText(song.raw)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(C.TEXT)
            gravity = Gravity.TOP
            minLines = 10
            maxLines = 18
            setHorizontallyScrolling(true)
        }
        val box = LinearLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0); addView(input, lp(MATCH, WRAP)) }
        AlertDialog.Builder(this)
            .setTitle("Edit chord")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                song.raw = input.text.toString()
                song.key = -1
                persistIfSaved()
                reparse()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun menu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        if (song.source.isNotBlank()) pm.menu.add(0, 1, 0, "Buka halaman sumber")
        pm.menu.add(0, 2, 1, "Edit teks chord")
        pm.menu.add(0, 3, 2, "Ganti judul")
        pm.menu.add(0, 5, 3, "Bagikan teks")
        if (saved) pm.menu.add(0, 4, 4, "Hapus dari koleksi")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(song.source)))
                2 -> editTextDialog()
                3 -> renameDialog()
                5 -> share()
                4 -> AlertDialog.Builder(this)
                    .setMessage("Hapus \"${song.title}\" dari koleksi?")
                    .setPositiveButton("Hapus") { _, _ -> SongStore.delete(this, song.id); finish() }
                    .setNegativeButton("Batal", null).show()
            }
            true
        }
        pm.show()
    }

    private fun share() {
        val text = Renderer.render(lines, song.key, song.transpose, mode()).joinToString("\n") { it.text }
        val full = "${song.title}\nDo = ${Notes.keyName(song.key + song.transpose)}\n\n$text"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, full)
        }, "Bagikan chord"))
    }
}
