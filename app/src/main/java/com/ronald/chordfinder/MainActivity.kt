package com.ronald.chordfinder

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var search: EditText
    private lateinit var tabOnline: TextView
    private lateinit var tabLibrary: TextView
    private lateinit var onlinePane: LinearLayout
    private lateinit var libraryPane: LinearLayout
    private lateinit var libraryList: LinearLayout
    private lateinit var libraryEmpty: TextView
    private lateinit var libTabs: List<TextView>
    private lateinit var libFab: TextView
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var foundBar: LinearLayout
    private lateinit var foundTitle: TextView
    private lateinit var hint: TextView
    private val sourceChips = ArrayList<TextView>()

    private var onlineMode = true
    /** 0 = Semua lagu, 1 = Daftar ibadah, 2 = Favorit */
    private var libTab = 0
    private var lastQuery = ""
    private var found: JSONObject? = null
    private val autoOpened = HashSet<String>()
    private val extractJs by lazy { assets.open("extract.js").bufferedReader().readText() }

    private val sources = listOf(
        "JRChord + PsalmNote" to "(site:jrchord.com OR site:psalmnote.com)",
        "JRChord" to "site:jrchord.com",
        "PsalmNote" to "site:psalmnote.com",
        "Semua situs" to "",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi().apply { fitSystemBars() })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    onlineMode && web.canGoBack() -> web.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        // Buka Koleksi kalau sudah ada lagu tersimpan
        showTab(online = SongStore.all(this).isEmpty())
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!onlineMode) refreshLibrary()
    }

    // ---------------------------------------------------------------- UI

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi(): View {
        val root = vbox().apply { setBackgroundColor(C.BG) }

        // Judul
        val header = hbox().apply { setPadding(dp(16), dp(14), dp(16), dp(4)) }
        header.addView(label("Chord Finder", 22f, C.TEXT, bold = true), lp(0, WRAP, 1f))
        header.addView(label("⋮", 22f, C.TEXT).apply {
            setPadding(dp(14), dp(2), dp(4), dp(2))
            setOnClickListener { v -> mainMenu(v) }
        })
        root.addView(header)

        // Kotak pencarian
        val searchRow = hbox().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        search = EditText(this).apply {
            hint = "Judul atau potongan lirik…"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(C.TEXT)
            setHintTextColor(C.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = rounded(C.SURFACE, dpf(14f), dp(1), C.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEARCH ||
                    (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                ) { submit(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!onlineMode) refreshLibrary() }
            })
        }
        searchRow.addView(search, lp(0, WRAP, 1f))
        searchRow.addView(pill("Cari") { submit() }.apply { setPill(true) }, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        root.addView(searchRow)

        // Tab
        val tabs = hbox().apply { setPadding(dp(16), dp(2), dp(16), dp(8)) }
        tabOnline = pill("Cari Online") { showTab(true) }
        tabLibrary = pill("Koleksi") { showTab(false) }
        tabs.addView(tabOnline)
        tabs.addView(tabLibrary, lp(WRAP, WRAP).apply { leftMargin = dp(8) })
        root.addView(tabs)

        val content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))

        // ---- Panel online
        onlinePane = vbox()
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = hbox().apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        sources.forEachIndexed { i, (name, _) ->
            val chip = pill(name) {
                Prefs.setSource(this, i)
                updateChips()
                if (lastQuery.isNotBlank()) runOnline(lastQuery)
            }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
            sourceChips.add(chip)
            chipRow.addView(chip, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        chipScroll.addView(chipRow)
        onlinePane.addView(chipScroll)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        onlinePane.addView(progress, lp(MATCH, dp(3)))

        val webFrame = FrameLayout(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setBackgroundColor(C.BG)
            webViewClient = ChordClient()
        }
        webFrame.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        hint = label(
            "Ketik judul atau sepotong lirik, lalu tekan Cari.\n\n" +
                "Hasil diutamakan dari JRChord dan PsalmNote. Buka salah satu hasil, " +
                "chord-nya otomatis ditampilkan lengkap dengan notasi angka.",
            15f, C.MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(C.BG)
        }
        webFrame.addView(hint, FrameLayout.LayoutParams(MATCH, MATCH))

        // Bar "chord ditemukan"
        foundBar = hbox().apply {
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = rounded(C.SURFACE2, dpf(16f), dp(1), C.CHORD)
            visibility = View.GONE
            elevation = dpf(6f)
        }
        val foundText = vbox()
        foundText.addView(label("Chord ditemukan", 12f, C.CHORD, bold = true))
        foundTitle = label("", 15f, C.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        foundText.addView(foundTitle)
        foundBar.addView(foundText, lp(0, WRAP, 1f))
        foundBar.addView(pill("Buka") { openFound() }.apply { setPill(true) })
        webFrame.addView(foundBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(dp(12), dp(12), dp(12), dp(12))
        })
        onlinePane.addView(webFrame, lp(MATCH, 0, 1f))
        content.addView(onlinePane, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- Panel koleksi
        libraryPane = vbox()
        val libTools = hbox().apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        libTabs = listOf("Semua lagu", "📅 Daftar ibadah", "★ Favorit").mapIndexed { i, t ->
            pill(t) { libTab = i; refreshLibrary() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(11), dp(7), dp(11), dp(7))
            }
        }
        libTabs.forEachIndexed { i, v -> libTools.addView(v, lp(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(6) }) }
        libraryPane.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(libTools) })

        val scroll = ScrollView(this)
        val scrollInner = vbox().apply { setPadding(dp(12), 0, dp(12), dp(24)) }
        libraryEmpty = label("", 15f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        scrollInner.addView(libraryEmpty)
        libraryList = vbox()
        scrollInner.addView(libraryList)
        scroll.addView(scrollInner)
        libraryPane.addView(scroll, lp(MATCH, 0, 1f))
        content.addView(libraryPane, FrameLayout.LayoutParams(MATCH, MATCH))
        libFab = label("", 15f, C.BG, bold = true).apply {
            setPadding(dp(18), dp(13), dp(18), dp(13))
            background = rounded(C.CHORD, dpf(18f))
            elevation = dpf(6f)
            isClickable = true
            setOnClickListener {
                if (libTab == 1) SetlistDialogs.create(this@MainActivity) { openSetlist(it) } else pasteDialog()
            }
        }
        content.addView(libFab, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })

        updateChips()
        return root
    }

    private fun showTab(online: Boolean) {
        onlineMode = online
        tabOnline.setPill(online)
        tabLibrary.setPill(!online)
        onlinePane.visibility = if (online) View.VISIBLE else View.GONE
        libraryPane.visibility = if (online) View.GONE else View.VISIBLE
        libFab.visibility = libraryPane.visibility
        search.hint = if (online) "Judul atau potongan lirik…" else "Cari di koleksi (judul / lirik)…"
        if (!online) refreshLibrary()
    }

    private fun updateChips() {
        val sel = Prefs.source(this)
        sourceChips.forEachIndexed { i, c -> c.setPill(i == sel) }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ---------------------------------------------------------- Online

    private fun submit() {
        val q = search.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        if (!onlineMode) {
            // Di Koleksi: kalau tidak ada yang cocok, tawarkan cari online
            if (filteredSongs().isEmpty()) { showTab(true); runOnline(q) }
            return
        }
        runOnline(q)
    }

    private fun runOnline(q: String) {
        lastQuery = q
        val site = sources[Prefs.source(this)].second
        val full = listOf("chord", q, site).filter { it.isNotBlank() }.joinToString(" ")
        hint.visibility = View.GONE
        hideFound()
        web.loadUrl("https://www.google.com/search?hl=id&q=" + URLEncoder.encode(full, "UTF-8"))
    }

    private fun isSearchPage(url: String?): Boolean {
        val host = url?.let { Uri.parse(it).host } ?: return true
        return host.contains("google.") || host.contains("bing.") || host.contains("duckduckgo.")
    }

    private fun isPriority(url: String) =
        Uri.parse(url).host?.let { it.endsWith("jrchord.com") || it.endsWith("psalmnote.com") } == true

    private inner class ChordClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val scheme = req.url.scheme ?: return true
            // Abaikan tautan ke aplikasi lain (intent://, market://) supaya tetap di sini
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progress.visibility = View.VISIBLE
            hideFound()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
            if (url == null || isSearchPage(url)) return
            // Beri waktu sedikit untuk halaman yang memuat chord lewat JavaScript
            view.postDelayed({ extract(url) }, 600)
        }
    }

    private fun extract(url: String) {
        if (web.url != url) return
        web.evaluateJavascript(extractJs) { result ->
            try {
                // evaluateJavascript mengembalikan string JSON yang di-quote
                val json = JSONObject(JSONObject("{\"v\":$result}").getString("v"))
                if (json.optInt("score") < 3 || json.optString("text").isBlank()) return@evaluateJavascript
                found = json
                foundTitle.text = cleanTitle(json.optString("title"))
                foundBar.visibility = View.VISIBLE
                if (isPriority(url) && autoOpened.add(url)) openFound()
            } catch (_: Exception) { }
        }
    }

    private fun hideFound() {
        found = null
        foundBar.visibility = View.GONE
    }

    private fun openFound() {
        val f = found ?: return
        val url = f.optString("url")
        val existing = SongStore.findBySource(this, url)
        val i = Intent(this, SongActivity::class.java)
        if (existing != null) {
            i.putExtra(SongActivity.EXTRA_ID, existing.id)
        } else {
            i.putExtra(SongActivity.EXTRA_TITLE, cleanTitle(f.optString("title")))
            i.putExtra(SongActivity.EXTRA_TEXT, f.optString("text"))
            i.putExtra(SongActivity.EXTRA_SOURCE, url)
            i.putExtra(SongActivity.EXTRA_QUERY, cleanTitle(f.optString("pageTitle")))
            i.putExtra(SongActivity.EXTRA_BPM, f.optInt("bpm", 0))
            i.putExtra(SongActivity.EXTRA_SIG, f.optString("sig"))
        }
        startActivity(i)
    }

    // --------------------------------------------------------- Koleksi

    private fun filteredSongs(): List<Song> {
        val q = search.text.toString()
        return SongStore.all(this)
            .filter { libTab != 2 || it.starred }
            .filter { com.ronald.chordfinder.music.TextSearch.matches(it.searchIndex(), q) }
            .sortedWith(compareByDescending<Song> { it.starred }.thenBy { it.title.lowercase() })
    }

    private fun refreshLibrary() {
        libTabs.forEachIndexed { i, v -> v.setPill(i == libTab); v.setPadding(dp(11), dp(7), dp(11), dp(7)) }
        libFab.text = if (libTab == 1) "+ Daftar baru" else "+ Tempel chord"
        libraryList.removeAllViews()
        if (libTab == 1) { refreshSetlists(); return }
        val all = SongStore.all(this)
        val list = filteredSongs()
        libraryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        libraryEmpty.text = when {
            all.isEmpty() -> "Koleksi masih kosong.\n\nCari lagu di tab Cari Online, buka chord-nya, lalu tekan Simpan. " +
                "Setelah itu lagu bisa dicari di sini kapan saja, tanpa internet."
            libTab == 2 && search.text.isBlank() -> "Belum ada lagu favorit.\nTekan ☆ pada lagu untuk menandainya."
            else -> "Tidak ada yang cocok di koleksi.\nTekan Cari untuk mencarinya online."
        }
        for (s in list) libraryList.addView(songRow(s))
        libraryList.addView(View(this), lp(MATCH, dp(70)))
    }

    private fun openSetlist(s: Setlist) {
        startActivity(Intent(this, SetlistActivity::class.java).putExtra(SetlistActivity.EXTRA_ID, s.id))
    }

    /** Tab Daftar ibadah: akan datang (terdekat dulu) lalu yang sudah lewat. Pencarian = judul lagu di dalamnya. */
    private fun refreshSetlists() {
        val q = search.text.toString()
        fun match(s: Setlist) = q.isBlank() ||
            com.ronald.chordfinder.music.TextSearch.matches(com.ronald.chordfinder.music.TextSearch.normalize(
                Dates.pretty(s.date) + " " + s.name + " " + s.items.mapNotNull { SongStore.get(this, it.songId)?.title }.joinToString(" ")), q)
        val (up, past) = SetlistStore.sorted(this)
        val u = up.filter(::match); val p = past.filter(::match)
        libraryEmpty.visibility = if (u.isEmpty() && p.isEmpty()) View.VISIBLE else View.GONE
        libraryEmpty.text = if (q.isBlank()) "Belum ada daftar ibadah.\n\nTekan + Daftar baru untuk membuat daftar lagu Minggu ini."
            else "Tidak ada daftar yang cocok."
        fun header(t: String) = label(t, 12f, C.MUTED).apply { setPadding(dp(4), dp(6), dp(4), dp(6)); letterSpacing = 0.05f }
        if (u.isNotEmpty()) libraryList.addView(header("AKAN DATANG"))
        u.forEachIndexed { i, s -> libraryList.addView(setlistCard(s, highlight = i == 0)) }
        if (p.isNotEmpty()) libraryList.addView(header("SUDAH LEWAT"))
        p.forEach { libraryList.addView(setlistCard(it, highlight = false, dim = true)) }
        libraryList.addView(View(this), lp(MATCH, dp(70)))
    }

    private fun setlistCard(s: Setlist, highlight: Boolean, dim: Boolean = false): View {
        val card = vbox().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = if (highlight) rounded(C.SURFACE, dpf(14f), dp(1), C.CHORD) else rounded(C.SURFACE, dpf(14f))
            alpha = if (dim) 0.8f else 1f
            isClickable = true
            setOnClickListener { openSetlist(s) }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(Dates.pretty(s.date))
                    .setItems(arrayOf("Ubah tanggal / nama", "Hapus daftar")) { _, w ->
                        if (w == 0) SetlistDialogs.edit(this@MainActivity, s) { SetlistStore.persist(this@MainActivity); refreshLibrary() }
                        else AlertDialog.Builder(this@MainActivity).setMessage("Hapus daftar ini? Lagunya tetap ada di Koleksi.")
                            .setPositiveButton("Hapus") { _, _ -> SetlistStore.delete(this@MainActivity, s.id); refreshLibrary() }
                            .setNegativeButton("Batal", null).show()
                    }.show()
                true
            }
        }
        val d = Dates.daysFromToday(s.date)
        val tag = when (d) { 0 -> "HARI INI"; 1 -> "BESOK"; else -> null }
        val title = android.text.SpannableStringBuilder(Dates.pretty(s.date))
        if (tag != null) {
            title.append("  ")
            val st = title.length
            title.append(" $tag ")
            title.setSpan(android.text.style.BackgroundColorSpan(C.CHORD), st, title.length, 0)
            title.setSpan(android.text.style.ForegroundColorSpan(C.BG), st, title.length, 0)
            title.setSpan(android.text.style.RelativeSizeSpan(0.7f), st, title.length, 0)
        }
        card.addView(label("", 16f, C.TEXT, bold = true).apply { text = title })
        card.addView(label("${s.name} · ${s.items.size} lagu", 12.5f, C.MUTED))
        if (!dim && s.items.isNotEmpty()) {
            val names = s.items.mapNotNull { SongStore.get(this, it.songId)?.title }
            val shown = names.take(3).mapIndexed { i, n -> "${i + 1}. $n" }.joinToString(" · ") +
                if (names.size > 3) " · +${names.size - 3}" else ""
            card.addView(label(shown, 13f, 0xFFC9C6BF.toInt()).apply { setPadding(0, dp(6), 0, 0); maxLines = 2 })
        }
        card.layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(8) }
        return card
    }

    // ------------------------------------------------ Ekspor / Impor

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.export(this).toByteArray()) }
            AlertDialog.Builder(this).setTitle("✓ Ekspor berhasil")
                .setMessage("${SongStore.all(this).size} lagu · ${SetlistStore.all(this).size} daftar ibadah\n\n" +
                    "Simpan file ini di tempat aman (Google Drive, WA ke diri sendiri). Pakai Impor Koleksi untuk memulihkannya.")
                .setPositiveButton("OK", null).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val r = Backup.import(this, text)
            refreshLibrary()
            AlertDialog.Builder(this).setTitle("✓ Impor selesai")
                .setMessage("Lagu: ${r.songsAdded} baru, ${r.songsUpdated} diperbarui\n" +
                    "Daftar ibadah: ${r.setlistsAdded} baru, ${r.setlistsUpdated} diperbarui")
                .setPositiveButton("OK", null).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Impor gagal")
                .setMessage("File ini bukan hasil Ekspor Koleksi Chord Finder, atau rusak.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun mainMenu(anchor: View) {
        val pm = android.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "⤓ Ekspor Koleksi")
        pm.menu.add(0, 2, 1, "⤒ Impor Koleksi")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> exportLauncher.launch("ChordFinder-koleksi-${Dates.today()}.json")
                2 -> importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            }
            true
        }
        pm.show()
    }

    private fun songRow(s: Song): View {
        val row = hbox().apply {
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = rounded(C.SURFACE, dpf(14f))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SongActivity::class.java).putExtra(SongActivity.EXTRA_ID, s.id))
            }
            setOnLongClickListener { confirmDelete(s); true }
        }
        row.layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(8) }
        val text = vbox()
        text.addView(label(s.title, 16f, C.TEXT, bold = true).apply { maxLines = 2 })
        val key = if (s.key >= 0) "Do = " + com.ronald.chordfinder.music.Notes.keyName(s.key + s.transpose) else "Kunci ?"
        val host = s.source.takeIf { it.isNotBlank() }?.let { Uri.parse(it).host?.removePrefix("www.") } ?: "manual"
        text.addView(label("$key  ·  $host", 13f, C.MUTED))
        row.addView(text, lp(0, WRAP, 1f))
        val star = label(if (s.starred) "★" else "☆", 22f, if (s.starred) C.STAR else C.MUTED).apply {
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setOnClickListener {
                s.starred = !s.starred
                SongStore.upsert(this@MainActivity, s)
                refreshLibrary()
            }
        }
        row.addView(star)
        return row
    }

    private fun confirmDelete(s: Song) {
        AlertDialog.Builder(this)
            .setTitle(s.title)
            .setMessage("Hapus lagu ini dari koleksi?")
            .setPositiveButton("Hapus") { _, _ -> SongStore.delete(this, s.id); refreshLibrary() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pasteDialog(prefill: String = "") {
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val title = EditText(this).apply {
            hint = "Judul lagu"
            setSingleLine()
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
        }
        val body = EditText(this).apply {
            hint = "Tempel chord + lirik di sini"
            setText(prefill)
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(title)
        box.addView(body)
        AlertDialog.Builder(this)
            .setTitle("Tempel chord")
            .setView(box)
            .setPositiveButton("Buka") { _, _ ->
                val text = body.text.toString()
                if (text.isBlank()) return@setPositiveButton
                startActivity(
                    Intent(this, SongActivity::class.java)
                        .putExtra(SongActivity.EXTRA_TITLE, title.text.toString().ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: "Tanpa judul" })
                        .putExtra(SongActivity.EXTRA_TEXT, text)
                )
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ----------------------------------------------- Bagikan dari aplikasi lain

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        intent.action = null
        if (text.isEmpty()) return
        val url = Regex("https?://\\S+").find(text)?.value
        if (url != null && text.length < url.length + 120) {
            showTab(true)
            hint.visibility = View.GONE
            web.loadUrl(url)
        } else {
            pasteDialog(text)
        }
    }
}
