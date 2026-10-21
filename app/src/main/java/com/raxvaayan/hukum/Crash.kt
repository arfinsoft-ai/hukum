package com.raxvaayan.hukum

import android.content.Context
import java.io.File

/** Saves any crash stack to a file; MainActivity shows it on next open so it can be reported. */
object Crash {
    @Volatile private var installed = false
    fun install(c: Context) {
        if (installed) return; installed = true
        val f = File(c.applicationContext.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText("v${c.packageManager.getPackageInfo(c.packageName, 0).versionName} [${t.name}]\n" + android.util.Log.getStackTraceString(e).take(4000)) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
    /** If the last run crashed, replace the screen with the error + Copy button. Returns true when shown. */
    fun showIfAny(a: android.app.Activity): Boolean {
        val err = take(a) ?: return false
        try { a.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("hukum crash", err)) } catch (_: Exception) {}
        val box = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(40, 80, 40, 40); setBackgroundColor(0xFF0E0F13.toInt()) }
        box.addView(android.widget.TextView(a).apply { text = "⚠️ HUKUM crash hua tha\nError clipboard mein copy ho gaya — Claude ko paste karo.\n"; setTextColor(0xFFF5B83D.toInt()); textSize = 17f })
        box.addView(android.widget.TextView(a).apply { text = err; setTextColor(0xFFECEDF0.toInt()); textSize = 11f; setTextIsSelectable(true) })
        box.addView(android.widget.Button(a).apply { text = "OK — app kholo"; setOnClickListener { a.recreate() } })
        a.setContentView(android.widget.ScrollView(a).apply { addView(box) })
        return true
    }

    fun take(c: Context): String? {
        val f = File(c.filesDir, "crash.txt"); if (!f.exists()) return null
        return try { f.readText() } finally { f.delete() }
    }
}
