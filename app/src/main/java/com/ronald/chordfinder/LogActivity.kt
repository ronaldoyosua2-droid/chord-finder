package com.ronald.chordfinder

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** ⋮ → 🧾 Log aplikasi: lihat, saring, hapus, dan bagikan log. */
class LogActivity : AppCompatActivity() {
    private var filter = 0 // 0 semua, 1 AI tempo, 2 error
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private val chips = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.apply(this)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = vbox().apply { setBackgroundColor(C.BG) }

        val top = hbox().apply { setPadding(dp(4), dp(10), dp(12), dp(8)) }
        top.addView(label("←", 22f, C.TEXT).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        })
        top.addView(label("🧾 Log aplikasi", 19f, C.TEXT, bold = true), lp(0, WRAP, 1f))
        top.addView(pill("⤴ Bagikan") { share() }.apply { setPill(true) })
        root.addView(top)

        val row = hbox().apply { setPadding(dp(12), 0, dp(12), dp(10)) }
        listOf("Semua", "AI tempo", "Error").forEachIndexed { i, t ->
            val c = pill(t) { filter = i; refresh() }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
            chips.add(c)
            row.addView(c, lp(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        row.addView(pill("🗑 Hapus") { confirmClear() }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(C.TEXT)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            addView(body)
            background = rounded(C.BG, dpf(12f), dp(1), C.LINE)
        }
        root.addView(scroll, lp(MATCH, 0, 1f).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        setContentView(root.apply { fitSystemBars() })
        refresh()
    }

    /** Baris log dikelompokkan: baris lanjutan (detail crash, diawali spasi) ikut baris sebelumnya. */
    private fun entries(): List<String> {
        val out = ArrayList<String>()
        for (l in AppLog.lines()) {
            if (l.startsWith(" ") && out.isNotEmpty()) out[out.size - 1] = out.last() + "\n" + l else out.add(l)
        }
        return out
    }

    private fun level(e: String) = e.split(' ').getOrNull(2).orEmpty()

    private fun shown(): List<String> = entries().filter {
        when (filter) {
            1 -> level(it) == AppLog.AI
            2 -> level(it) == AppLog.ERROR || level(it) == AppLog.CRASH
            else -> true
        }
    }

    private fun refresh() {
        chips.forEachIndexed { i, c -> c.setPill(i == filter); c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        val sb = SpannableStringBuilder()
        sb.append(AppLog.header(), ForegroundColorSpan(C.MUTED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val list = shown()
        if (list.isEmpty()) {
            sb.append("\n\n(Belum ada catatan.)", ForegroundColorSpan(C.MUTED), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (e in list) {
            sb.append('\n')
            val parts = e.split(' ', limit = 4)
            if (parts.size < 4) { sb.append(e); continue }
            val s0 = sb.length
            sb.append(parts[0]).append(' ').append(parts[1])
            sb.setSpan(ForegroundColorSpan(C.MUTED), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(' ')
            val col = when (parts[2]) {
                AppLog.ERROR, AppLog.CRASH -> 0xFFFF6B6B.toInt()
                AppLog.WARN -> C.CHORD
                else -> C.NUMBER
            }
            val l0 = sb.length
            sb.append(parts[2])
            sb.setSpan(ForegroundColorSpan(col), l0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(' ').append(parts[3])
        }
        body.text = sb
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Hapus log?")
            .setMessage("Semua catatan di log aplikasi akan dihapus.")
            .setPositiveButton("Hapus") { _, _ -> AppLog.clear(); refresh() }
            .setNegativeButton("Batal", null).show()
    }

    private fun share() {
        val text = (listOf(AppLog.header()) + shown()).joinToString("\n")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Log Chord Finder")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Bagikan log"))
    }
}
