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
    fun take(c: Context): String? {
        val f = File(c.filesDir, "crash.txt"); if (!f.exists()) return null
        return try { f.readText() } finally { f.delete() }
    }
}
