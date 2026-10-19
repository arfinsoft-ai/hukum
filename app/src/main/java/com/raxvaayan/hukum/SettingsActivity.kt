package com.raxvaayan.hukum

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var box: LinearLayout
    private val checks = ArrayList<() -> Unit>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        box = vbox().apply { setPadding(dp(16), dp(16), dp(16), dp(40)) }
        val root = ScrollView(this).apply { setBackgroundColor(C.BG); fitsSystemWindows = true; addView(box) }
        setContentView(root)

        box.addView(text("⚙️ Settings", 22f, C.GOLD, true))

        // ---- Phone setup ----
        section("📱 Phone setup (ek baar)")
        note("OnePlus pe sideloaded app ka Accessibility toggle grey ho to pehle step 0 karo: App info → ⋮ (upar right) → \"Allow restricted settings\".")
        setupRow("0. App info (restricted settings / auto-launch)", { true }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        setupRow("1. Permissions — contacts, call, mic, SMS, location, calendar", { perms().all { has(it) } }) {
            requestPermissions(perms(), 1)
        }
        setupRow("2. Accessibility — WhatsApp/Gmail auto-send + Blinkit", { HukumAccess.isOn() }) {
            Toast.makeText(this, "Installed apps → HUKUM Auto-Send → ON", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setupRow("3. Notification access — WhatsApp reply", { notifOn() }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        setupRow("4. Battery — Unrestricted (warna OnePlus band kar dega)", { batteryOk() }) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        // ---- AI ----
        section("🧠 AI (OpenAI-compatible)")
        val url = field("Base URL", Prefs.baseUrl)
        val key = field("API key", Prefs.apiKey, secret = true)
        val model = field("Model", Prefs.model)
        val presets = hbox()
        presets.addView(btn("Groq", C.CARD, C.TEXT) {
            url.setText("https://api.groq.com/openai/v1"); model.setText("llama-3.3-70b-versatile")
        }, lp(0, WRAP, 1f))
        presets.addView(btn("Gemini", C.CARD, C.TEXT) {
            url.setText("https://generativelanguage.googleapis.com/v1beta/openai"); model.setText("gemini-2.5-flash")
        }, lp(0, WRAP, 1f).apply { leftMargin = dp(6) })
        presets.addView(btn("OpenRouter", C.CARD, C.TEXT) {
            url.setText("https://openrouter.ai/api/v1"); model.setText("google/gemini-2.5-flash")
        }, lp(0, WRAP, 1f).apply { leftMargin = dp(6) })
        box.addView(presets, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        val name = field("Aapka naam", Prefs.userName)

        // ---- Voice ----
        section("🎤 Voice")
        val langs = listOf("hi-IN" to "हिंदी", "gu-IN" to "ગુજરાતી", "en-IN" to "English")
        val langRow = hbox()
        val langBtns = ArrayList<TextView>()
        fun paintLang() = langBtns.forEachIndexed { i, t ->
            val on = langs[i].first == Prefs.lang
            t.background = round(if (on) C.GOLD else C.CARD, dp(10).toFloat())
            t.setTextColor(if (on) android.graphics.Color.BLACK else C.TEXT)
        }
        langs.forEachIndexed { i, (code, label) ->
            val t = btn(label, C.CARD, C.TEXT) { Prefs.lang = code; paintLang() }
            langBtns.add(t)
            langRow.addView(t, lp(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        paintLang()
        box.addView(langRow, lp(MATCH, WRAP).apply { topMargin = dp(8) })

        toggle("Jawab bol ke sunao", Prefs.speakReplies) { Prefs.speakReplies = it }
        toggle("👂 \"Hey Hukum\" wake word (battery zyada)", Prefs.wakeWord) { on ->
            if (on && !has(Manifest.permission.RECORD_AUDIO)) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            }
            Prefs.wakeWord = on
            if (on) WakeService.start(this) else WakeService.stop(this)
        }
        note("Pehli baar ON karne pe ~42 MB Hindi model download hoga (offline chalega). \"Hukum\" bolo, app khul ke sunne lagega.")

        // ---- Behaviour ----
        section("🛡️ Safety")
        toggle("Bina confirm kiye bhejo (call/WhatsApp/email)", Prefs.autoConfirm) { Prefs.autoConfirm = it }
        val waRow = hbox()
        waRow.addView(text("WhatsApp app:", 14f, C.MUTED), lp(0, WRAP, 1f))
        val waBtn = btn(if (Prefs.waPkg == "com.whatsapp") "WhatsApp" else "Business", C.CARD, C.TEXT) {}
        waBtn.setOnClickListener {
            Prefs.waPkg = if (Prefs.waPkg == "com.whatsapp") "com.whatsapp.w4b" else "com.whatsapp"
            waBtn.text = if (Prefs.waPkg == "com.whatsapp") "WhatsApp" else "Business"
        }
        waRow.addView(waBtn)
        box.addView(waRow, lp(MATCH, WRAP).apply { topMargin = dp(10) })

        section("📈 Dhan (trading)")
        val dhan = field("Dhan access-token", Prefs.dhanToken, secret = true)

        section("🌅 Morning brief (roz)")
        toggle("Roz subah khud brief sunao (mausam, meetings, missed calls, news)", Prefs.briefOn) { Prefs.briefOn = it }
        val bt = field("Brief time (HH:mm)", Prefs.briefTime)

        section("🫧 Extras")
        toggle("Floating 👑 bubble (har app ke upar)", Prefs.bubble) {
            Prefs.bubble = it
            if (it) {
                if (Bubble.canDraw(this)) Bubble.start(this)
                else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else Bubble.stop(this)
        }

        box.addView(btn("💾 Save") {
            Prefs.baseUrl = url.text.toString(); Prefs.apiKey = key.text.toString()
            Prefs.model = model.text.toString(); Prefs.userName = name.text.toString().ifBlank { "Ravi" }
            Prefs.dhanToken = dhan.text.toString()
            Prefs.briefTime = bt.text.toString().ifBlank { "08:00" }
            More.scheduleBrief(this)
            Toast.makeText(this, "Saved ✅", Toast.LENGTH_SHORT).show()
            finish()
        }, lp(MATCH, WRAP).apply { topMargin = dp(24) })
    }

    override fun onResume() { super.onResume(); checks.forEach { it() } }

    private fun perms(): Array<String> {
        val p = arrayListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p.toTypedArray()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun notifOn() = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun section(t: String) = box.addView(text(t, 16f, C.TEXT, true), lp(MATCH, WRAP).apply { topMargin = dp(24); bottomMargin = dp(4) })
    private fun note(t: String) = box.addView(text(t, 12.5f, C.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(4) })

    private fun setupRow(label: String, ok: () -> Boolean, onClick: () -> Unit) {
        val row = hbox().apply {
            background = round(C.CARD, dp(12).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
        }
        val t = text(label, 14f)
        val s = text("", 16f)
        row.addView(t, lp(0, WRAP, 1f))
        row.addView(s)
        box.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        checks.add { s.text = if (ok()) "✅" else "➜" }
    }

    private fun field(label: String, value: String, secret: Boolean = false): EditText {
        box.addView(text(label, 12.5f, C.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val e = EditText(this).apply {
            setText(value); setTextColor(C.TEXT); textSize = 15f; isSingleLine = true
            background = round(C.CARD, dp(10).toFloat(), 1, C.LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(e, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        return e
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = label; isChecked = value; setTextColor(C.TEXT); textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        box.addView(sw, lp(MATCH, WRAP).apply { topMargin = dp(6) })
    }
}
