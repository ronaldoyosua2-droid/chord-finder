package com.ronald.chordfinder

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log aplikasi: tersimpan di HP saja (filesDir/applog.txt), maksimal 500 baris terakhir.
 * Tidak memuat lirik, isi chord, atau suara.
 */
object AppLog {
    const val MAX = 500
    const val INFO = "INFO"
    const val WARN = "PERINGATAN"
    const val ERROR = "ERROR"
    const val CRASH = "CRASH"
    const val AI = "AI"

    private var file: File? = null
    private var header = ""
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "applog.txt")
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        header = "HP: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · v$ver"
    }

    fun header() = header

    fun i(msg: String) = write(INFO, msg)
    fun w(msg: String) = write(WARN, msg)
    fun e(msg: String, t: Throwable? = null) = write(ERROR, msg + (t?.let { ": ${it.javaClass.simpleName} ${it.message.orEmpty()}" } ?: ""))
    fun ai(msg: String) = write(AI, msg)

    fun crash(t: Throwable) {
        val sb = StringBuilder("${t.javaClass.name}: ${t.message.orEmpty()}")
        for (el in t.stackTrace.take(12)) sb.append("\n    at ").append(el.toString())
        t.cause?.let { sb.append("\n    sebab: ${it.javaClass.name}: ${it.message.orEmpty()}") }
        write(CRASH, sb.toString())
    }

    @Synchronized
    private fun write(level: String, msg: String) {
        val f = file ?: return
        try {
            f.appendText("${fmt.format(Date())} $level ${msg.replace("\r", "")}\n")
            // pangkas supaya tetap ≤ MAX baris (sesekali saja, bukan setiap tulis)
            if (f.length() > 120_000) trim(f)
        } catch (e: Exception) { }
    }

    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size > MAX) f.writeText(lines.takeLast(MAX).joinToString("\n", postfix = "\n"))
    }

    /** Semua baris (lama → baru), paling banyak [MAX]. Baris lanjutan crash diawali spasi. */
    @Synchronized
    fun lines(): List<String> {
        val f = file ?: return emptyList()
        return try { f.readLines().filter { it.isNotEmpty() }.takeLast(MAX) } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    fun clear() { try { file?.writeText("") } catch (e: Exception) { } }
}

/** Menyalakan log dan pencatat crash saat aplikasi dibuka. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try { AppLog.crash(ex) } catch (e: Throwable) { }
            prev?.uncaughtException(th, ex)
        }
    }
}
