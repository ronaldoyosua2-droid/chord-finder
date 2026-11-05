package com.ronald.chordfinder

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.ronald.chordfinder.music.ReleaseText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Release(val tag: String, val build: Int, val body: String, val apkUrl: String, val size: Long)

/** Cek & pasang update dari halaman Releases GitHub. */
object Updater {
    private const val API = "https://api.github.com/repos/ronaldoyosua2-droid/chord-finder/releases/latest"
    private const val DAY = 24L * 60 * 60 * 1000
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingApk: File? = null

    fun currentBuild(a: AppCompatActivity): Int {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    fun versionName(a: AppCompatActivity): String = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?"

    private fun sp(a: AppCompatActivity) = a.getSharedPreferences("prefs", AppCompatActivity.MODE_PRIVATE)

    /** Dipanggil saat aplikasi dibuka: maksimal sekali sehari. */
    fun autoCheck(a: AppCompatActivity) {
        val last = sp(a).getLong("updCheck", 0)
        if (System.currentTimeMillis() - last < DAY) return
        check(a, manual = false)
    }

    fun check(a: AppCompatActivity, manual: Boolean) {
        if (manual) Toast.makeText(a, "Mengecek update…", Toast.LENGTH_SHORT).show()
        pool.execute {
            val r = try { fetch() } catch (e: Exception) { AppLog.e("Gagal cek update", e); null }
            main.post {
                if (a.isFinishing) return@post
                if (r == null) {
                    if (manual) Toast.makeText(a, "Gagal mengecek update. Periksa internet.", Toast.LENGTH_LONG).show()
                    return@post
                }
                sp(a).edit().putLong("updCheck", System.currentTimeMillis()).apply()
                val cur = currentBuild(a)
                AppLog.i("Cek update: terbaru di GitHub ${r.tag}, terpasang v${versionName(a)}")
                val skipped = sp(a).getInt("updSkip", 0)
                when {
                    r.build <= cur -> if (manual) Toast.makeText(a, "Sudah versi terbaru (v${versionName(a)})", Toast.LENGTH_LONG).show()
                    !manual && r.build == skipped -> {}
                    else -> showCard(a, r)
                }
            }
        }
    }

    private fun fetch(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(text)
        val tag = o.optString("tag_name")
        val build = ReleaseText.buildOf(tag) ?: return null
        val assets = o.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until assets.length()) {
            val x = assets.getJSONObject(i)
            if (x.optString("name").endsWith(".apk")) {
                return Release(tag, build, o.optString("body"), x.optString("browser_download_url"), x.optLong("size"))
            }
        }
        return null
    }

    private fun mb(b: Long) = String.format("%.1f MB", b / 1048576.0).replace('.', ',')

