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
    private lateinit var starFilter: TextView
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var foundBar: LinearLayout
    private lateinit var foundTitle: TextView
    private lateinit var hint: TextView
    private val sourceChips = ArrayList<TextView>()

    private var onlineMode = true
    private var onlyStarred = false
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
        header.addView(label("Do = 1", 13f, C.NUMBER).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(C.SURFACE2, dpf(12f))
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
        starFilter = pill("★ Minggu ini") {
            onlyStarred = !onlyStarred
            refreshLibrary()
        }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        libTools.addView(starFilter)
        libTools.addView(View(this), lp(0, 1, 1f))
        libTools.addView(pill("+ Tempel chord") { pasteDialog() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        libraryPane.addView(libTools)

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

        updateChips()
        return root
    }

    private fun showTab(online: Boolean) {
        onlineMode = online
        tabOnline.setPill(online)
        tabLibrary.setPill(!online)
        onlinePane.visibility = if (online) View.VISIBLE else View.GONE
        libraryPane.visibility = if (online) View.GONE else View.VISIBLE
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
            .filter { !onlyStarred || it.starred }
            .filter { com.ronald.chordfinder.music.TextSearch.matches(it.searchIndex(), q) }
            .sortedWith(compareByDescending<Song> { it.starred }.thenBy { it.title.lowercase() })
    }

    private fun refreshLibrary() {
        starFilter.setPill(onlyStarred)
        libraryList.removeAllViews()
        val all = SongStore.all(this)
        val list = filteredSongs()
        libraryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        libraryEmpty.text = when {
            all.isEmpty() -> "Koleksi masih kosong.\n\nCari lagu di tab Cari Online, buka chord-nya, lalu tekan Simpan. " +
                "Setelah itu lagu bisa dicari di sini kapan saja, tanpa internet."
            onlyStarred && search.text.isBlank() -> "Belum ada lagu untuk Minggu ini.\nTekan ★ saat membuka lagu."
            else -> "Tidak ada yang cocok di koleksi.\nTekan Cari untuk mencarinya online."
        }
        if (onlyStarred && list.isNotEmpty()) {
            libraryList.addView(label("Kosongkan daftar Minggu ini", 13f, C.MUTED).apply {
                setPadding(dp(8), dp(4), dp(8), dp(10))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Hapus tanda ★ dari semua lagu? Lagunya tetap ada di koleksi.")
                        .setPositiveButton("Kosongkan") { _, _ -> SongStore.clearStars(this@MainActivity); refreshLibrary() }
                        .setNegativeButton("Batal", null).show()
                }
            })
        }
        for (s in list) libraryList.addView(songRow(s))
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
