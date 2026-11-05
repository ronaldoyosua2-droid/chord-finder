package com.ronald.chordfinder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import com.ronald.chordfinder.music.Resample

/**
 * Mengambil suara yang sedang diputar di HP (Android 10+), mis. video YouTube di Chord Finder.
 * Wajib dari Android: izin "Mulai merekam…" setiap kali + notifikasi selama mendengar.
 * Suara hanya disimpan di memori lalu diberikan ke [listener]; tidak ditulis ke file.
 */
@RequiresApi(29)
class CaptureService : Service() {
    interface Listener {
        fun progress(p: Float, secondsLeft: Int)
        fun done(audio22k: FloatArray?, error: String?)
    }

    companion object {
        private const val CH = "capture"
        private const val NID = 41
        var listener: Listener? = null
        @Volatile private var cancel = false

        fun start(ctx: Context, resultCode: Int, data: Intent, seconds: Int) {
            cancel = false
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java)
                .putExtra("code", resultCode).putExtra("data", data).putExtra("sec", seconds))
        }

        fun stop() { cancel = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        val sec = intent?.getIntExtra("sec", 15) ?: 15
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { if (data != null) mpm.getMediaProjection(code, data) else null } catch (e: Exception) {
            AppLog.e("Ambil suara HP: izin tidak bisa dipakai", e); null
        }
        if (p == null) { finish(null, "Izin mengambil suara HP tidak didapat."); return START_NOT_STICKY }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { cancel = true }
        }, main)
        Thread { record(p, sec) }.start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Deteksi tempo", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Chord Finder")
            .setContentText("Mendengarkan tempo…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NID, n)
    }

    @SuppressLint("MissingPermission")
    private fun record(p: MediaProjection, sec: Int) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        var rec: AudioRecord? = null
        var rate = 0
        for (r in intArrayOf(22050, 44100, 48000)) {
            try {
                val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val a = AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(min, 8192) * 2)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                if (a.state == AudioRecord.STATE_INITIALIZED) { rec = a; rate = r; break }
                a.release()
            } catch (e: Exception) { AppLog.w("Ambil suara HP: $r Hz gagal (${e.javaClass.simpleName})") }
        }
        if (rec == null) { finish(null, "Suara HP tidak bisa diambil di HP ini."); return }
        AppLog.ai("Ambil suara HP: $rate Hz")
        val total = rate * sec
        val all = FloatArray(total)
        val buf = ShortArray(4096)
        var got = 0
        var lastSec = -1
        try {
            rec.startRecording()
            while (got < total && !cancel) {
                val n = rec.read(buf, 0, minOf(buf.size, total - got))
                if (n <= 0) break
                for (i in 0 until n) all[got + i] = buf[i] / 32768f
                got += n
                val left = ((total - got) / rate) + 1
                if (left != lastSec) {
                    lastSec = left
                    val pr = got.toFloat() / total
                    main.post { listener?.progress(pr, left) }
                }
            }
        } catch (e: Exception) {
            AppLog.e("Ambil suara HP terhenti", e)
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
        }
        if (cancel) { finish(null, null); return }
        val audio = Resample.to22k(all.copyOf(got), rate)
        finish(audio, null)
    }

    private fun finish(audio: FloatArray?, error: String?) {
        main.post {
            listener?.done(audio, error)
            try { projection?.stop() } catch (e: Exception) { }
            projection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
