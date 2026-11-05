package com.ronald.chordfinder

import android.app.Dialog
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ronald.chordfinder.music.BeatDetector
import com.ronald.chordfinder.music.Line
import com.ronald.chordfinder.music.MetroConfig
import com.ronald.chordfinder.music.SeqItem
import com.ronald.chordfinder.music.SequenceBuilder
import com.ronald.chordfinder.music.Step

/** Hubungan MetroUi dengan layar lagu. */
interface MetroHost {
    val song: Song
    val lines: List<Line>
    val manualMods: Map<Int, Int>
    fun persist()
    fun scrollToLine(src: Int)
    fun requestMic(granted: () -> Unit)
    /** Video YouTube lagu ini sedang terbuka di layar. */
    val videoOpen: Boolean
    /** Minta izin Android untuk mengambil suara HP (MediaProjection). */
    fun requestCapture(done: (Int, android.content.Intent?) -> Unit)
}

/** Semua tampilan metronom: tombol, bar ketukan, hitungan besar, panel atur, editor urutan. */
class MetroUi(private val a: AppCompatActivity, private val host: MetroHost) : MetronomeEngine.Listener {

    private val engine = MetronomeEngine(a)
    lateinit var button: TextView
    lateinit var beatBar: LinearLayout
    lateinit var overlay: TextView
    private lateinit var barBpm: TextView
    private lateinit var barDots: LinearLayout
    private lateinit var barSection: TextView
    private val dots = ArrayList<TextView>()
    private var config = MetroConfig()
    private val taps = ArrayList<Long>()

    /** Sedang jalan mengikuti video. [syncStartAt] posisi garis waktu saat mulai, [syncWall] jam saat mulai. */
    var syncRunning = false
        private set
    private var syncStartAt = 0.0
    private var syncWall = 0L

    /** Posisi garis waktu metronom sekarang (detik), untuk dibandingkan dengan video. */
    fun syncPosition(): Double = syncStartAt + (SystemClock.elapsedRealtime() - syncWall) / 1000.0

    /** Mulai mengikuti video. [videoTime] = posisi video sekarang (detik). */
    fun startSynced(videoTime: Double) {
        val s = host.song
        config = buildConfig().copy(
            bpm = Math.round(s.syncBpm).toInt(), bpmExact = s.syncBpm, countIn = false,
        )
        syncStartAt = videoTime - s.syncOffset
        syncWall = SystemClock.elapsedRealtime()
        syncRunning = true
        rebuildDots()
        barBpm.text = "♩ ${config.bpm}"
        barSection.text = "🔗 video"
        beatBar.visibility = View.VISIBLE
        overlay.visibility = View.GONE
        engine.start(config, Prefs.sound(a), Prefs.voice(a), this, startAt = if (syncStartAt == 0.0) 1e-6 else syncStartAt)
        updateButton()
        if (config.useSequence) {
            val bar = Math.floor(syncStartAt / (config.beats * 60.0 / s.syncBpm)).toInt()
            sectionForBar(bar)?.first?.let { if (Prefs.followScroll(a) && it.line >= 0) host.scrollToLine(it.line) }
        }
    }

    companion object {
        val CUES = listOf(
            "" to "— (tanpa aba-aba)", "intro" to "Intro", "verse" to "Verse", "prechorus" to "Pre-Chorus",
            "chorus" to "Chorus", "bridge" to "Bridge", "interlude" to "Interlude", "instrumental" to "Instrumental",
            "tag" to "Tag", "lastchorus" to "Last chorus", "repeat" to "Repeat", "up" to "Up! (modulasi)",
            "outro" to "Outro", "ending" to "Ending",
        )
        fun cueLabel(c: String) = CUES.firstOrNull { it.first == c }?.second ?: c
    }

    // ------------------------------------------------------------ view

    fun buildButton(): TextView {
        button = a.pill("") { if (engine.isRunning) stop() else openSheet() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            setOnLongClickListener { openSheet(); true }
        }
        updateButton()
        return button
    }

