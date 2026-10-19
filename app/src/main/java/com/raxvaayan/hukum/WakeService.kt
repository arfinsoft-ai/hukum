package com.raxvaayan.hukum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** Offline "Hey Hukum" wake word using a small Vosk Hindi model (downloaded once, ~42 MB). */
class WakeService : Service(), RecognitionListener {

    companion object {
        @Volatile var inst: WakeService? = null
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"
        private val WORDS = listOf("हुकुम", "हुकम", "हुकूम", "हुक्म", "hukum", "hukam")

        fun start(c: Context) = c.startForegroundService(Intent(c, WakeService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, WakeService::class.java))
        fun pause() { inst?.let { s -> s.h.post { s.stopRec() } } }
        fun resume() { inst?.let { s -> s.h.postDelayed({ s.startRec() }, 600) } }
    }

    private val h = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var lastHit = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("wake", "Hey Hukum", NotificationManager.IMPORTANCE_LOW))
        val n = notif("Model taiyaar ho raha hai…")
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        thread {
            try {
                val dir = ensureModel()
                model = Model(dir.absolutePath)
                h.post { update("Sun raha hoon — bolo \"Hukum\""); startRec() }
            } catch (e: Exception) {
                h.post { update("Wake word error: ${e.message}") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        stopRec(); model?.close(); model = null; inst = null
        super.onDestroy()
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "wake")
            .setContentTitle("HUKUM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun update(t: String) = getSystemService(NotificationManager::class.java).notify(1, notif(t))

    private fun ensureModel(): File {
        val dir = File(filesDir, "vosk-hi")
        if (File(dir, "ready").exists()) return dir
        h.post { update("Hindi model download ho raha hai (~42 MB)…") }
        dir.deleteRecursively(); dir.mkdirs()
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 60000
        ZipInputStream(conn.inputStream.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(dir, rel)
                if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); FileOutputStream(out).use { zin.copyTo(it) } }
            }
        }
        File(dir, "ready").writeText("ok")
        return dir
    }

    fun startRec() {
        if (speech != null) return
        val m = model ?: return
        try {
            speech = SpeechService(Recognizer(m, 16000f), 16000f).also { it.startListening(this) }
        } catch (e: Exception) {
            update("Mic busy: ${e.message}")
        }
    }

    fun stopRec() {
        speech?.let { it.stop(); it.shutdown() }
        speech = null
    }

    private fun check(json: String?, key: String) {
        val t = try { JSONObject(json ?: return).optString(key) } catch (e: Exception) { return }
        if (t.isBlank() || WORDS.none { t.contains(it, true) }) return
        val now = System.currentTimeMillis()
        if (now - lastHit < 4000) return
        lastHit = now
        stopRec()
        val i = Intent(this, MainActivity::class.java)
            .putExtra("wake", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        // Background activity starts are allowed from a bound accessibility service.
        val launcher: Context = HukumAccess.inst ?: this
        try { launcher.startActivity(i) } catch (e: Exception) { resume() }
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { stopRec(); resume() }
    override fun onTimeout() { stopRec(); resume() }
}
