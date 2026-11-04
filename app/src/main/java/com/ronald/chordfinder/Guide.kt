package com.ronald.chordfinder

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

/**
 * Satu langkah panduan.
 * [target] = kotak di layar (koordinat layar) yang disorot, atau null kalau tidak sedang tampil (langkah dilewati).
 * [since] = nomor build saat langkah ini ditambahkan. 0 = panduan dasar.
 * [needsAction] = true: langkah baru lanjut kalau fiturnya benar-benar terbuka (dialog/menu).
 * [event] = langkah lanjut saat aplikasi melapor kejadian ini (mis. "search").
 */
class GuideStep(
    val title: CharSequence,
    val text: () -> CharSequence,
    val target: () -> Rect?,
    val since: Int = 0,
    val needsAction: Boolean = false,
    val event: String? = null,
    val hand: Boolean = false,
)

/** Panduan interaktif: menyorot tombol asli dan membiarkan pengguna langsung mencobanya. */
object Guide {
    const val MAIN = "main"
    const val SONG = "song"

    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun build(a: Activity): Int {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    /**
     * Panggil paling awal di MainActivity (sebelum migrasi apa pun).
     * Pengguna lama (sudah memakai versi sebelumnya) tidak otomatis mendapat panduan dasar.
     */
    fun init(a: AppCompatActivity) {
        val p = sp(a)
        if (p.getBoolean("guideInit", false)) return
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        val existing = pi.firstInstallTime != pi.lastUpdateTime ||
            p.getBoolean("migKeyBtn", false) || p.contains("updCheck") || SongStore.all(a).isNotEmpty()
        val e = p.edit().putBoolean("guideInit", true)
        if (existing) {
            // Panduan dasar dianggap sudah dilihat; kartu "Diperbarui" tetap muncul (lastVer = 0)
            e.putInt("guideSeen_$MAIN", build(a)).putInt("guideSeen_$SONG", build(a))
        } else {
            e.putInt("lastVer", build(a))
        }
        e.apply()
    }

    /** Setelah update: kartu "✨ Diperbarui ke vX". Dipanggil dari MainActivity; [then] jalan setelah kartu ditutup. */
    fun showUpdatedCardIfNeeded(a: AppCompatActivity, then: () -> Unit) {
        val p = sp(a)
        val cur = build(a)
        if (p.getInt("lastVer", 0) >= cur) { then(); return }
        p.edit().putInt("lastVer", cur).apply()
        val ver = Updater.versionName(a)
        val arr = try { JSONArray(a.assets.open("changelog.json").bufferedReader().readText()) } catch (e: Exception) { JSONArray() }
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
        val entry = all.firstOrNull { it.optString("v") == ver } ?: all.firstOrNull()
        if (entry == null) { then(); return }
        val sb = SpannableStringBuilder()
        val items = entry.optJSONArray("items") ?: JSONArray()
        for (j in 0 until items.length()) {
            val it = items.getJSONArray(j)
            val fix = it.optString(0) == "fix"
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("• ")
            val k0 = sb.length
            sb.append(if (fix) "Fix " else "New ")
            sb.setSpan(ForegroundColorSpan(if (fix) C.NUMBER else C.GO), k0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), k0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(it.optString(1))
        }
        val dlg = android.app.Dialog(a)
        val root = a.vbox().apply {
            setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(14))
            background = a.sheetBg(20f)
        }
        root.addView(a.label("✨ Diperbarui ke v$ver", 18f, C.TEXT, bold = true))
        root.addView(a.label(entry.optString("date"), 12.5f, C.MUTED).apply { setPadding(0, a.dp(2), 0, a.dp(10)) })
        root.addView(android.widget.ScrollView(a).apply {
            addView(a.label("", 14f, C.TEXT).apply { text = sb; setLineSpacing(0f, 1.2f) })
        }, lp(MATCH, WRAP).apply { weight = 0f })
        val btns = a.hbox(Gravity.END).apply { setPadding(0, a.dp(14), 0, 0) }
        btns.addView(a.label("Nanti", 15f, C.MUTED, bold = true).apply {
            setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            setOnClickListener { dlg.dismiss() }
        })
        btns.addView(a.label("Lihat semua", 15f, C.BG, bold = true).apply {
            setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10))
            background = rounded(C.CHORD, a.dpf(16f))
            setOnClickListener {
                dlg.setOnDismissListener(null)
                dlg.dismiss()
                Updater.showHistory(a) { then() }
            }
        }, lp(WRAP, WRAP).apply { leftMargin = a.dp(8) })
        root.addView(btns)
        dlg.setOnDismissListener { then() }
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(a.dp(480)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    /** Mulai panduan [tour] kalau ada langkah yang belum pernah dilihat (atau sedang diulang). */
    fun maybeStart(a: AppCompatActivity, tour: String, steps: List<GuideStep>) {
        val p = sp(a)
        val replay = p.getBoolean("guideReplay_$tour", false)
        val seen = if (replay) -1 else p.getInt("guideSeen_$tour", -1)
        val todo = steps.filter { it.since > seen }
        if (todo.isEmpty()) return
        if (current(a) != null) return
        val at = p.getInt("guideAt_$tour", 0).coerceIn(0, todo.size - 1)
        val ov = GuideOverlay(a, tour, todo, at)
        (a.window.decorView as ViewGroup).addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** ⋮ → Panduan: ulang semua langkah, mulai dari layar utama. Layar lagu diulang saat lagu dibuka. */
    fun replay(a: AppCompatActivity, mainSteps: List<GuideStep>) {
        current(a)?.finish(false)
        sp(a).edit().putBoolean("guideReplay_$MAIN", true).putBoolean("guideReplay_$SONG", true)
            .putInt("guideAt_$MAIN", 0).putInt("guideAt_$SONG", 0).apply()
        maybeStart(a, MAIN, mainSteps)
    }

    /** Aplikasi melapor kejadian (mis. pencarian dijalankan). */
    fun event(a: Activity, name: String) { current(a)?.onEvent(name) }

    private fun current(a: Activity): GuideOverlay? {
        val d = a.window.decorView as ViewGroup
        for (i in 0 until d.childCount) (d.getChildAt(i) as? GuideOverlay)?.let { return it }
        return null
    }

    internal fun saveAt(c: Context, tour: String, i: Int) = sp(c).edit().putInt("guideAt_$tour", i).apply()

    internal fun done(a: Activity, tour: String) {
        sp(a).edit().putInt("guideSeen_$tour", build(a)).putBoolean("guideReplay_$tour", false)
            .remove("guideAt_$tour").apply()
    }

    /** Kotak sebuah view di layar, atau null kalau view tidak tampil. */
    fun rectOf(v: View?): Rect? {
        if (v == null || !v.isShown || v.width == 0 || v.height == 0) return null
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return Rect(l[0], l[1], l[0] + v.width, l[1] + v.height)
    }
}

/** Lapisan redup dengan lubang di tombol yang disorot, plus kotak penjelasan. */
@SuppressLint("ViewConstructor")
class GuideOverlay(
    private val a: AppCompatActivity,
    private val tour: String,
    private val steps: List<GuideStep>,
    start: Int,
) : FrameLayout(a) {
    private var idx = start
    private var hole: RectF? = null
    /** Sedang dicoba: lapisan disembunyikan, hanya tombol kecil "Lanjut panduan" yang tampil. */
    private var paused = false
    private var lostFocus = false
    private val main = Handler(Looper.getMainLooper())

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = a.dpf(2.5f)
        color = 0xFFF5B342.toInt()
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path()
    private val arrow = Path()

    private val tip: ViewGroup
    private val tipTitle: TextView
    private val tipText: TextView
    private val tipCount: TextView
    private val tipNext: TextView
    private val hand: TextView
    private val resumeChip: TextView
    private var handAnim: ObjectAnimator? = null

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { if (!paused) place() }

    init {
        setWillNotDraw(false)
        isClickable = false
        val dark = 0xFF16161D.toInt()
        tip = a.vbox().apply {
            background = rounded(0xFFFFFFFF.toInt(), a.dpf(14f))
            setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(10))
            elevation = a.dpf(8f)
        }
        tipTitle = a.label("", 15.5f, dark, bold = true)
        tipText = a.label("", 14f, 0xFF2A2A33.toInt()).apply { setLineSpacing(0f, 1.15f); setPadding(0, a.dp(4), 0, 0) }
        val foot = a.hbox().apply { setPadding(0, a.dp(10), 0, 0) }
        val skip = a.label("Lewati panduan", 13f, 0xFF777777.toInt()).apply {
            setPadding(0, a.dp(8), a.dp(8), a.dp(8))
            setOnClickListener { finish(true) }
        }
        tipCount = a.label("", 13f, 0xFF777777.toInt()).apply { setPadding(a.dp(8), 0, a.dp(10), 0) }
        tipNext = a.label("Lanjut", 14.5f, dark, bold = true).apply {
            setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
            background = rounded(0xFFF5B342.toInt(), a.dpf(14f))
            setOnClickListener { next() }
        }
        foot.addView(skip, lp(0, WRAP, 1f))
        foot.addView(tipCount)
        foot.addView(tipNext)
        tip.addView(tipTitle); tip.addView(tipText); tip.addView(foot)
        addView(tip, FrameLayout.LayoutParams(WRAP, WRAP))

        hand = a.label("👆", 34f, 0xFFFFFFFF.toInt()).apply { visibility = View.GONE }
        addView(hand, FrameLayout.LayoutParams(WRAP, WRAP))

        resumeChip = a.label("🧭 Lanjut panduan", 14f, dark, bold = true).apply {
            setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10))
            background = rounded(0xFFF5B342.toInt(), a.dpf(20f))
            elevation = a.dpf(6f)
            visibility = View.GONE
            setOnClickListener { resume(advance = true) }
        }
        addView(resumeChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = a.dp(52) })
        alpha = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        post { show(); animate().alpha(1f).setDuration(220).start() }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        handAnim?.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // --------------------------------------------------------------- langkah

    /** Langkah yang tombolnya sedang tampil. */
    private fun visibleSteps() = steps.filter { it.target() != null }

    private fun show() {
        // Lewati langkah yang tombolnya tidak tampil
        while (idx < steps.size && steps[idx].target() == null) idx++
        if (idx >= steps.size) { finish(false); return }
        Guide.saveAt(a, tour, idx)
        val st = steps[idx]
        tipTitle.text = st.title
        tipText.text = st.text()
        val vis = visibleSteps()
        val n = vis.indexOf(st) + 1
        tipCount.text = "$n/${vis.size}"
        tipNext.text = if (steps.drop(idx + 1).none { it.target() != null }) "Selesai" else "Lanjut"
        paused = false
        lostFocus = false
        tip.visibility = View.VISIBLE
        resumeChip.visibility = View.GONE
        setHand(st.hand)
        place()
    }

    fun next() {
        idx++
        show()
    }

    fun finish(@Suppress("UNUSED_PARAMETER") skipped: Boolean) {
        Guide.done(a, tour)
        animate().alpha(0f).setDuration(160).withEndAction { (parent as? ViewGroup)?.removeView(this) }.start()
    }

    fun onEvent(name: String) {
        val st = steps.getOrNull(idx) ?: return
        if (st.event == name) main.postDelayed({ resume(advance = true) }, 350)
    }

    /** Hitung ulang posisi lubang & kotak penjelasan dari posisi tombol yang sebenarnya. */
    private fun place() {
        val st = steps.getOrNull(idx) ?: return
        val r = st.target()
        if (r == null) { invalidate(); return }
        val me = IntArray(2)
        getLocationOnScreen(me)
        val pad = a.dpf(5f)
        val h = RectF(r.left - me[0] - pad, r.top - me[1] - pad, r.right - me[0] + pad, r.bottom - me[1] + pad)
        hole = h

        val side = a.dp(12)
        val maxW = a.dp(440)
        val w = (width - side * 2).coerceAtMost(maxW)
        tip.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        val th = tip.measuredHeight
        val gap = a.dp(14)
        val below = h.centerY() < height / 2f
        val ty = if (below) h.bottom + gap else h.top - gap - th
        val tx = (h.centerX() - w / 2f).coerceIn(side.toFloat(), (width - side - w).toFloat().coerceAtLeast(side.toFloat()))
        if (tip.layoutParams.width != w) tip.layoutParams = FrameLayout.LayoutParams(w, WRAP)
        tip.translationX = tx
        tip.translationY = ty.coerceIn(a.dpf(24f), (height - th - a.dp(8)).toFloat().coerceAtLeast(a.dpf(24f)))

        // panah segitiga dari kotak penjelasan ke tombol
        val ax = h.centerX().coerceIn(tx + a.dp(18), tx + w - a.dp(18))
        val s = a.dpf(9f)
        arrow.reset()
        if (below) {
            val y = tip.translationY
            arrow.moveTo(ax - s, y + 1); arrow.lineTo(ax, y - s); arrow.lineTo(ax + s, y + 1)
        } else {
            val y = tip.translationY + th
            arrow.moveTo(ax - s, y - 1); arrow.lineTo(ax, y + s); arrow.lineTo(ax + s, y - 1)
        }
        arrow.close()

        if (st.hand) {
            hand.measure(0, 0)
            hand.x = h.left + a.dp(24)
            hand.y = h.centerY() - a.dp(6)
        }
        invalidate()
    }

    private fun setHand(on: Boolean) {
        handAnim?.cancel()
        hand.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        // gerakan "tekan lama": turun, tahan, naik
        handAnim = ObjectAnimator.ofFloat(hand, "scaleX", 1f, 0.82f, 0.82f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { hand.scaleY = hand.scaleX; hand.alpha = 0.6f + 0.4f * ((hand.scaleX - 0.82f) / 0.18f) }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (paused) return
        val h = hole ?: run { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim); return }
        val rr = a.dpf(14f)
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(h, rr, rr, Path.Direction.CW)
        c.drawPath(path, dim)
        c.drawRoundRect(h, rr, rr, ring)
        c.drawPath(arrow, white)
    }

    // ---------------------------------------------------------------- sentuhan

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (paused) {
            // Hanya tombol "Lanjut panduan" yang ditangani; sisanya diteruskan ke aplikasi
            val r = Rect(); resumeChip.getHitRect(r)
            return if (resumeChip.visibility == View.VISIBLE && r.contains(e.x.toInt(), e.y.toInt())) super.dispatchTouchEvent(e) else false
        }
        val h = hole
        val inTip = e.x >= tip.translationX && e.x <= tip.translationX + tip.width &&
            e.y >= tip.translationY && e.y <= tip.translationY + tip.height
        if (!inTip && h != null && h.contains(e.x, e.y)) {
            // Tombol asli disentuh: biarkan bekerja (pilihan a), panduan menunggu
            if (e.actionMasked == MotionEvent.ACTION_DOWN) pause()
            return false
        }
        super.dispatchTouchEvent(e)
        return true // di luar tombol: tidak diteruskan
    }

    private fun pause() {
        paused = true
        lostFocus = false
        tip.visibility = View.GONE
        setHand(false)
        invalidate()
        val st = steps[idx]
        // Tunggu: fitur terbuka (jendela lain mengambil fokus) lalu ditutup, atau kejadian dari aplikasi
        main.postDelayed({
            if (!paused || lostFocus) return@postDelayed
            when {
                st.event != null || st.needsAction -> resumeChip.visibility = View.VISIBLE
                else -> resume(advance = true)
            }
        }, 1500)
        // Langkah tekan lama: kalau hanya diketuk, tampilkan lagi langkah yang sama
        if (st.hand) main.postDelayed({ if (paused && !lostFocus) resume(advance = false) }, 1600)
    }

    private fun resume(advance: Boolean) {
        if (!paused) return
        main.removeCallbacksAndMessages(null)
        paused = false
        if (advance) next() else show()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!paused) return
        if (!hasWindowFocus) lostFocus = true
        else if (lostFocus) main.postDelayed({ resume(advance = true) }, 300)
    }
}
