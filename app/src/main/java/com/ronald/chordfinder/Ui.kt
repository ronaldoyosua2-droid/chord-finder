package com.ronald.chordfinder

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Palet warna. Dua tema: Biasa (gelap) dan AMOLED (hitam pekat, kontras tinggi).
 * [amoled] diisi oleh [Theme.apply] sebelum tampilan dibangun.
 */
object C {
    @JvmStatic var amoled = true
    private fun c(normal: String, black: String) = Color.parseColor(if (amoled) black else normal)
    val BG get() = c("#0E0E13", "#000000")
    val SURFACE get() = c("#16161D", "#000000")
    val SURFACE2 get() = c("#20202A", "#0E0E0E")
    val LINE get() = c("#2C2C38", "#333333")
    val TEXT get() = c("#ECEAE4", "#FFFFFF")
    val MUTED get() = c("#9A978F", "#A9A9A9")
    val CHORD get() = c("#F5B342", "#FFC24A")
    val NUMBER get() = c("#6FD3C6", "#5FF2E0")
    val SECTION get() = c("#C9A7FF", "#D9B8FF")
    val STAR get() = CHORD
    val MOD get() = c("#FF8FA3", "#FF8FA3")
    val YT get() = c("#FF3B3B", "#FF4040")
    val NOTE get() = c("#8FC7FF", "#8FC7FF")
    val GO get() = c("#7BE495", "#8CFFA8")
    /** Lirik ditebalkan di tema AMOLED. */
    val LYRIC_STYLE get() = if (amoled) Typeface.BOLD else Typeface.NORMAL
}

/** Pilihan tema (⋮ → Tema). Bawaan: AMOLED. */
object Theme {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    fun isAmoled(ctx: Context) = sp(ctx).getBoolean("amoled", true)
    fun set(ctx: Context, amoled: Boolean) = sp(ctx).edit().putBoolean("amoled", amoled).apply()

    /** Panggil di onCreate sebelum super.onCreate. */
    fun apply(a: android.app.Activity) {
        C.amoled = isAmoled(a)
        a.setTheme(if (C.amoled) R.style.AppTheme_Amoled else R.style.AppTheme)
    }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.label(text: String, size: Float = 14f, color: Int = C.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Tombol pil kecil. */
fun Context.pill(text: String, active: Boolean = false, onClick: (View) -> Unit) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setPadding(dp(14), dp(8), dp(14), dp(8))
    minWidth = dp(40)
    isClickable = true
    isFocusable = true
    setPill(active)
    setOnClickListener(onClick)
}

fun TextView.setPill(active: Boolean) {
    val r = context.dpf(18f)
    background = if (active) rounded(C.CHORD, r) else rounded(C.SURFACE2, r, context.dp(1), C.LINE)
    setTextColor(if (active) C.BG else C.TEXT)
    setTypeface(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
}

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    val p = layoutParams as? LinearLayout.LayoutParams ?: lp(WRAP, WRAP)
    p.setMargins(l, t, r, b)
    layoutParams = p
    return this
}

/** Rapikan judul halaman web: buang "Chord", "Lirik", nama situs, dll. */
fun cleanTitle(raw: String): String {
    var t = raw.trim()
    t = t.replace(Regex("\\s*[|–—-]\\s*(JRChord|PsalmNote|Psalmnote|psalmnote\\.com|jrchord\\.com)\\s*$", RegexOption.IGNORE_CASE), "")
    t = t.replace(Regex("^(chord|kunci gitar|kunci|lirik( dan| &)? chord|chord( dan| &)? lirik|lirik)\\s+(lagu\\s+)?", RegexOption.IGNORE_CASE), "")
    t = t.replace(Regex("\\s+(chords?\\s*(&|and|dan)\\s*lyrics?|lirik\\s*(&|dan)\\s*chord)\\b", RegexOption.IGNORE_CASE), "")
    t = t.replace(Regex("\\s+(chord|chords|lirik)$", RegexOption.IGNORE_CASE), "")
    return t.trim().ifEmpty { raw.trim() }
}

/**
 * Android 15+ menggambar aplikasi sampai ke balik status bar & navigasi (edge-to-edge).
 * Beri jarak sesuai tinggi status bar, tombol navigasi, notch, dan keyboard.
 */
fun View.fitSystemBars() {
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val t = androidx.core.view.WindowInsetsCompat.Type.systemBars() or
            androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
            androidx.core.view.WindowInsetsCompat.Type.ime()
        val b = insets.getInsets(t)
        v.setPadding(b.left, b.top, b.right, b.bottom)
        androidx.core.view.WindowInsetsCompat.CONSUMED
    }
    androidx.core.view.ViewCompat.requestApplyInsets(this)
}

/** Lencana kecil berwarna (mis. "EKSPERIMENTAL") di dalam teks. */
class BadgeSpan(private val bg: Int, private val fg: Int, private val scale: Float = 0.62f) :
    android.text.style.ReplacementSpan() {
    private fun small(p: android.graphics.Paint) = android.graphics.Paint(p).apply {
        textSize = p.textSize * scale
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    override fun getSize(p: android.graphics.Paint, t: CharSequence, s: Int, e: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        val sp = small(p)
        return (sp.measureText(t, s, e) + sp.textSize * 1.1f).toInt()
    }
    override fun draw(c: android.graphics.Canvas, t: CharSequence, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: android.graphics.Paint) {
        val sp = small(p)
        val padX = sp.textSize * 0.55f
        val w = sp.measureText(t, s, e) + padX * 2
        val h = sp.textSize * 1.45f
        val cy = y + (p.ascent() + p.descent()) / 2f
        val r = android.graphics.RectF(x, cy - h / 2, x + w, cy + h / 2)
        c.drawRoundRect(r, h * 0.3f, h * 0.3f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        sp.color = fg
        c.drawText(t, s, e, x + padX, cy - (sp.ascent() + sp.descent()) / 2f, sp)
    }
}

/** Tambahkan lencana EKSPERIMENTAL (atau [word]) di akhir [sb]. */
fun android.text.SpannableStringBuilder.appendExp(word: String = "EKSPERIMENTAL"): android.text.SpannableStringBuilder {
    append("  ")
    val s = length
    append(word)
    setSpan(BadgeSpan(C.SECTION, 0xFF000000.toInt()), s, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return this
}

/** Judul dengan lencana EKSPERIMENTAL. */
fun Context.expTitle(text: String, size: Float = 17f): TextView =
    label("", size, C.TEXT, bold = true).apply {
        this.text = android.text.SpannableStringBuilder(text).appendExp()
    }

/** Catatan kecil di bawah judul fitur eksperimental. */
fun Context.expNote(): TextView =
    label("Masih diuji, hasilnya bisa meleset. Kalau ada yang salah, kabari pembuat.", 12f, C.MUTED)

/** Latar lembar/dialog. Di tema AMOLED diberi garis tepi supaya terlihat di atas hitam. */
fun Context.sheetBg(radius: Float) =
    if (C.amoled) rounded(C.SURFACE, dpf(radius), dp(1), C.LINE) else rounded(C.SURFACE, dpf(radius))
