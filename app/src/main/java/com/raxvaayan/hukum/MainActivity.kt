package com.raxvaayan.hukum

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class MainActivity : Activity(), ToolHost {

    override val ctx: Context get() = this
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var mic: TextView
    private lateinit var status: TextView
    private val history = JSONArray()
    @Volatile private var busy = false
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        buildUi()
        tts = TextToSpeech(this) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.forLanguageTag(Prefs.lang)
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { WakeService.resume() }
            @Deprecated("") override fun onError(id: String?) { WakeService.resume() }
        })
        bubble("Hukum kijiye, ${Prefs.userName} 🙏\nJaise: \"kal subah 6 baje alarm\", \"Amit ko WhatsApp karo ki meeting 4 baje hai\", \"mummy ko call karo\"", false)
        handleWake(intent)
    }

    override fun onResume() {
        super.onResume()
        tts?.language = Locale.forLanguageTag(Prefs.lang)
        refreshStatus()
        if (Prefs.wakeWord && WakeService.inst == null && hasPerm(Manifest.permission.RECORD_AUDIO)) WakeService.start(this)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleWake(i) }

    override fun onDestroy() { sr?.destroy(); tts?.shutdown(); super.onDestroy() }

    private fun handleWake(i: Intent?) {
        if (i?.getBooleanExtra("wake", false) != true) return
        i.removeExtra("wake")
        setShowWhenLocked(true); setTurnScreenOn(true)
        ui.postDelayed({ listen() }, 300)
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = vbox().apply { setBackgroundColor(C.BG); fitsSystemWindows = true }

        val top = hbox().apply { setPadding(dp(16), dp(12), dp(8), dp(12)) }
        val titleBox = vbox()
        titleBox.addView(text("👑 HUKUM", 20f, C.GOLD, true))
        status = text("", 12f, C.MUTED)
        titleBox.addView(status)
        top.addView(titleBox, lp(0, WRAP, 1f))
        top.addView(text("⚙️", 22f).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(top, lp(MATCH, WRAP))
        root.addView(View(this).apply { setBackgroundColor(C.LINE) }, lp(MATCH, 1))

        list = vbox().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, lp(MATCH, 0, 1f))

        val bar = hbox().apply { setPadding(dp(10), dp(8), dp(10), dp(10)) }
        input = EditText(this).apply {
            hint = "Hukum likho…"; setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 16f
            background = round(C.CARD, dp(22).toFloat(), 1, C.LINE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 4; imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, a, e ->
                if (a == EditorInfo.IME_ACTION_SEND || e?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false
            }
        }
        bar.addView(input, lp(0, WRAP, 1f))
        mic = circle("🎤") { if (sr != null) stopListen() else listen() }
        bar.addView(mic, lp(dp(48), dp(48)).apply { leftMargin = dp(8) })
        bar.addView(circle("➤") { send() }, lp(dp(48), dp(48)).apply { leftMargin = dp(6) })
        root.addView(bar, lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun circle(s: String, onClick: () -> Unit) = text(s, 20f, C.TEXT).apply {
        gravity = Gravity.CENTER; background = round(C.CARD, dp(24).toFloat(), 1, C.LINE)
        setOnClickListener { onClick() }
    }

    private fun refreshStatus() {
        val bits = ArrayList<String>()
        if (Prefs.apiKey.isBlank()) bits.add("⚠️ API key nahi")
        if (!HukumAccess.isOn()) bits.add("Auto-send OFF")
        if (!WaListener.connected) bits.add("Reply OFF")
        if (Prefs.wakeWord) bits.add("👂 Hey Hukum ON")
        status.text = if (bits.isEmpty()) "Sab ready ✅" else bits.joinToString(" · ")
    }

    private fun bubble(s: String, user: Boolean): TextView {
        val t = text(s, 15f, C.TEXT).apply {
            background = round(if (user) C.USER else C.CARD, dp(16).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextIsSelectable(true)
        }
        val p = lp(WRAP, WRAP).apply { topMargin = dp(6); gravity = if (user) Gravity.END else Gravity.START }
        t.maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        list.addView(t, p)
        scrollDown()
        return t
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    // ---------- Agent ----------
    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        input.setText("")
        run(t, voice = false)
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, hh:mm a", Locale.ENGLISH).format(Date())
        return """Tu HUKUM hai — ${Prefs.userName} ka personal Android phone assistant. User Hindi, Gujarati, English ya mix mein hukum dega.
Abhi: $now (India time).
Rules:
- Kaam ho sakta hai to TOOL CALL kar, sirf baat mat kar. Ek message mein multiple kaam ho to sab tools call kar.
- Tool result dekh ke 1-2 line mein jawab de, user ki bhasha mein (Hinglish theek hai). Fluff nahi.
- Contact ambiguous/nahi mila to user se seedha poochh.
- WhatsApp/email: user ne exact words diye to wahi bhej; warna ${Prefs.userName} ki taraf se natural, chhota message likh. Email professional ho, sign-off "${Prefs.userName}".
- "kal", "parso", "shaam" jaise words current time se calculate kar. Shaam = 18-19, raat = 21-22, subah = 6-8 agar time na bataya ho.
- Reply ke liye pehle whatsapp_read se sender ka exact naam dekh, phir whatsapp_reply.
- Jo kaam tools se nahi hota (Uber/Blinkit booking abhi nahi), batado aur open_app se app khol do."""
    }

    private fun trimHistory() {
        if (history.length() <= 30) return
        var start = history.length() - 30
        while (start < history.length() && history.getJSONObject(start).optString("role") != "user") start++
        val keep = JSONArray()
        for (i in start until history.length()) keep.put(history.get(i))
        while (history.length() > 0) history.remove(0)
        for (i in 0 until keep.length()) history.put(keep.get(i))
    }

    private fun run(text: String, voice: Boolean) {
        bubble(text, true)
        busy = true
        val thinking = bubble("…", false)
        history.put(JSONObject().put("role", "user").put("content", text))
        thread {
            var reply = ""
            try {
                for (step in 0 until 6) {
                    trimHistory()
                    val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
                    for (i in 0 until history.length()) msgs.put(history.get(i))
                    val m = Llm.chat(msgs, Tools.defs)
                    val calls = m.optJSONArray("tool_calls")
                    val content = if (m.isNull("content")) "" else m.optString("content").trim()
                    val clean = JSONObject().put("role", "assistant").put("content", content)
                    if (calls != null && calls.length() > 0) clean.put("tool_calls", calls)
                    history.put(clean)
                    if (calls == null || calls.length() == 0) { reply = content; break }
                    for (i in 0 until calls.length()) {
                        val c = calls.getJSONObject(i)
                        val f = c.getJSONObject("function")
                        val args = when (val a = f.opt("arguments")) {
                            is JSONObject -> a
                            is String -> try { JSONObject(a.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                            else -> JSONObject()
                        }
                        val name = f.getString("name")
                        ui.post { thinking.text = "⚙️ ${name.replace('_', ' ')}…" }
                        val result = Tools.run(this, name, args)
                        history.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", result))
                    }
                }
            } catch (e: Exception) {
                reply = "⚠️ ${e.message}"
            }
            val out = reply.ifBlank { "Ho gaya ✅" }
            ui.post {
                thinking.text = out
                busy = false
                scrollDown()
                if (voice || Prefs.speakReplies) speak(out) else WakeService.resume()
            }
        }
    }

    private fun speak(s: String) {
        val t = tts
        if (!ttsReady || t == null) { WakeService.resume(); return }
        WakeService.pause()
        t.speak(s.replace(Regex("[*_#`⚠️✅⚙️]"), ""), TextToSpeech.QUEUE_FLUSH, null, "r" + System.currentTimeMillis())
    }

    // ---------- ToolHost ----------
    override fun onUi(block: () -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var r = ""; var err: Exception? = null
        val latch = CountDownLatch(1)
        ui.post { try { r = block() } catch (e: Exception) { err = e }; latch.countDown() }
        latch.await()
        err?.let { throw it }
        return r
    }

    override fun confirm(title: String, detail: String): Boolean {
        var ok = false
        val latch = CountDownLatch(1)
        ui.post {
            val card = vbox().apply {
                background = round(C.CARD, dp(16).toFloat(), dp(1), C.GOLD)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(text(title, 15f, C.GOLD, true))
            card.addView(text(detail, 14f, C.TEXT).apply { setPadding(0, dp(6), 0, dp(10)) })
            val row = hbox()
            fun done(v: Boolean, label: String) {
                ok = v
                card.removeView(row)
                card.addView(text(label, 13f, if (v) C.GREEN else C.RED))
                latch.countDown()
            }
            row.addView(btn("✅ Haan, karo") { done(true, "✅ Confirmed") }, lp(0, WRAP, 1f))
            row.addView(btn("❌ Nahi", C.LINE, C.TEXT) { done(false, "❌ Cancelled") }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
            card.addView(row)
            list.addView(card, lp(MATCH, WRAP).apply { topMargin = dp(8) })
            scrollDown()
        }
        latch.await()
        return ok
    }

    // ---------- Voice ----------
    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun listen() {
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (busy) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { bubble("⚠️ Is phone pe speech recognition available nahi", false); return }
        WakeService.pause()
        tts?.stop()
        ui.postDelayed({ startRecognizer() }, 250)
    }

    private fun startRecognizer() {
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        sr = r
        mic.background = round(C.GOLD, dp(24).toFloat())
        input.hint = "Sun raha hoon…"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {
                p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input.setText(it) }
            }
            override fun onResults(p: Bundle?) {
                val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                stopListen(resumeWake = t.isEmpty())
                input.setText("")
                if (t.isNotEmpty()) run(t, voice = true)
            }
            override fun onError(e: Int) {
                stopListen()
                if (e != SpeechRecognizer.ERROR_NO_MATCH && e != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && e != SpeechRecognizer.ERROR_CLIENT)
                    bubble("⚠️ Voice error ($e)", false)
            }
        })
        r.startListening(i)
    }

    private fun stopListen(resumeWake: Boolean = true) {
        sr?.let { it.stopListening(); it.destroy() }
        sr = null
        mic.background = round(C.CARD, dp(24).toFloat(), 1, C.LINE)
        input.hint = "Hukum likho…"
        if (resumeWake) WakeService.resume()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 7 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
    }
}
