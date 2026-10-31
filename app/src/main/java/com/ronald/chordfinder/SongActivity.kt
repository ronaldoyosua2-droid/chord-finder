package com.ronald.chordfinder

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ronald.chordfinder.music.KeyDetector
import com.ronald.chordfinder.music.Line
import com.ronald.chordfinder.music.Modulation
import com.ronald.chordfinder.music.Note
import com.ronald.chordfinder.music.Notes
import com.ronald.chordfinder.music.NumberMode
import com.ronald.chordfinder.music.RenderedLine
import com.ronald.chordfinder.music.Renderer
import com.ronald.chordfinder.music.SongParser
import com.ronald.chordfinder.music.Span

class SongActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_QUERY = "query"
        const val EXTRA_BPM = "bpm"
        const val EXTRA_SIG = "sig"
        const val EXTRA_SETLIST = "setlist"
        const val EXTRA_INDEX = "index"
        private const val REQ_MIC = 7
        private val SPEEDS = intArrayOf(1, 2, 3, 4, 6)
    }

    private lateinit var song: Song

    // Daftar ibadah (kalau dibuka dari daftar): kunci disimpan per daftar
    private var setlist: Setlist? = null
    private var setIndex = -1
    private val setItem get() = setlist?.items?.getOrNull(setIndex)?.takeIf { it.songId == song.id }
    private var tr: Int
        get() = setItem?.transpose ?: song.transpose
        set(v) {
            val it = setItem
            if (it != null) { it.transpose = v; SetlistStore.persist(this) } else song.transpose = v
        }

    // Sinkron metronom ke video
    private var vTime = 0.0
    private var vWall = 0L
    private var vState = -1
    private var tapping = false
    private val tapTimes = ArrayList<Double>()
    private lateinit var tapPanel: LinearLayout
    private lateinit var tapCount: TextView
    private lateinit var tapResult: TextView
    private lateinit var syncBtn: TextView
    private lateinit var nudgeBox: LinearLayout
    private var saved = false
    private var lines: List<Line> = emptyList()
    private var keyExplicit = false
    private var rendered: List<RenderedLine> = emptyList()
    private var renderedOffset = 0
    private var suggestion: Modulation.Suggestion? = null

    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var starView: TextView
    private lateinit var body: TextView
    private lateinit var keyBtn: TextView
    private lateinit var numBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var ytBtn: TextView
    private lateinit var scroll: ScrollView

    // YouTube
    private lateinit var ytCard: LinearLayout
    private lateinit var ytThumb: ImageView
    private lateinit var ytLabel: TextView
    private lateinit var ytTitle: TextView
    private lateinit var ytSub: TextView
    private lateinit var playerBox: LinearLayout
    private var player: WebView? = null
    private var playing = false
    private var searching = false
    private var results: List<Video>? = null

    // Modulasi
    private lateinit var modBanner: LinearLayout
    private lateinit var modText: TextView

    // Catatan & metronom
    private lateinit var songNoteView: TextView
    private lateinit var metro: MetroUi
    private var micGranted: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scrolling = false
    private var speedIdx = 1
    private var lastTouchY = 0f
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

        setlist = intent.getStringExtra(EXTRA_SETLIST)?.let { SetlistStore.get(this, it) }
        setIndex = intent.getIntExtra(EXTRA_INDEX, -1)
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
                query = intent.getStringExtra(EXTRA_QUERY) ?: "",
            )
        }
        metro = MetroUi(this, object : MetroHost {
            override val song get() = this@SongActivity.song
            override val lines get() = this@SongActivity.lines
            override val manualMods get() = this@SongActivity.song.mods
            override fun persist() = persistIfSaved()
            override fun scrollToLine(src: Int) = this@SongActivity.scrollToLine(src)
            override fun requestMic(granted: () -> Unit) = this@SongActivity.requestMic(granted)
        })
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi().apply { fitSystemBars() })
        reparse()
        metro.initFrom(intent.getIntExtra(EXTRA_BPM, 0), intent.getStringExtra(EXTRA_SIG) ?: "")
        updateSongNote()
        updateYtCard()
        if (song.ytId.isEmpty()) autoSearch()
    }

    override fun onPause() {
        super.onPause()
        setScrolling(false)
        player?.evaluateJavascript("pause()", null)
    }

    override fun onDestroy() {
        metro.release()
        player?.destroy()
        super.onDestroy()
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

    @SuppressLint("ClickableViewAccessibility")
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
        buildSetNav()?.let { root.addView(it) }

        root.addView(buildYtCard())
        root.addView(buildPlayer())
        root.addView(metro.buildBeatBar())
        root.addView(buildModBanner())
        songNoteView = label("", 13.5f, C.TEXT).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0x148FC7FF, dpf(12f), dp(1), 0x558FC7FF)
            visibility = View.GONE
            setOnClickListener { songNoteDialog() }
            layoutParams = lp(MATCH, WRAP).apply { setMargins(dp(12), 0, dp(12), dp(8)) }
        }
        root.addView(songNoteView)

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
        body.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) lastTouchY = e.y
            false
        }
        body.setOnClickListener { if (scrolling) setScrolling(false) }
        body.setOnLongClickListener { lineMenu(); true }
        val chordFrame = FrameLayout(this)
        chordFrame.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        chordFrame.addView(metro.buildOverlay(), FrameLayout.LayoutParams(MATCH, MATCH))
        chordFrame.addView(buildTapPanel(), FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(chordFrame, lp(MATCH, 0, 1f))

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

        val row2Scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setPadding(0, dp(8), 0, 0)
        }
        val row2 = hbox()
        row2.addView(small(pill("A−") { font(-1f) }).apply { setPadding(dp(9), dp(8), dp(9), dp(8)); minWidth = 0 })
        row2.addView(small(pill("A+") { font(1f) }).apply { setPadding(dp(9), dp(8), dp(9), dp(8)); minWidth = 0 },
            lp(WRAP, WRAP).apply { leftMargin = dp(4) })
        ytBtn = small(pill("▶ YouTube") { ytButton() })
        row2.addView(ytBtn, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        playBtn = small(pill("▶ Scroll") { setScrolling(!scrolling) })
        playBtn.setOnLongClickListener {
            speedIdx = (speedIdx + 1) % SPEEDS.size
            Toast.makeText(this, "Kecepatan scroll: ${speedIdx + 1}x", Toast.LENGTH_SHORT).show()
            true
        }
        row2.addView(metro.buildButton(), lp(WRAP, WRAP).apply { leftMargin = dp(5) })
        row2.addView(playBtn, lp(WRAP, WRAP).apply { leftMargin = dp(5) })
        row2.addView(View(this), lp(0, 1, 1f))
        saveBtn = small(pill("Simpan") { if (!saved) save() })
        row2.addView(saveBtn, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        row2Scroll.addView(row2, ViewGroup.LayoutParams(MATCH, WRAP))
        panel.addView(row2Scroll)
        root.addView(panel)

        updateTop()
        updateYtButton()
        return root
    }

    private fun small(t: TextView): TextView = t.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
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
        small(saveBtn)
    }

    private fun mode(): NumberMode = NumberMode.valueOf(Prefs.numberMode(this))

    private fun render() {
        val target = Notes.mod12(song.key + tr)
        val shift = when {
            tr == 0 -> ""
            tr > 0 -> "  (+${tr})"
            else -> "  (${tr})"
        }
        keyBtn.text = "Do = " + Notes.keyName(target) + shift
        numBtn.text = when (mode()) {
            NumberMode.INLINE -> "Angka: Bm(2)"
            NumberMode.BELOW -> "Angka: bawah"
            NumberMode.OFF -> "Angka: off"
        }

        val sb = SpannableStringBuilder()
        val hasMod = lines.indices.any { Modulation.isChangeAt(lines, song.mods, it) }
        val header = "Do = ${Notes.keyName(target)}" + (if (tr != 0) "   (asli: ${Notes.keyName(song.key)})" else "") +
            (if (!keyExplicit) "   · kunci terdeteksi otomatis" else "") +
            (if (hasMod) "   · ada modulasi" else "")
        sb.append(header, ForegroundColorSpan(C.MUTED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n\n")
        renderedOffset = 2

        rendered = Renderer.render(lines, song.key, tr, mode(), song.mods, song.notes)
        if (rendered.none { it.spans.any { s -> s.kind == Span.Kind.CHORD } }) {
            sb.append("(Tidak ada chord yang terbaca di teks ini.)\n\n", ForegroundColorSpan(C.MUTED), 0)
            renderedOffset += 2
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
                    Span.Kind.MODULATION -> {
                        sb.setSpan(ForegroundColorSpan(C.MOD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    Span.Kind.LABEL -> sb.setSpan(ForegroundColorSpan(C.MUTED), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    Span.Kind.NOTE -> {
                        sb.setSpan(ForegroundColorSpan(C.NOTE), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            sb.append('\n')
        }
        body.text = sb
        updateSuggestion()
    }

    // -------------------------------------------------------- Modulasi

    private fun buildModBanner(): View {
        modBanner = hbox().apply {
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = rounded(C.SURFACE2, dpf(14f), dp(1), C.MOD)
            visibility = View.GONE
        }
        modText = label("", 13.5f, C.TEXT)
        modBanner.addView(modText, lp(0, WRAP, 1f))
        modBanner.addView(small(pill("Pakai") { acceptSuggestion() }).apply { setPill(true); small(this) },
            lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        modBanner.addView(small(pill("Abaikan") { dismissSuggestion() }),
            lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        modBanner.layoutParams = lp(MATCH, WRAP).apply { setMargins(dp(12), 0, dp(12), dp(8)) }
        return modBanner
    }

    private fun updateSuggestion() {
        val keys = Modulation.keys(lines, song.key, song.mods)
        suggestion = Modulation.suggest(lines, keys, song.mods, song.modDismissed)
        val s = suggestion
        if (s == null) { modBanner.visibility = View.GONE; return }
        modText.text = "Sepertinya \"${s.sectionName}\" naik ke Do = ${Notes.keyName(s.newKey + tr)}. Pakai?"
        modBanner.visibility = View.VISIBLE
    }

    private fun acceptSuggestion() {
        val s = suggestion ?: return
        song.mods[s.lineIndex] = s.newKey
        persistIfSaved()
        render()
    }

    private fun dismissSuggestion() {
        val s = suggestion ?: return
        song.modDismissed.add(s.lineIndex)
        persistIfSaved()
        render()
    }

    /** Baris yang ditekan lama: indeks di [rendered], atau null. */
    private fun touchedLine(): RenderedLine? {
        val layout = body.layout ?: return null
        val line = layout.getLineForVertical((lastTouchY - body.totalPaddingTop).toInt())
        return rendered.getOrNull(line - renderedOffset)
    }

    /** Tekan lama pada baris: jembatan chord, catatan, atau modulasi. */
    private fun lineMenu() {
        val r = touchedLine() ?: return
        val src = r.src
        if (src < 0) return
        if (r.note >= 0) {
            val note = song.notes[src]?.getOrNull(r.note) ?: return
            AlertDialog.Builder(this)
                .setTitle(if (note.bridge) "Jembatan chord" else "Catatan")
                .setItems(arrayOf("✎ Edit", "✕ Hapus")) { _, w ->
                    if (w == 0) {
                        if (note.bridge) bridgeDialog(src, r.note) else noteDialog(src, r.note)
                    } else {
                        song.notes[src]?.removeAt(r.note)
                        if (song.notes[src].isNullOrEmpty()) song.notes.remove(src)
                        persistIfSaved(); render()
                    }
                }.show()
            return
        }
        val preview = r.text.trim().take(40)
        // Catatan di baris chord ditaruh sesudah liriknya (chord + lirik = satu kesatuan)
        val target = if (lines.getOrNull(src) is Line.Chords && lines.getOrNull(src + 1) is Line.Lyric) src + 1 else src
        AlertDialog.Builder(this)
            .setTitle(preview.ifEmpty { "Baris ini" })
            .setItems(arrayOf(
                "✎  Tambah jembatan chord\n     chord penghubung sesudah baris ini",
                "📝  Tambah catatan\n     mis. \"intro 2x\", \"masuk drum\"",
                "⤴  Modulasi mulai di sini\n     pilih Do baru",
            )) { _, w ->
                when (w) {
                    0 -> bridgeDialog(target, -1)
                    1 -> noteDialog(target, -1)
                    2 -> modulationDialog(src)
                }
            }.show()
    }

    private fun modulationDialog(src: Int) {
        val keys = Modulation.keys(lines, song.key, song.mods)
        val cur = keys[src]
        val hasChange = Modulation.isChangeAt(lines, song.mods, src)
        val items = ArrayList<String>()
        for (i in 0 until 12) {
            val k = Notes.mod12(song.key + i)
            val name = "Do = " + Notes.keyName(k + tr)
            items.add(if (k == cur && hasChange) "$name   (sekarang)" else name)
        }
        if (hasChange) items.add("✕ Hapus modulasi di sini")
        AlertDialog.Builder(this)
            .setTitle("Modulasi mulai di baris ini")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 12) {
                    if (song.mods.containsKey(src) && lines[src] !is Line.KeyChange) song.mods.remove(src)
                    else song.mods[src] = -1
                } else {
                    song.mods[src] = Notes.mod12(song.key + which)
                }
                song.seq.clear()
                persistIfSaved()
                render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Do (kunci asli) yang berlaku di baris [src]. */
    private fun keyAt(src: Int) = Modulation.keys(lines, song.key, song.mods).getOrElse(src) { song.key }

    private fun bridgeDialog(src: Int, editIndex: Int) {
        val key = keyAt(src)
        val existing = if (editIndex >= 0) song.notes[src]?.getOrNull(editIndex)?.text.orEmpty() else ""
        val box = vbox().apply { setPadding(dp(18), dp(6), dp(18), 0) }
        val after = listOfNotNull(
            rendered.firstOrNull { it.src == src - 1 && it.note < 0 && lines.getOrNull(src - 1) is Line.Chords }?.text?.trim(),
            rendered.firstOrNull { it.src == src && it.note < 0 }?.text?.trim(),
        ).joinToString("  /  ").take(48)
        box.addView(label("Sesudah: $after",
            12f, C.MUTED).apply { typeface = Typeface.MONOSPACE })
        val input = EditText(this).apply {
            setText(existing)
            hint = "mis. G/B C#dim"
            setSingleLine()
            typeface = Typeface.MONOSPACE
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
            background = rounded(C.BG, dpf(12f), dp(1), C.NOTE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(input, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(label("Cepat ketik:", 12f, C.MUTED).apply { setPadding(0, dp(8), 0, dp(4)) })
        // Chord bawaan Do yang berlaku + chord penghubung yang sering dipakai (ditulis sesuai transpose)
        val shown = Notes.mod12(key + tr)
        val flats = Notes.useFlats(shown)
        fun n(semi: Int) = Notes.name(shown + semi, flats)
        val quick = listOf(n(0), n(2) + "m", n(4) + "m", n(5), n(7), n(9) + "m", n(7) + "/" + n(11),
            n(4) + "/" + n(8), Notes.name(shown + 1, false) + "dim", n(10), "→")
        val chipRow = hbox()
        val preview = label("", 13.5f, C.TEXT).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(C.BG, dpf(12f))
        }
        fun updatePreview() {
            // Teks diketik dalam kunci yang tampil (sudah ditranspose) -> simpan dalam kunci asli
            val rl = Renderer.renderNote(Note(true, untranspose(input.text.toString())), key, tr, mode())
            val sb = SpannableStringBuilder(rl.text)
            for (sp in rl.spans) {
                val col = when (sp.kind) { Span.Kind.CHORD -> C.CHORD; Span.Kind.NUMBER -> C.NUMBER; Span.Kind.NOTE -> C.NOTE; else -> C.MUTED }
                sb.setSpan(ForegroundColorSpan(col), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            preview.text = sb
        }
        for (q in quick) {
            chipRow.addView(small(pill(q) {
                val t = input.text.toString().trimEnd()
                input.setText(if (t.isEmpty()) q else "$t $q")
                input.setSelection(input.text.length)
            }), lp(WRAP, WRAP).apply { rightMargin = dp(5) })
        }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) })
        box.addView(label("Pratinjau:", 12f, C.MUTED).apply { setPadding(0, dp(8), 0, dp(4)) })
        box.addView(preview, lp(MATCH, WRAP))
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) = updatePreview()
        })
        updatePreview()
        AlertDialog.Builder(this)
            .setTitle("Jembatan chord")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val t = untranspose(input.text.toString().trim())
                if (t.isBlank()) return@setPositiveButton
                val list = song.notes.getOrPut(src) { ArrayList() }
                if (editIndex >= 0 && editIndex < list.size) list[editIndex] = Note(true, t) else list.add(Note(true, t))
                persistIfSaved(); render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Chord yang diketik sesuai tampilan (sudah ditranspose) dikembalikan ke kunci asli lagu. */
    private fun untranspose(text: String): String {
        if (tr == 0) return text
        return text.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { tok ->
            val c = com.ronald.chordfinder.music.Chord.parse(tok)
            c?.transpose(-tr)?.format(Notes.useFlats(song.key)) ?: tok
        }
    }

    private fun noteDialog(src: Int, editIndex: Int) {
        val existing = if (editIndex >= 0) song.notes[src]?.getOrNull(editIndex)?.text.orEmpty() else ""
        val input = EditText(this).apply {
            setText(existing)
            hint = "mis. masuk drum, naik dinamika"
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
        }
        AlertDialog.Builder(this)
            .setTitle("Catatan")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, lp(MATCH, WRAP)) })
            .setPositiveButton("Simpan") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isBlank()) return@setPositiveButton
                val list = song.notes.getOrPut(src) { ArrayList() }
                if (editIndex >= 0 && editIndex < list.size) list[editIndex] = Note(false, t) else list.add(Note(false, t))
                persistIfSaved(); render()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun updateSongNote() {
        if (song.songNote.isBlank()) { songNoteView.visibility = View.GONE; return }
        val sb = SpannableStringBuilder()
        sb.append("📝 CATATAN LAGU\n", ForegroundColorSpan(C.NOTE), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(song.songNote)
        songNoteView.text = sb
        songNoteView.visibility = View.VISIBLE
    }

    private fun songNoteDialog() {
        val input = EditText(this).apply {
            setText(song.songNote)
            hint = "mis. Intro 2x · Reff terakhir ulang 2x, pelan"
            minLines = 2
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
        }
        AlertDialog.Builder(this)
            .setTitle("Catatan lagu")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, lp(MATCH, WRAP)) })
            .setPositiveButton("Simpan") { _, _ ->
                song.songNote = input.text.toString().trim()
                persistIfSaved(); updateSongNote()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Gulir ke baris lagu [src] (dipakai metronom saat pindah bagian). */
    private fun scrollToLine(src: Int) {
        val idx = rendered.indexOfFirst { it.src >= src }
        val layout = body.layout ?: return
        if (idx < 0) return
        val line = (renderedOffset + idx).coerceAtMost(layout.lineCount - 1)
        val y = layout.getLineTop(line) + body.totalPaddingTop - dp(6)
        scroll.smoothScrollTo(0, y.coerceAtLeast(0))
    }

    private fun requestMic(granted: () -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            granted(); return
        }
        micGranted = granted
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) micGranted?.invoke()
        else Toast.makeText(this, "Izin mikrofon dibutuhkan untuk deteksi tempo. Bisa pakai Tap tempo.", Toast.LENGTH_LONG).show()
        micGranted = null
    }

    // --------------------------------------------------- Daftar ibadah

    private fun buildSetNav(): View? {
        val sl = setlist ?: return null
        if (setItem == null) return null
        val bar = hbox().apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(C.SURFACE2, dpf(14f), dp(1), C.LINE)
            layoutParams = lp(MATCH, WRAP).apply { setMargins(dp(12), 0, dp(12), dp(8)) }
        }
        val n = sl.items.size
        val prev = small(pill(if (setIndex > 0) "‹ $setIndex" else "‹") { goTo(setIndex - 1) })
        prev.isEnabled = setIndex > 0; prev.alpha = if (setIndex > 0) 1f else 0.35f
        bar.addView(prev)
        val mid = vbox().apply { gravity = Gravity.CENTER; setOnClickListener { finish() } }
        mid.addView(label("📅 ${Dates.short(sl.date)} · lagu ${setIndex + 1}/$n", 13.5f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER })
        val nextSong = sl.items.getOrNull(setIndex + 1)?.let { SongStore.get(this, it.songId) }
        mid.addView(label(if (nextSong != null) "berikutnya: ${nextSong.title}" else "lagu terakhir", 11.5f, C.MUTED).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        bar.addView(mid, lp(0, WRAP, 1f))
        val next = small(pill(if (setIndex + 1 < n) "${setIndex + 2} ›" else "›") { goTo(setIndex + 1) })
        next.isEnabled = setIndex + 1 < n; next.alpha = if (setIndex + 1 < n) 1f else 0.35f
        bar.addView(next)
        return bar
    }

    private fun goTo(i: Int) {
        val sl = setlist ?: return
        val item = sl.items.getOrNull(i) ?: return
        metro.release()
        startActivity(Intent(this, SongActivity::class.java)
            .putExtra(EXTRA_ID, item.songId).putExtra(EXTRA_SETLIST, sl.id).putExtra(EXTRA_INDEX, i))
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        finish()
    }

    /** Centang tanggal mana saja lagu ini dimainkan; tampilkan riwayat. */
    private fun setlistSheet() {
        if (!saved) save()
        val (up, past) = SetlistStore.sorted(this)
        val choices = up + past.take(3)
        val box = vbox().apply { setPadding(dp(18), dp(4), dp(18), 0) }
        box.addView(label("Lagu bisa masuk ke beberapa tanggal sekaligus", 12.5f, C.MUTED).apply { setPadding(0, 0, 0, dp(8)) })
        lateinit var dlg: AlertDialog
        fun row(sl: Setlist): View {
            val r = hbox().apply { setPadding(dp(4), dp(10), dp(4), dp(10)); isClickable = true }
            val check = label("", 14f, C.BG, bold = true).apply { gravity = Gravity.CENTER }
            fun paint() {
                val on = sl.items.any { it.songId == song.id }
                check.text = if (on) "✓" else ""
                check.background = if (on) rounded(C.CHORD, dpf(6f)) else rounded(0, dpf(6f), dp(2), C.LINE)
            }
            paint()
            r.addView(check, lp(dp(22), dp(22)))
            val t = vbox().apply { setPadding(dp(12), 0, 0, 0) }
            t.addView(label(Dates.pretty(sl.date), 14.5f, C.TEXT))
            t.addView(label("${sl.name} · ${sl.items.size} lagu", 12f, C.MUTED))
            r.addView(t, lp(0, WRAP, 1f))
            r.setOnClickListener {
                if (sl.items.any { it.songId == song.id }) sl.items.removeAll { it.songId == song.id }
                else sl.items.add(SetItem(song.id))
                SetlistStore.persist(this); paint()
            }
            return r
        }
        choices.forEach { box.addView(row(it)) }
        box.addView(hbox().apply {
            setPadding(dp(4), dp(10), dp(4), dp(10)); isClickable = true
            addView(label("+", 15f, C.TEXT, bold = true).apply {
                gravity = Gravity.CENTER; background = rounded(0, dpf(6f), dp(2), C.LINE)
            }, lp(dp(22), dp(22)))
            addView(vbox().apply {
                setPadding(dp(12), 0, 0, 0)
                addView(label("Daftar baru…", 14.5f, C.TEXT)); addView(label("pilih tanggal", 12f, C.MUTED))
            })
            setOnClickListener {
                dlg.dismiss()
                SetlistDialogs.create(this@SongActivity, song.id) {
                    Toast.makeText(this@SongActivity, "Masuk ke ${Dates.pretty(it.date)}", Toast.LENGTH_SHORT).show()
                }
            }
        })
        val played = SetlistStore.containing(this, song.id).filter { Dates.daysFromToday(it.date) < 0 }.sortedByDescending { it.date }
        if (played.isNotEmpty()) {
            box.addView(label("PERNAH DIMAINKAN\n" + played.take(6).joinToString(" · ") { Dates.pretty(it.date).substringAfter(", ") },
                13f, 0xFFC9C6BF.toInt()).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(C.BG, dpf(12f))
                layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(8) }
            })
        }
        dlg = AlertDialog.Builder(this).setTitle("Simpan ke daftar ibadah")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Selesai", null).show()
    }

    // ------------------------------------------------ Sinkron ke video

    private fun videoNow(): Double =
        if (vState == 1) vTime + (android.os.SystemClock.elapsedRealtime() - vWall) / 1000.0 else vTime

    /** Dipanggil tiap ~200 ms dari pemutar: metronom ikut play/pause/geser video. */
    private fun onVideoTick() {
        if (tapping) return
        if (vState != 1) {
            metro.userStopped = false
            if (metro.syncRunning) metro.stop(byUser = false)
            return
        }
        if (!song.hasSync || !song.syncOn || metro.userStopped) return
        if (!metro.syncRunning) { metro.startSynced(videoNow()); return }
        val drift = (videoNow() - song.syncOffset) - metro.syncPosition()
        // Video digeser / tersendat: mulai ulang di posisi yang benar
        if (Math.abs(drift) > 0.25) metro.startSynced(videoNow())
    }

    private fun updateSyncTools() {
        if (!this::syncBtn.isInitialized) return
        if (song.hasSync) {
            syncBtn.text = if (song.syncOn) "♩ Ikut video: ON" else "♩ Ikut video: OFF"
            syncBtn.background = if (song.syncOn) rounded(C.GO, dpf(18f)) else rounded(C.SURFACE2, dpf(18f), dp(1), C.LINE)
            syncBtn.setTextColor(if (song.syncOn) 0xFF0B2A14.toInt() else C.TEXT)
            nudgeBox.visibility = if (song.syncOn) View.VISIBLE else View.GONE
        } else {
            syncBtn.text = "♩ Sinkron"
            syncBtn.background = rounded(C.SURFACE2, dpf(18f), dp(1), C.GO)
            syncBtn.setTextColor(C.GO)
            nudgeBox.visibility = View.GONE
        }
        small(syncBtn)
    }

    private fun syncButton() {
        if (!song.hasSync) { startTap(); return }
        song.syncOn = !song.syncOn
        persistIfSaved()
        if (!song.syncOn && metro.syncRunning) metro.stop(byUser = false)
        updateSyncTools()
        Toast.makeText(this, if (song.syncOn) "Metronom ikut video. Tahan tombol ini untuk tandai ulang." else "Metronom tidak ikut video",
            Toast.LENGTH_SHORT).show()
    }

    private fun nudge(d: Double) {
        if (!song.hasSync) return
        song.syncOffset = (song.syncOffset + d).coerceAtLeast(0.0)
        persistIfSaved()
        if (metro.syncRunning) metro.startSynced(videoNow())
        Toast.makeText(this, if (d < 0) "Metronom dimajukan 20 ms" else "Metronom dimundurkan 20 ms", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTapPanel(): View {
        tapPanel = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            setBackgroundColor(0xF20E0E13.toInt())
            visibility = View.GONE
            isClickable = true
        }
        tapPanel.addView(label("♩ Sinkron metronom ke video", 17f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER })
        tapPanel.addView(label("Ketuk lingkaran setiap ketukan, mulai tepat di ketukan 1 intro. Minimal 4 ketukan, makin banyak makin pas.",
            13f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(12)) })
        val size = (resources.displayMetrics.widthPixels * 0.5).toInt().coerceAtMost(dp(230))
        val pad = vbox().apply {
            gravity = Gravity.CENTER
            background = rounded(0x227BE495, size / 2f, dp(3), C.GO)
        }
        tapCount = label("0", 52f, C.GO, bold = true).apply { gravity = Gravity.CENTER }
        pad.addView(tapCount)
        pad.addView(label("ketukan · ketuk di sini", 12.5f, C.MUTED).apply { gravity = Gravity.CENTER })
        pad.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                tapTimes.add(videoNow())
                v.alpha = 0.6f
                updateTap()
            } else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) v.alpha = 1f
            true
        }
        tapPanel.addView(pad, lp(size, size))
        tapResult = label("", 14f, C.TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)) }
        tapPanel.addView(tapResult)
        val row = hbox(Gravity.CENTER)
        row.addView(small(pill("Batal") { endTap(false) }))
        row.addView(small(pill("↺ Ulang") { tapTimes.clear(); updateTap() }), lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        row.addView(label("✓ Simpan sinkron", 14.5f, 0xFF0B2A14.toInt(), bold = true).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(C.GO, dpf(18f))
            isClickable = true
            setOnClickListener { endTap(true) }
        }, lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        tapPanel.addView(row)
        return tapPanel
    }

    private fun fmtTime(t: Double): String {
        val m = (t / 60).toInt(); val sec = t - m * 60
        return "%d:%04.1f".format(m, sec)
    }

    private fun updateTap() {
        tapCount.text = "${tapTimes.size}"
        val r = com.ronald.chordfinder.music.TapSync.fit(tapTimes)
        tapResult.text = if (r == null) "Tempo & posisi ketukan 1 muncul setelah 4 ketukan"
        else "%.1f BPM  ·  bar 1 mulai di %s".format(r.bpm, fmtTime(r.offset))
    }

    private fun startTap() {
        if (song.ytId.isEmpty()) { pickerDialog(); return }
        if (playerBox.visibility != View.VISIBLE || vState != 1) {
            if (playerBox.visibility != View.VISIBLE) openPlayer()
            Toast.makeText(this, "Putar videonya, lalu tekan ♩ Sinkron lagi saat lagu mulai.", Toast.LENGTH_LONG).show()
            return
        }
        if (metro.syncRunning || metro.isRunning) metro.stop(byUser = false)
        tapping = true
        tapTimes.clear()
        updateTap()
        tapPanel.visibility = View.VISIBLE
    }

    private fun endTap(save: Boolean) {
        val r = com.ronald.chordfinder.music.TapSync.fit(tapTimes)
        if (save && r == null) {
            Toast.makeText(this, "Ketuk minimal 4 ketukan dulu", Toast.LENGTH_SHORT).show(); return
        }
        tapping = false
        tapPanel.visibility = View.GONE
        if (!save || r == null) return
        song.syncBpm = r.bpm
        song.syncOffset = r.offset.coerceAtLeast(0.0)
        song.syncVideo = song.ytId
        song.syncOn = true
        song.bpm = Math.round(r.bpm).toInt()
        if (!saved) save() else persistIfSaved()
        metro.userStopped = false
        updateSyncTools()
        metro.refreshButton()
        Toast.makeText(this, "Tersimpan. Metronom akan ikut video ini.", Toast.LENGTH_SHORT).show()
    }

    // --------------------------------------------------------- YouTube

    private fun buildYtCard(): View {
        ytCard = hbox().apply {
            setPadding(dp(8), dp(8), dp(10), dp(8))
            background = rounded(C.SURFACE2, dpf(14f), dp(1), C.LINE)
            isClickable = true
            setOnClickListener { if (song.ytId.isNotEmpty()) openPlayer() else pickerDialog() }
            setOnLongClickListener { pickerDialog(); true }
        }
        ytThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(C.LINE, dpf(8f))
            clipToOutline = true
        }
        ytCard.addView(ytThumb, lp(dp(104), dp(58)))
        val txt = vbox().apply { setPadding(dp(10), 0, 0, 0) }
        ytLabel = label("", 11f, C.YT, bold = true)
        ytTitle = label("", 13.5f, C.TEXT, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        ytSub = label("", 12f, C.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        txt.addView(ytLabel); txt.addView(ytTitle); txt.addView(ytSub)
        ytCard.addView(txt, lp(0, WRAP, 1f))
        ytCard.layoutParams = lp(MATCH, WRAP).apply { setMargins(dp(12), 0, dp(12), dp(8)) }
        return ytCard
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildPlayer(): View {
        playerBox = vbox().apply { visibility = View.GONE }
        val h = (resources.displayMetrics.widthPixels * 9 / 16).coerceAtMost(dp(240))
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        playerBox.addView(frame, lp(MATCH, h))
        val tools = hbox().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        tools.addView(small(pill("⟲ 10 dtk") { player?.evaluateJavascript("back10()", null) }))
        tools.addView(small(pill("Ganti video") { pickerDialog() }), lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        syncBtn = small(pill("♩ Sinkron") { syncButton() })
        syncBtn.setOnLongClickListener { startTap(); true }
        tools.addView(syncBtn, lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        nudgeBox = hbox()
        nudgeBox.addView(small(pill("◂ 20ms") { nudge(-0.02) }), lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        nudgeBox.addView(small(pill("20ms ▸") { nudge(0.02) }), lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        tools.addView(nudgeBox)
        tools.addView(small(pill("YouTube ↗") { openInYouTube() }), lp(WRAP, WRAP).apply { leftMargin = dp(6) })
        tools.addView(View(this), lp(0, 1, 1f))
        tools.addView(small(pill("✕") { closePlayer() }))
        val toolsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true }
        toolsScroll.addView(tools, ViewGroup.LayoutParams(MATCH, WRAP))
        playerBox.addView(toolsScroll)
        playerBox.tag = frame
        return playerBox
    }

    private fun updateYtCard() {
        val playerOpen = playerBox.visibility == View.VISIBLE
        when {
            playerOpen -> ytCard.visibility = View.GONE
            song.ytId.isNotEmpty() -> {
                ytCard.visibility = View.VISIBLE
                ytLabel.text = "▶ VIDEO YOUTUBE"
                ytTitle.text = song.ytTitle.ifEmpty { "Video YouTube" }
                ytSub.text = listOf(song.ytChannel, "ketuk untuk putar").filter { it.isNotEmpty() }.joinToString(" · ")
                YouTube.loadThumb(song.ytId, ytThumb)
            }
            searching -> {
                ytCard.visibility = View.VISIBLE
                ytLabel.text = "YOUTUBE"
                ytTitle.text = "Mencari video…"
                ytSub.text = ytQuery()
                ytThumb.setImageDrawable(null)
            }
            results != null -> {
                ytCard.visibility = View.VISIBLE
                ytLabel.text = "YOUTUBE"
                ytTitle.text = "Video tidak ditemukan"
                ytSub.text = "Ketuk untuk cari dengan kata lain"
                ytThumb.setImageDrawable(null)
            }
            else -> ytCard.visibility = View.GONE
        }
    }

    private fun updateYtButton() {
        ytBtn.text = if (playing) "❚❚ YouTube" else "▶ YouTube"
        ytBtn.background = rounded(C.YT, dpf(18f))
        ytBtn.setTextColor(0xFFFFFFFF.toInt())
        ytBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun ytQuery(): String {
        val base = song.query.ifBlank { song.title }
        return base.replace(Regex("[()\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun autoSearch() {
        searching = true
        updateYtCard()
        YouTube.search(ytQuery()) { list ->
            searching = false
            results = list ?: emptyList()
            val first = list?.firstOrNull()
            if (first != null && song.ytId.isEmpty()) {
                setVideo(first)
            }
            updateYtCard()
        }
    }

    private fun setVideo(v: Video) {
        if (metro.syncRunning) metro.stop(byUser = false)
        song.ytId = v.id
        song.ytTitle = v.title
        song.ytChannel = v.channel
        persistIfSaved()
        updateYtCard()
    }

    private fun ytButton() {
        when {
            song.ytId.isEmpty() -> pickerDialog()
            playerBox.visibility != View.VISIBLE -> openPlayer()
            else -> player?.evaluateJavascript("toggle()", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPlayer() {
        if (song.ytId.isEmpty()) return
        val frame = playerBox.tag as FrameLayout
        if (player == null) {
            player = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                setBackgroundColor(0xFF000000.toInt())
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                        // Tautan keluar (logo YouTube, dll.) dibuka di aplikasi YouTube
                        startActivity(Intent(Intent.ACTION_VIEW, req.url))
                        return true
                    }
                }
                addJavascriptInterface(Bridge(), "Android")
            }
            frame.addView(player, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        player!!.loadDataWithBaseURL(YouTube.ORIGIN + "/", YouTube.playerHtml(song.ytId), "text/html", "utf-8", null)
        playerBox.visibility = View.VISIBLE
        updateSyncTools()
        updateYtCard()
    }

    private fun closePlayer() {
        vState = -1
        if (metro.syncRunning) metro.stop(byUser = false)
        player?.evaluateJavascript("pause()", null)
        player?.loadUrl("about:blank")
        playerBox.visibility = View.GONE
        playing = false
        updateYtButton()
        updateYtCard()
    }

    private fun openInYouTube() {
        if (song.ytId.isEmpty()) return
        player?.evaluateJavascript("pause()", null)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${song.ytId}")))
    }

    private inner class Bridge {
        @JavascriptInterface
        fun state(s: Int) = runOnUiThread {
            playing = s == 1 || s == 3
            updateYtButton()
        }

        @JavascriptInterface
        fun time(t: Double, st: Int) = runOnUiThread {
            vTime = t
            vWall = android.os.SystemClock.elapsedRealtime()
            vState = st
            onVideoTick()
        }

        @JavascriptInterface
        fun error(code: Int) = runOnUiThread {
            val msg = "Video ini tidak bisa diputar di dalam aplikasi (kode $code). " +
                "Biasanya karena pemilik video membatasinya, atau YouTube meminta verifikasi. " +
                "Pilih video lain atau buka di aplikasi YouTube."
            AlertDialog.Builder(this@SongActivity)
                .setMessage(msg)
                .setPositiveButton("Pilih video lain") { _, _ -> pickerDialog() }
                .setNeutralButton("Buka di YouTube") { _, _ -> openInYouTube() }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    /** Daftar pilihan video: hasil pencarian, ubah kata kunci, atau tempel link sendiri. */
    private fun pickerDialog() {
        val dlg = Dialog(this)
        val root = vbox().apply {
            setPadding(dp(14), dp(16), dp(14), dp(14))
            background = rounded(C.SURFACE, dpf(20f))
        }
        root.addView(label("Pilih video YouTube", 17f, C.TEXT, bold = true))
        val sub = label("", 12.5f, C.MUTED).apply { setPadding(0, dp(2), 0, dp(10)) }
        root.addView(sub)
        val input = EditText(this).apply {
            setText(ytQuery())
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            hint = "Kata kunci, mis. judul + \"live\""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(C.BG, dpf(12f), dp(1), C.LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(input)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE
        }
        root.addView(progress, lp(MATCH, dp(4)).apply { topMargin = dp(6) })
        val list = vbox()
        val listScroll = ScrollView(this)
        listScroll.addView(list)
        root.addView(listScroll, lp(MATCH, (resources.displayMetrics.heightPixels * 0.45).toInt()))
        root.addView(small(pill("📋 Tempel link YouTube sendiri") { pasteLink(dlg) }),
            lp(MATCH, WRAP).apply { topMargin = dp(10) })

        fun show(videos: List<Video>?) {
            list.removeAllViews()
            if (videos.isNullOrEmpty()) {
                list.addView(label(if (videos == null) "Gagal mencari. Periksa internet." else "Tidak ada hasil.", 14f, C.MUTED)
                    .apply { setPadding(dp(6), dp(16), dp(6), dp(16)) })
                return
            }
            for (v in videos) {
                val row = hbox().apply {
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    if (v.id == song.ytId) background = rounded(C.SURFACE2, dpf(12f), dp(1), C.CHORD)
                    isClickable = true
                    setOnClickListener {
                        setVideo(v)
                        dlg.dismiss()
                        openPlayer()
                    }
                }
                val th = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(C.LINE, dpf(8f))
                    clipToOutline = true
                }
                YouTube.loadThumb(v.id, th)
                val thFrame = FrameLayout(this)
                thFrame.addView(th, FrameLayout.LayoutParams(MATCH, MATCH))
                thFrame.addView(label(v.length, 10f, 0xFFFFFFFF.toInt()).apply {
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                    background = rounded(0xCC000000.toInt(), dpf(3f))
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
                row.addView(thFrame, lp(dp(104), dp(58)))
                val t = vbox().apply { setPadding(dp(10), 0, 0, 0) }
                t.addView(label(v.title + if (v.id == song.ytId) "  · DIPAKAI" else "", 13.5f, C.TEXT, bold = true).apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                t.addView(label(v.channel, 12f, C.MUTED).apply { maxLines = 1 })
                row.addView(t, lp(0, WRAP, 1f))
                list.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(4) })
            }
        }

        fun run(q: String) {
            sub.text = "Hasil untuk \"$q\""
            progress.visibility = View.VISIBLE
            YouTube.search(q) { r ->
                progress.visibility = View.GONE
                results = r
                show(r)
            }
        }

        input.setOnEditorActionListener { _, _, _ -> run(input.text.toString().trim()); true }
        if (results != null && input.text.toString() == ytQuery()) {
            sub.text = "Hasil untuk \"${ytQuery()}\""
            show(results)
        } else run(ytQuery())

        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    private fun pasteLink(parent: Dialog) {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val fromClip = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val input = EditText(this).apply {
            hint = "https://youtu.be/…"
            setText(if (YouTube.parseId(fromClip) != null) fromClip else "")
            setSingleLine()
            setTextColor(C.TEXT)
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, lp(MATCH, WRAP)) }
        AlertDialog.Builder(this)
            .setTitle("Tempel link YouTube")
            .setView(box)
            .setPositiveButton("Pakai") { _, _ ->
                val id = YouTube.parseId(input.text.toString())
                if (id == null) {
                    Toast.makeText(this, "Link YouTube tidak dikenali", Toast.LENGTH_SHORT).show()
                } else {
                    setVideo(Video(id, "Video dari link", "", ""))
                    parent.dismiss()
                    openPlayer()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------------------------------------------------------- Aksi

    private fun transpose(d: Int) {
        var t = tr + d
        if (t > 11) t -= 12
        if (t < -11) t += 12
        tr = t
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
        small(playBtn)
    }

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
                // Nomor baris berubah: modulasi manual, catatan per baris, dan urutan direset
                song.mods.clear()
                song.modDismissed.clear()
                song.notes.clear()
                song.seq.clear()
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
        pm.menu.add(0, 9, 3, "📅 Simpan ke daftar ibadah")
        pm.menu.add(0, 6, 3, "Pilih video YouTube")
        pm.menu.add(0, 7, 3, "Catatan lagu")
        pm.menu.add(0, 8, 3, "Metronom")
        pm.menu.add(0, 5, 4, "Bagikan teks")
        if (saved) pm.menu.add(0, 4, 5, "Hapus dari koleksi")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(song.source)))
                2 -> editTextDialog()
                3 -> renameDialog()
                5 -> share()
                6 -> pickerDialog()
                9 -> setlistSheet()
                7 -> songNoteDialog()
                8 -> metro.openSheet()
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
        val text = Renderer.render(lines, song.key, tr, mode(), song.mods, song.notes).joinToString("\n") { it.text }
        val yt = if (song.ytId.isNotEmpty()) "\nYouTube: https://youtu.be/${song.ytId}" else ""
        val tempo = if (song.bpm > 0) "\nTempo: ${song.bpm} BPM" + (if (song.sigBeats > 0) " · ${song.sigBeats}/${song.sigUnit}" else "") else ""
        val note = if (song.songNote.isNotBlank()) "\nCatatan: ${song.songNote}" else ""
        val full = "${song.title}\nDo = ${Notes.keyName(song.key + tr)}$tempo$yt$note\n\n$text"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, full)
        }, "Bagikan chord"))
    }
}
