package com.raxvaayan.hukum

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("hukum", Context.MODE_PRIVATE)
    }

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)

    var baseUrl: String
        get() = s("baseUrl", "https://api.groq.com/openai/v1")
        set(v) = sp.edit().putString("baseUrl", v.trim()).apply()
    var apiKey: String
        get() = s("apiKey", "")
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()
    var model: String
        get() = s("model", "llama-3.3-70b-versatile")
        set(v) = sp.edit().putString("model", v.trim()).apply()
    var userName: String
        get() = s("userName", "Ravi")
        set(v) = sp.edit().putString("userName", v.trim()).apply()
    /** hi-IN, gu-IN, en-IN */
    var lang: String
        get() = s("lang", "hi-IN")
        set(v) = sp.edit().putString("lang", v).apply()
    var wakeWord: Boolean
        get() = b("wakeWord", false)
        set(v) = sp.edit().putBoolean("wakeWord", v).apply()
    var autoConfirm: Boolean
        get() = b("autoConfirm", false)
        set(v) = sp.edit().putBoolean("autoConfirm", v).apply()
    var speakReplies: Boolean
        get() = b("speakReplies", true)
        set(v) = sp.edit().putBoolean("speakReplies", v).apply()
    /** com.whatsapp or com.whatsapp.w4b */
    var waPkg: String
        get() = s("waPkg", "com.whatsapp")
        set(v) = sp.edit().putString("waPkg", v).apply()
    var dhanToken: String
        get() = s("dhanToken", "")
        set(v) = sp.edit().putString("dhanToken", v.trim()).apply()
    var bubble: Boolean
        get() = b("bubble", false)
        set(v) = sp.edit().putBoolean("bubble", v).apply()
}
