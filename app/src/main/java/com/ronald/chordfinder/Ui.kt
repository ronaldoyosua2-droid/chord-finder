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

/** Palet gelap yang nyaman dibaca di atas stand keyboard. */
object C {
    val BG = Color.parseColor("#0E0E13")
    val SURFACE = Color.parseColor("#16161D")
    val SURFACE2 = Color.parseColor("#20202A")
    val LINE = Color.parseColor("#2C2C38")
    val TEXT = Color.parseColor("#ECEAE4")
    val MUTED = Color.parseColor("#9A978F")
    val CHORD = Color.parseColor("#F5B342")
    val NUMBER = Color.parseColor("#6FD3C6")
    val SECTION = Color.parseColor("#C9A7FF")
    val STAR = Color.parseColor("#F5B342")
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
    t = t.replace(Regex("\\s+(chord|chords|lirik)$", RegexOption.IGNORE_CASE), "")
    return t.trim().ifEmpty { raw.trim() }
}