    private fun showCard(a: AppCompatActivity, r: Release) {
        val dlg = Dialog(a)
        val root = a.vbox().apply {
            setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(12))
            background = rounded(C.SURFACE, a.dpf(20f), a.dp(1), C.CHORD)
        }
        root.addView(a.label("✨ Update tersedia", 18f, C.CHORD, bold = true))
        val v = SpannableStringBuilder("v${versionName(a)} → ")
        val s0 = v.length
        v.append(r.tag)
        v.setSpan(StyleSpan(Typeface.BOLD), s0, v.length, 0)
        v.setSpan(ForegroundColorSpan(C.TEXT), s0, v.length, 0)
        if (r.size > 0) v.append(" · ${mb(r.size)}")
        root.addView(a.label("", 13f, C.MUTED).apply { text = v; setPadding(0, a.dp(2), 0, a.dp(10)) })
        val wn = ReleaseText.plain(ReleaseText.whatsNew(r.body))
        if (wn.isNotEmpty()) {
            val box = a.vbox().apply {
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
                background = rounded(C.BG, a.dpf(12f))
            }
            box.addView(a.label("WHAT'S NEW", 11.5f, C.SECTION, bold = true).apply { letterSpacing = 0.05f })
            box.addView(a.label(wn, 13f, C.TEXT).apply { setLineSpacing(0f, 1.15f); setPadding(0, a.dp(4), 0, 0) })
            root.addView(ScrollView(a).apply { addView(box) },
                lp(MATCH, WRAP).apply { height = minOf(a.resources.displayMetrics.heightPixels / 3, a.dp(260)) })
        }
        val btns = a.hbox().apply { setPadding(0, a.dp(12), 0, 0) }
        btns.addView(a.pill("Nanti") { dlg.dismiss() }, lp(0, WRAP, 1f))
        btns.addView(a.label("⬇ Update sekarang", 14f, 0xFF0B2A14.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, a.dp(10), 0, a.dp(10))
            background = rounded(C.GO, a.dpf(18f))
            isClickable = true
            setOnClickListener { dlg.dismiss(); download(a, r) }
        }, lp(0, WRAP, 1f).apply { leftMargin = a.dp(8) })
        root.addView(btns)
        root.addView(a.label("Lewati versi ini", 12.5f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, a.dp(10), 0, a.dp(2))
            setOnClickListener {
                sp(a).edit().putInt("updSkip", r.build).apply()
                dlg.dismiss()
            }
        }, lp(MATCH, WRAP))
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    private fun download(a: AppCompatActivity, r: Release) {
        val dlg = Dialog(a)
        val root = a.vbox().apply {
            setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
            background = rounded(C.SURFACE, a.dpf(20f), a.dp(1), C.CHORD)
        }
        root.addView(a.label("⬇ Mengunduh ${r.tag}", 17f, C.CHORD, bold = true))
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(bar, lp(MATCH, WRAP).apply { topMargin = a.dp(12) })
        val info = a.label("", 12.5f, C.MUTED)
        root.addView(info)
        dlg.setContentView(root)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
        val dir = File(a.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "ChordFinder-${r.tag}.apk")
        pool.execute {
            var ok = false
            try {
                var url = URL(r.apkUrl)
                var c: HttpURLConnection
                var hops = 0
                while (true) {
                    c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    val code = c.responseCode
                    if (code in 300..399 && hops < 5) {
                        url = URL(url, c.getHeaderField("Location")); c.disconnect(); hops++; continue
                    }
                    break
                }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                var got = 0L
                c.inputStream.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(32 * 1024)
                        var lastUi = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            o.write(buf, 0, n); got += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 100) {
                                lastUi = now
                                val g = got
                                main.post {
                                    if (total > 0) bar.progress = (g * 1000 / total).toInt()
                                    info.text = "${mb(g)} / ${mb(total)}" + if (total > 0) " · ${g * 100 / total}%" else ""
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                ok = total <= 0 || got >= total
            } catch (e: Exception) { ok = false; AppLog.e("Gagal unduh update", e) }
            main.post {
                dlg.dismiss()
                if (!ok) {
                    Toast.makeText(a, "Unduhan gagal. Coba lagi lewat ⋮ → Cek update.", Toast.LENGTH_LONG).show()
                } else install(a, out)
            }
        }
    }

    private fun install(a: AppCompatActivity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            AlertDialog.Builder(a)
                .setTitle("Izinkan pemasangan")
                .setMessage("Android perlu izin \"Instal aplikasi tak dikenal\" untuk Chord Finder. Aktifkan di layar berikut, lalu kembali ke aplikasi.")
                .setPositiveButton("Buka pengaturan") { _, _ ->
                    a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                }
                .setNegativeButton("Batal") { _, _ -> pendingApk = null }
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", apk)
        a.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Kembali dari pengaturan izin: lanjutkan pemasangan. */
    fun onResume(a: AppCompatActivity) {
        val apk = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) return
        pendingApk = null
        install(a, apk)
    }

    // ------------------------------------------------------ Apa yang baru

    fun hasUnreadNews(a: AppCompatActivity) = sp(a).getInt("newsSeen", 0) < currentBuild(a)

    fun showHistory(a: AppCompatActivity, done: () -> Unit) {
        sp(a).edit().putInt("newsSeen", currentBuild(a)).apply()
        val arr = try { JSONArray(a.assets.open("changelog.json").bufferedReader().readText()) } catch (e: Exception) { JSONArray() }
        val cur = versionName(a)
        val dlg = Dialog(a)
        val root = a.vbox().apply {
            setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(12))
            background = a.sheetBg(20f)
        }
        root.addView(a.label("✨ Apa yang baru", 18f, C.TEXT, bold = true))
        root.addView(a.label("Versi terpasang: v$cur", 12.5f, C.MUTED).apply { setPadding(0, a.dp(2), 0, a.dp(10)) })
        val list = a.vbox()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val isCur = o.optString("v") == cur
            val sb = SpannableStringBuilder("v" + o.optString("v"))
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, 0)
            sb.setSpan(ForegroundColorSpan(if (isCur) C.CHORD else C.TEXT), 0, sb.length, 0)
            val d0 = sb.length
            sb.append("  ").append(o.optString("date"))
            sb.setSpan(ForegroundColorSpan(C.MUTED), d0, sb.length, 0)
            sb.setSpan(RelativeSizeSpan(0.82f), d0, sb.length, 0)
            if (isCur) {
                val t0 = sb.length
                sb.append("  TERPASANG")
                sb.setSpan(ForegroundColorSpan(C.CHORD), t0, sb.length, 0)
                sb.setSpan(RelativeSizeSpan(0.72f), t0, sb.length, 0)
                sb.setSpan(StyleSpan(Typeface.BOLD), t0, sb.length, 0)
            }
            sb.append('\n')
            val items = o.optJSONArray("items") ?: JSONArray()
            for (j in 0 until items.length()) {
                val it = items.getJSONArray(j)
                val kind = it.optString(0)
                sb.append("• ")
                val k0 = sb.length
                sb.append(if (kind == "fix") "Fix " else "New ")
                sb.setSpan(ForegroundColorSpan(if (kind == "fix") C.NUMBER else C.GO), k0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), k0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(it.optString(1)).append('\n')
            }
            list.addView(TextView(a).apply {
                text = sb
                setTextColor(0xFFD6D3CC.toInt())
                textSize = 13.5f
                setLineSpacing(0f, 1.15f)
                setPadding(a.dp(12), 0, 0, a.dp(12))
                background = android.graphics.drawable.LayerDrawable(arrayOf(
                    android.graphics.drawable.ColorDrawable(if (isCur) C.CHORD else C.LINE)
                )).apply { setLayerSize(0, a.dp(3), 0); setLayerGravity(0, Gravity.START or Gravity.FILL_VERTICAL) }
            })
        }
        root.addView(ScrollView(a).apply { addView(list) }, lp(MATCH, (a.resources.displayMetrics.heightPixels * 0.6).toInt()))
        root.addView(a.label("Tutup", 15f, C.BG, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, a.dp(12), 0, a.dp(12))
            background = rounded(C.CHORD, a.dpf(16f))
            isClickable = true
            setOnClickListener { dlg.dismiss() }
        }, lp(MATCH, WRAP).apply { topMargin = a.dp(10) })
        dlg.setOnDismissListener { done() }
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    fun showAbout(a: AppCompatActivity) {
        AlertDialog.Builder(a)
            .setTitle("Chord Finder")
            .setMessage("Versi v${versionName(a)}\n\n" +
                "Fitur EKSPERIMENTAL (masih diuji):\n" +
                "• Metronom (termasuk 🎤 Deteksi tempo dan ☰ Urutan lagu)\n" +
                "• ♩ Sinkron ke video YouTube\n" +
                "• Saran modulasi otomatis\n" +
                "Hasilnya bisa meleset. Kalau ada yang salah, kirim SS ke pembuat.\n\n" +
                "© 2026 Ronald\ngithub.com/ronaldoyosua2-droid/chord-finder")
            .setPositiveButton("OK", null)
            .show()
    }
}