    fun buildBeatBar(): View {
        beatBar = a.hbox().apply {
            setPadding(a.dp(10), a.dp(6), a.dp(6), a.dp(6))
            background = rounded(C.SURFACE2, a.dpf(14f), a.dp(1), C.LINE)
            visibility = View.GONE
        }
        barBpm = a.label("", 14f, C.TEXT, bold = true)
        beatBar.addView(barBpm)
        barDots = a.hbox().apply { setPadding(a.dp(8), 0, a.dp(8), 0) }
        beatBar.addView(barDots)
        barSection = a.label("", 12.5f, C.SECTION).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        beatBar.addView(barSection, lp(0, WRAP, 1f))
        beatBar.addView(a.pill("■ Stop") { stop() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(a.dp(10), a.dp(5), a.dp(10), a.dp(5))
        })
        beatBar.layoutParams = lp(MATCH, WRAP).apply { setMargins(a.dp(12), 0, a.dp(12), a.dp(8)) }
        return beatBar
    }

    fun buildOverlay(): TextView {
        overlay = TextView(a).apply {
            gravity = Gravity.CENTER
            setTextColor(C.GO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 140f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(a.dpf(18f), 0f, 0f, 0x667BE495)
            setBackgroundColor((C.BG and 0x00FFFFFF) or 0xB3000000.toInt())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { stop() }
        }
        return overlay
    }

    private fun updateButton() {
        val bpm = host.song.bpm
        val running = this::button.isInitialized && engine.isRunning
        button.text = if (running) "■ ${config.bpm}" else if (bpm > 0) "♩ $bpm" else "♩ Metronom"
        button.background = rounded(C.GO, a.dpf(18f))
        button.setTextColor(0xFF0B2A14.toInt())
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    // ------------------------------------------------------- nilai

    /** Isi tempo & birama awal dari situs / teks lagu, kalau belum diatur. */
    fun initFrom(pageBpm: Int, pageSig: String) {
        val s = host.song
        if (s.bpm <= 0) {
            val b = if (pageBpm in 30..260) pageBpm else com.ronald.chordfinder.music.TempoText.bpm(s.raw) ?: 0
            if (b > 0) s.bpm = b
        }
        if (s.sigBeats <= 0) {
            val sig = Regex("^(\\d{1,2})/(4|8)$").find(pageSig.trim())?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                ?: com.ronald.chordfinder.music.TempoText.signature(s.raw)
            if (sig != null && sig.first in listOf(2, 3, 4, 6, 12)) { s.sigBeats = sig.first; s.sigUnit = sig.second }
        }
        if (this::button.isInitialized) updateButton()
    }

    private fun beats() = if (host.song.sigBeats > 0) host.song.sigBeats else 4
    private fun unit() = if (host.song.sigBeats > 0) host.song.sigUnit else 4
    private fun bpm() = if (host.song.bpm > 0) host.song.bpm else 80

    private fun sequence(): List<SeqItem> {
        val s = host.song
        if (s.seq.isEmpty()) s.seq.addAll(SequenceBuilder.auto(host.lines, host.manualMods))
        return s.seq
    }

    private fun buildConfig() = MetroConfig(
        bpm = bpm(),
        beats = beats(),
        unit = unit(),
        pattern = host.song.stepPattern(beats()).let { p ->
            if (host.song.pattern.isEmpty()) MetroConfig.defaultPattern(beats(), unit()) else p
        },
        subdivision = Prefs.subdivision(a),
        countIn = Prefs.countIn(a),
        cues = Prefs.cues(a),
        sequence = if (host.song.useSeq) sequence() else emptyList(),
        useSequence = host.song.useSeq,
    )

    // ------------------------------------------------------- jalan

    fun start() {
        config = buildConfig()
        if (host.song.bpm <= 0) { host.song.bpm = config.bpm }
        host.persist()
        rebuildDots()
        barBpm.text = "♩ ${config.bpm}"
        barSection.text = if (config.useSequence) "" else config.label
        beatBar.visibility = View.VISIBLE
        engine.start(config, Prefs.sound(a), Prefs.voice(a), this)
        AppLog.i("Metronom mulai ${config.bpm} BPM ${config.beats}/${config.unit}")
        updateButton()
        if (config.useSequence) config.sequence.firstOrNull()?.let { if (Prefs.followScroll(a) && it.line >= 0) host.scrollToLine(it.line) }
    }

    /** Pengguna menekan Stop saat metronom ikut video: jangan langsung jalan lagi sampai video dijeda. */
    var userStopped = false

    fun refreshButton() = updateButton()

    val isRunning get() = engine.isRunning

    fun stop(byUser: Boolean = true) {
        if (byUser && syncRunning) userStopped = true
        syncRunning = false
        engine.stop()
        beatBar.visibility = View.GONE
        overlay.visibility = View.GONE
        updateButton()
    }

    fun release() = engine.stop()

    private fun rebuildDots() {
        barDots.removeAllViews(); dots.clear()
        val n = config.beats
        val size = if (n > 6) a.dp(18) else a.dp(24)
        for (i in 0 until n) {
            val d = a.label("${i + 1}", if (n > 6) 10f else 12f, C.MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(C.LINE, size / 2f)
            }
            barDots.addView(d, lp(size, size).apply { rightMargin = a.dp(4) })
            dots.add(d)
        }
    }

    override fun onBeat(beat: Int, bar: Int, counting: Boolean) {
        dots.forEachIndexed { i, d ->
            val on = i == beat
            d.background = rounded(if (on) C.GO else C.LINE, d.layoutParams.width / 2f)
            d.setTextColor(if (on) 0xFF0B2A14.toInt() else C.MUTED)
        }
        if (counting) {
            overlay.text = "${beat + 1}"
            overlay.visibility = View.VISIBLE
        } else {
            overlay.visibility = View.GONE
            if (config.useSequence) {
                val tlSec = sectionForBar(bar)
                if (tlSec != null) barSection.text = (if (syncRunning) "🔗 " else "") +
                    "${tlSec.first.name} · bar ${tlSec.second}/${tlSec.first.bars}"
            } else if (syncRunning) {
                barSection.text = "🔗 video · bar ${bar + 1}"
            }
        }
    }

    private fun sectionForBar(bar: Int): Pair<SeqItem, Int>? {
        if (bar < 0) return null
        var b = 0
        for (s in config.sequence) {
            if (bar < b + s.bars) return s to (bar - b + 1)
            b += s.bars
        }
        return null
    }

    override fun onSection(index: Int) {
        val s = config.sequence.getOrNull(index) ?: return
        if (Prefs.followScroll(a) && s.line >= 0) host.scrollToLine(s.line)
    }

    override fun onEnd() {
        stop()
        Toast.makeText(a, "Lagu selesai", Toast.LENGTH_SHORT).show()
    }

    // --------------------------------------------------- panel atur

    private fun chipRow(): LinearLayout = a.hbox().apply { setPadding(0, a.dp(2), 0, a.dp(2)) }

    private fun small(t: TextView) = t.apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7))
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(a.label(title, 12f, C.MUTED).apply { setPadding(0, a.dp(12), 0, a.dp(6)) })
    }

    private fun scrollRow(row: LinearLayout): View = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }

    private fun toggle(parent: LinearLayout, title: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = a.hbox().apply {
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            background = rounded(C.SURFACE2, a.dpf(12f))
        }
        val t = a.vbox()
        t.addView(a.label(title, 14f, C.TEXT))
        t.addView(a.label(sub, 12f, C.MUTED))
        row.addView(t, lp(0, WRAP, 1f))
        val sw = TextView(a).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(4))
        }
        fun paint() {
            val on = get()
            sw.text = if (on) "ON" else "OFF"
            sw.background = rounded(if (on) C.GO else C.LINE, a.dpf(12f))
            sw.setTextColor(if (on) 0xFF0B2A14.toInt() else C.MUTED)
        }
        paint()
        row.isClickable = true
        row.setOnClickListener { set(!get()); paint() }
        row.addView(sw)
        parent.addView(row, lp(MATCH, WRAP).apply { topMargin = a.dp(6) })
    }

    fun openSheet() {
        val s = host.song
        val dlg = Dialog(a)
        val root = a.vbox().apply {
            setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
            background = a.sheetBg(22f)
        }
        val scroll = ScrollView(a)
        val box = a.vbox()
        scroll.addView(box)
        root.addView(scroll, lp(MATCH, 0, 1f))

        box.addView(a.expTitle("Metronom"))
        box.addView(a.expNote().apply { setPadding(0, a.dp(2), 0, 0) })

        // BPM
        val bpmRow = a.hbox(Gravity.CENTER).apply { setPadding(0, a.dp(10), 0, 0) }
        val bpmText = a.label("${bpm()}", 52f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = a.dp(120)
        }
        fun setBpm(v: Int) {
            s.bpm = v.coerceIn(30, 260); bpmText.text = "${s.bpm}"; host.persist(); updateButton()
        }
        fun circle(t: String, d: Int) = a.label(t, 24f, C.TEXT).apply {
            gravity = Gravity.CENTER
            background = rounded(C.SURFACE2, a.dpf(26f), a.dp(1), C.LINE)
            isClickable = true
            setOnClickListener { setBpm(bpm() + d) }
            setOnLongClickListener { setBpm(bpm() + d * 5); true }
        }
        bpmRow.addView(circle("−", -1), lp(a.dp(52), a.dp(52)))
        val bpmCol = a.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        bpmCol.addView(bpmText)
        bpmCol.addView(a.label("BPM  (tahan −/+ = 5)", 12f, C.MUTED).apply { gravity = Gravity.CENTER })
        bpmText.setOnClickListener {
            val input = EditText(a).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; setText("${bpm()}"); setTextColor(C.TEXT); selectAll()
            }
            AlertDialog.Builder(a).setTitle("Tempo (BPM)").setView(LinearLayout(a).apply {
                setPadding(a.dp(20), a.dp(8), a.dp(20), 0); addView(input, lp(MATCH, WRAP))
            }).setPositiveButton("OK") { _, _ -> input.text.toString().toIntOrNull()?.let { setBpm(it) } }
                .setNegativeButton("Batal", null).show()
        }
        bpmRow.addView(bpmCol, lp(WRAP, WRAP).apply { leftMargin = a.dp(14); rightMargin = a.dp(14) })
        bpmRow.addView(circle("+", 1), lp(a.dp(52), a.dp(52)))
        box.addView(bpmRow)

        val tools = a.hbox(Gravity.CENTER).apply { setPadding(0, a.dp(8), 0, 0) }
        tools.addView(small(a.pill("👆 Tap tempo") {
            val now = SystemClock.elapsedRealtime()
            if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
            taps.add(now)
            if (taps.size > 8) taps.removeAt(0)
            if (taps.size >= 3) {
                val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
                setBpm((60000 / avg).toInt())
            }
        }))
        tools.addView(small(a.pill("🎤 Deteksi") {
            detectStart { v -> setBpm(v) }
        }), lp(WRAP, WRAP).apply { leftMargin = a.dp(6) })
        tools.addView(small(a.pill("×2") { setBpm(bpm() * 2) }), lp(WRAP, WRAP).apply { leftMargin = a.dp(6) })
        tools.addView(small(a.pill("÷2") { setBpm(bpm() / 2) }), lp(WRAP, WRAP).apply { leftMargin = a.dp(6) })
        box.addView(scrollRow(tools).apply { layoutParams = lp(MATCH, WRAP) })

        // Birama
        section(box, "Birama")
        val sigRow = chipRow()
        val sigChips = ArrayList<Pair<TextView, Pair<Int, Int>>>()
        val stepsRow = a.hbox(Gravity.CENTER)
        fun paintSig() = sigChips.forEach { (v, sg) -> small(v.apply { setPill(sg.first == beats() && sg.second == unit()) }) }
        lateinit var paintSteps: () -> Unit
        for (sg in MetroConfig.SIGNATURES) {
            val chip = small(a.pill("${sg.first}/${sg.second}") {
                s.sigBeats = sg.first; s.sigUnit = sg.second; s.pattern = ""
                host.persist(); paintSig(); paintSteps()
            })
            sigChips.add(chip to sg)
            sigRow.addView(chip, lp(WRAP, WRAP).apply { rightMargin = a.dp(6) })
        }
        paintSig()
        box.addView(scrollRow(sigRow))

        // Pola ketukan
        section(box, "Pola ketukan — ketuk kotak: aksen → biasa → diam")
        paintSteps = {
            stepsRow.removeAllViews()
            val n = beats()
            val pat = if (s.pattern.isEmpty()) MetroConfig.defaultPattern(n, unit()) else s.stepPattern(n)
            val size = if (n > 6) a.dp(34) else a.dp(46)
            pat.forEachIndexed { i, st ->
                val v = a.vbox().apply {
                    gravity = Gravity.CENTER
                    val (bg, fg) = when (st) {
                        Step.ACCENT -> C.CHORD to C.BG
                        Step.NORMAL -> C.SURFACE2 to C.TEXT
                        Step.MUTE -> C.BG to C.MUTED
                    }
                    background = rounded(bg, a.dpf(10f), a.dp(2), if (st == Step.ACCENT) C.CHORD else C.LINE)
                    addView(a.label("${i + 1}", if (n > 6) 12f else 15f, fg, bold = true).apply { gravity = Gravity.CENTER })
                    if (n <= 6) addView(a.label(when (st) { Step.ACCENT -> "aksen"; Step.NORMAL -> "biasa"; Step.MUTE -> "diam" },
                        9f, if (st == Step.ACCENT) 0xFF3A2A00.toInt() else C.MUTED).apply { gravity = Gravity.CENTER })
                    isClickable = true
                    setOnClickListener {
                        val cur = pat.toMutableList()
                        cur[i] = when (cur[i]) { Step.ACCENT -> Step.NORMAL; Step.NORMAL -> Step.MUTE; Step.MUTE -> Step.ACCENT }
                        s.setStepPattern(cur); host.persist(); paintSteps()
                    }
                }
                stepsRow.addView(v, lp(size, size).apply { rightMargin = a.dp(5) })
            }
        }
        paintSteps()
        box.addView(scrollRow(stepsRow))

        // Pecahan ketukan & bunyi
        section(box, "Pecahan ketukan")
        val subRow = chipRow()
        val subs = listOf(1 to "♩", 2 to "♪♪", 3 to "♪♪♪", 4 to "♬♬")
        val subChips = ArrayList<TextView>()
        subs.forEach { (v, t) ->
            val chip = small(a.pill(t) { Prefs.setSubdivision(a, v); subChips.forEachIndexed { i, c -> small(c.apply { setPill(subs[i].first == v) }) } })
            chip.setPill(Prefs.subdivision(a) == v); small(chip)
            subChips.add(chip)
            subRow.addView(chip, lp(WRAP, WRAP).apply { rightMargin = a.dp(6) })
        }
        box.addView(scrollRow(subRow))

        section(box, "Bunyi klik")
        val soundRow = chipRow()
        val soundChips = ArrayList<TextView>()
        MetronomeEngine.SOUNDS.forEach { (k, t) ->
            val chip = small(a.pill(t) {
                Prefs.setSound(a, k)
                soundChips.forEachIndexed { i, c -> small(c.apply { setPill(MetronomeEngine.SOUNDS[i].first == k) }) }
            })
            chip.setPill(Prefs.sound(a) == k); small(chip)
            soundChips.add(chip)
            soundRow.addView(chip, lp(WRAP, WRAP).apply { rightMargin = a.dp(6) })
        }
        box.addView(scrollRow(soundRow))

        section(box, "Suara hitungan & aba-aba")
        val voiceRow = chipRow()
        val voiceChips = ArrayList<TextView>()
        MetronomeEngine.VOICES.forEach { (k, t) ->
            val chip = small(a.pill(t) {
                Prefs.setVoice(a, k)
                voiceChips.forEachIndexed { i, c -> small(c.apply { setPill(MetronomeEngine.VOICES[i].first == k) }) }
                previewVoice(k)
            })
            chip.setPill(Prefs.voice(a) == k); small(chip)
            voiceChips.add(chip)
            voiceRow.addView(chip, lp(WRAP, WRAP).apply { rightMargin = a.dp(6) })
        }
        box.addView(scrollRow(voiceRow))

        toggle(box, "Hitungan masuk", "\"one, two, three, four\" sebelum mulai · 1 bar",
            { Prefs.countIn(a) }, { Prefs.setCountIn(a, it) })
        toggle(box, "Urutan lagu (sequencer)", "Ikuti bagian lagu dengan jumlah bar",
            { s.useSeq }, { s.useSeq = it; host.persist() })
        toggle(box, "Aba-aba bagian", "\"Chorus!\", \"Bridge!\" 1 bar sebelum bagian berikutnya",
            { Prefs.cues(a) }, { Prefs.setCues(a, it) })
        toggle(box, "Chord ikut bagian", "Layar pindah ke bagian yang sedang dimainkan",
            { Prefs.followScroll(a) }, { Prefs.setFollowScroll(a, it) })
        box.addView(small(a.pill("☰ Atur urutan lagu…") { seqEditor() }), lp(MATCH, WRAP).apply { topMargin = a.dp(8) })

        val go = a.label("▶ Mulai", 16f, 0xFF0B2A14.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, a.dp(13), 0, a.dp(13))
            background = rounded(C.GO, a.dpf(16f))
            isClickable = true
            setOnClickListener { dlg.dismiss(); start() }
        }
        root.addView(go, lp(MATCH, WRAP).apply { topMargin = a.dp(10) })

        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.96).toInt(),
            (a.resources.displayMetrics.heightPixels * 0.86).toInt())
        dlg.window?.setGravity(Gravity.BOTTOM)
        dlg.show()
    }

    private fun previewVoice(label: String) {
        // Putar "one" + "chorus" sekali sebagai contoh suara
        val prev = MetroConfig(bpm = 100, beats = 4, countIn = true, cues = false, sequence = listOf(SeqItem("x", 1, "")),
            useSequence = true, pattern = listOf(Step.MUTE, Step.MUTE, Step.MUTE, Step.MUTE))
        if (engine.isRunning) return
        engine.start(prev, Prefs.sound(a), label, object : MetronomeEngine.Listener {})
    }

    // ------------------------------------------------- deteksi tempo (AI)

    private val detectSeconds = 15

    private fun detectTitle() = android.text.SpannableStringBuilder("🎤 Deteksi tempo")
        .appendBadge("AI", C.NUMBER).appendExp("EKSP")

    /** Pilih sumber suara (Android 10+), lalu dengarkan dan hitung. */
    private fun detectStart(result: (Int) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            host.requestMic { detectRun(false, null, result) }
            return
        }
        var fromDevice = host.videoOpen
        val box = a.vbox().apply { setPadding(a.dp(20), a.dp(8), a.dp(20), 0) }
        fun card(title: String, sub: String) = a.vbox().apply {
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            addView(a.label(title, 15f, C.TEXT, bold = true))
            addView(a.label(sub, 13f, C.MUTED).apply { setPadding(0, a.dp(2), 0, 0) })
            isClickable = true
        }
        val dev = card("📱 Langsung dari HP", "Suara video yang diputar di Chord Finder dibaca langsung. Paling akurat dan tidak terganggu suara ruangan.")
        val mic = card("🎤 Mikrofon", "Untuk lagu dari speaker, tim musik, atau HP lain.")
        fun paint() {
            dev.background = rounded(C.BG, a.dpf(12f), a.dp(if (fromDevice) 2 else 1), if (fromDevice) C.CHORD else C.LINE)
            mic.background = rounded(C.BG, a.dpf(12f), a.dp(if (!fromDevice) 2 else 1), if (!fromDevice) C.CHORD else C.LINE)
        }
        dev.setOnClickListener { fromDevice = true; paint() }
        mic.setOnClickListener { fromDevice = false; paint() }
        paint()
        box.addView(dev, lp(MATCH, WRAP).apply { topMargin = a.dp(6) })
        box.addView(mic, lp(MATCH, WRAP).apply { topMargin = a.dp(10) })
        AlertDialog.Builder(a).setTitle(detectTitle()).setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Mulai") { _, _ ->
                if (!fromDevice) { host.requestMic { detectRun(false, null, result) }; return@setPositiveButton }
                host.requestMic {
                    host.requestCapture { code, data ->
                        if (code == android.app.Activity.RESULT_OK && data != null) {
                            AppLog.ai("Izin Android: diberikan")
                            detectRun(true, code to data, result)
                        } else {
                            AppLog.ai("Izin Android: ditolak")
                            android.widget.Toast.makeText(a, "Izin ditolak. Bisa pakai Mikrofon atau Tap tempo.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.show()
    }

    private fun detectRun(fromDevice: Boolean, grant: Pair<Int, android.content.Intent>?, result: (Int) -> Unit) {
        val box = a.vbox().apply { setPadding(a.dp(20), a.dp(12), a.dp(20), 0) }
        val big = a.label("", 44f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val msg = a.label(
            if (fromDevice) "📱 Mendengarkan suara video… $detectSeconds detik lagi"
            else "Putar lagunya sekarang (YouTube, speaker, atau tim). Mendengarkan $detectSeconds detik…", 14f, C.TEXT)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val note = a.expNote().apply { setPadding(0, a.dp(10), 0, 0) }
        box.addView(big); box.addView(msg); box.addView(bar, lp(MATCH, WRAP).apply { topMargin = a.dp(12) })
        box.addView(note)
        var finished = false
        val d = AlertDialog.Builder(a).setTitle(detectTitle()).setView(box)
            .setNegativeButton("Tutup", null).show()
        d.setOnDismissListener {
            if (!finished) {
                AppLog.ai("Dibatalkan")
                if (fromDevice && android.os.Build.VERSION.SDK_INT >= 29) CaptureService.stop() else engine.cancelMic()
            }
        }
        AppLog.ai("Mulai deteksi · sumber: ${if (fromDevice) "HP" else "mikrofon"} · $detectSeconds dtk")

        fun analyze(audio: FloatArray?, error: String?) {
            if (!d.isShowing) return
            if (audio == null || audio.size < LogMelSize.MIN) {
                finished = true
                val why = error ?: if (fromDevice) "Suara HP tidak bisa diambil." else "Mikrofon tidak bisa dipakai."
                AppLog.e("Deteksi tempo: $why")
                msg.text = "$why Coba lagi, pakai sumber lain, atau Tap tempo."
                bar.visibility = View.GONE
                return
            }
            val db = TempoAi.loudnessDb(audio)
            AppLog.ai("Suara: ${"%.1f".format(audio.size / 22050.0)} dtk · keras rata2 ${"%.0f".format(db)} dB")
            if (db < -55) {
                finished = true
                AppLog.w("Deteksi tempo: hampir tidak ada suara")
                msg.text = if (fromDevice) "Tidak ada suara dari HP. Pastikan videonya sedang diputar, lalu coba lagi."
                    else "Suara terlalu pelan. Dekatkan HP ke sumber suara, atau pakai Tap tempo."
                bar.visibility = View.GONE
                return
            }
            msg.text = "🤖 Menghitung ketukan…"
            bar.isIndeterminate = true
            note.text = "Biasanya kurang dari 2 detik."
            Thread {
                val ai = try { TempoAi.detect(a, audio) } catch (e: Throwable) { AppLog.e("AI gagal", e); null }
                val old = try { BeatDetector.estimate(audio, 22050) } catch (e: Throwable) { null }
                a.runOnUiThread {
                    finished = true
                    AppLog.ai("Hasil AI ${ai?.bpm?.let { "$it BPM" } ?: "-"} · cara lama ${old?.let { "$it BPM" } ?: "-"}")
                    if (!d.isShowing) return@runOnUiThread
                    bar.visibility = View.GONE
                    note.text = a.expNote().text
                    val bpm = ai?.bpm ?: old
                    if (bpm == null) {
                        AppLog.ai("Dipakai: - (tidak terdeteksi)")
                        msg.text = "Tempo tidak terdeteksi. Coba lebih dekat ke sumber suara, atau pakai Tap tempo."
                        return@runOnUiThread
                    }
                    val how = if (ai != null) "AI" else "cara biasa"
                    AppLog.ai("Dipakai: $bpm BPM ($how)")
                    big.text = android.text.SpannableStringBuilder("$bpm").apply {
                        val s0 = length
                        append(" BPM")
                        setSpan(android.text.style.RelativeSizeSpan(0.32f), s0, length, 0)
                        setSpan(android.text.style.ForegroundColorSpan(C.MUTED), s0, length, 0)
                    }
                    big.visibility = View.VISIBLE
                    msg.gravity = Gravity.CENTER
                    msg.text = "Terdeteksi sekitar $bpm BPM ($how).\nKalau terasa terlalu cepat/lambat, pakai ÷2 atau ×2."
                    result(bpm)
                }
            }.start()
        }

        if (fromDevice && grant != null && android.os.Build.VERSION.SDK_INT >= 29) {
            CaptureService.listener = object : CaptureService.Listener {
                override fun progress(p: Float, secondsLeft: Int) {
                    bar.progress = (p * 100).toInt()
                    msg.text = "📱 Mendengarkan suara video… $secondsLeft detik lagi"
                }
                override fun done(audio22k: FloatArray?, error: String?) {
                    CaptureService.listener = null
                    analyze(audio22k, error)
                }
            }
            CaptureService.start(a, grant.first, grant.second, detectSeconds)
        } else {
            engine.recordMic(detectSeconds, { p, _ -> bar.progress = (p * 100).toInt() }) { audio -> analyze(audio, null) }
        }
    }

    private object LogMelSize { const val MIN = 22050 * 3 }

    // ------------------------------------------------- editor urutan

    private fun seqEditor() {
        val s = host.song
        sequence()
        val dlg = Dialog(a)
        val root = a.vbox().apply {
            setPadding(a.dp(14), a.dp(16), a.dp(14), a.dp(14))
            background = a.sheetBg(22f)
        }
        root.addView(a.expTitle("Urutan lagu"))
        root.addView(a.label("Diisi otomatis dari bagian lagu (± 1 chord = 1 bar). Sesuaikan dengan cara kalian main.", 12f, C.MUTED)
            .apply { setPadding(0, a.dp(2), 0, a.dp(8)) })
        val list = a.vbox()
        val sc = ScrollView(a).apply { addView(list) }
        root.addView(sc, lp(MATCH, 0, 1f))

        fun paint() {
            list.removeAllViews()
            s.seq.forEachIndexed { i, it ->
                val row = a.hbox().apply {
                    setPadding(a.dp(10), a.dp(8), a.dp(6), a.dp(8))
                    background = rounded(C.SURFACE2, a.dpf(12f))
                }
                val name = a.label(it.name, 14.5f, if (it.cue == "up") C.MOD else C.SECTION, bold = true).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener { _ ->
                        val input = EditText(a).apply { setText(it.name); setTextColor(C.TEXT); setSingleLine() }
                        AlertDialog.Builder(a).setTitle("Nama bagian").setView(LinearLayout(a).apply {
                            setPadding(a.dp(20), a.dp(8), a.dp(20), 0); addView(input, lp(MATCH, WRAP))
                        }).setPositiveButton("OK") { _, _ ->
                            s.seq[i] = it.copy(name = input.text.toString().ifBlank { it.name }); host.persist(); paint()
                        }.setNegativeButton("Batal", null).show()
                    }
                }
                row.addView(name, lp(0, WRAP, 1f))
                fun mini(t: String, f: () -> Unit) = small(a.pill(t) { f() }).apply {
                    setPadding(a.dp(9), a.dp(5), a.dp(9), a.dp(5))
                }
                row.addView(mini("−") { if (it.bars > 1) { s.seq[i] = it.copy(bars = it.bars - 1); host.persist(); paint() } })
                row.addView(a.label("${it.bars} bar", 13f, C.TEXT).apply {
                    gravity = Gravity.CENTER; minWidth = a.dp(50)
                })
                row.addView(mini("+") { s.seq[i] = it.copy(bars = it.bars + 1); host.persist(); paint() })
                row.addView(mini("🔊 " + if (it.cue.isEmpty()) "—" else cueLabel(it.cue).substringBefore(" (")) {
                    AlertDialog.Builder(a).setTitle("Aba-aba sebelum \"${it.name}\"")
                        .setItems(CUES.map { c -> c.second }.toTypedArray()) { _, w ->
                            s.seq[i] = it.copy(cue = CUES[w].first); host.persist(); paint()
                        }.show()
                }, lp(WRAP, WRAP).apply { leftMargin = a.dp(4) })
                row.addView(mini("✕") { s.seq.removeAt(i); host.persist(); paint() }, lp(WRAP, WRAP).apply { leftMargin = a.dp(4) })
                list.addView(row, lp(MATCH, WRAP).apply { bottomMargin = a.dp(6) })
            }
        }
        paint()

        val bottom = a.hbox().apply { setPadding(0, a.dp(10), 0, 0) }
        bottom.addView(small(a.pill("+ Bagian") {
            AlertDialog.Builder(a).setTitle("Tambah bagian")
                .setItems(CUES.drop(1).map { it.second }.toTypedArray()) { _, w ->
                    val c = CUES[w + 1]
                    s.seq.add(SeqItem(c.second.substringBefore(" ("), 8, c.first)); host.persist(); paint()
                }.show()
        }))
        bottom.addView(small(a.pill("↺ Otomatis") {
            s.seq.clear(); s.seq.addAll(SequenceBuilder.auto(host.lines, host.manualMods)); host.persist(); paint()
        }), lp(WRAP, WRAP).apply { leftMargin = a.dp(6) })
        bottom.addView(View(a), lp(0, 1, 1f))
        bottom.addView(small(a.pill("Selesai") { dlg.dismiss() }.apply { setPill(true) }))
        root.addView(bottom)

        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.96).toInt(),
            (a.resources.displayMetrics.heightPixels * 0.8).toInt())
        dlg.show()
    }
}
