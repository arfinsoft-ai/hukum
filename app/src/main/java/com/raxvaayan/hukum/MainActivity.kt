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

private val CLAIM = Regex("(kar diya|kar di |bhej diya|bhej di|bheja|set kar|khol diya|laga diya|chala diya|add kar|moklyu|mokli|moklavi|mokalyu|મોકલ|કરી દીધ|સેટ કર|ખોલી|મોકલી|मोकल|भेज दिया|कर दिया|sent|done|ho gaya|thai gayu|થઈ ગયુ)")

class MainActivity : Activity(), ToolHost {

    override val ctx: Context get() = this
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var mic: TextView
    private lateinit var status: TextView
    private lateinit var tasksBtn: TextView
    private val history = JSONArray()
    @Volatile private var busy = false
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        Sched.reschedulePending(this)
        More.scheduleBrief(this)
        Health.schedule(this)
        Market.schedule(this)
        Biz.schedule(this)
        if (Prefs.bubble) Bubble.start(this)
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
        bubble("Hukum kijiye, ${Prefs.userName} 🙏\nJaise: \"kal subah 6 baje alarm\", \"kal 9 baje Amit ko WhatsApp karna ki payment bhejo\", \"Papa ko meri location bhejo\", \"aaj ka mausam\"\n\n📋 upar — aane wale saare kaam.", false)
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
        top.addView(android.widget.ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); setOnClickListener { startActivity(Intent(this@MainActivity, TasksActivity::class.java)) }
        }, lp(dp(46), dp(46)).apply { rightMargin = dp(10) })
        val titleBox = vbox()
        titleBox.addView(text("HUKUM", 21f, C.GOLD, true).apply { letterSpacing = 0.08f })
        titleBox.addView(text("Powered by RAXVAAYAN AI", 10.5f, C.MUTED).apply { letterSpacing = 0.04f })
        status = text("", 11.5f, C.MUTED)
        titleBox.addView(status)
        top.addView(titleBox, lp(0, WRAP, 1f))
        tasksBtn = text("📋", 20f, C.TEXT).apply {
            gravity = Gravity.CENTER; background = round(C.CARD, dp(18).toFloat(), 1, C.LINE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { startActivity(Intent(this@MainActivity, TasksActivity::class.java)) }
        }
        top.addView(tasksBtn, lp(WRAP, dp(40)).apply { rightMargin = dp(2) })
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
        bar.addView(circle("📎") { pickImage() }, lp(dp(44), dp(44)).apply { rightMargin = dp(6) })
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

    private fun refreshTasks() {
        val n = TasksActivity.count(this)
        tasksBtn.text = if (n > 0) "📋 $n" else "📋"
        tasksBtn.setTextColor(if (n > 0) C.GOLD else C.TEXT)
    }

    private fun refreshStatus() {
        refreshTasks()
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
Abhi: $now (India time) = ${SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date())} (24h).
Rules:
- Kaam ho sakta hai to TOOL CALL kar, sirf baat mat kar. Ek message mein multiple kaam ho to sab tools call kar.
- Tool result dekh ke 1-2 line mein jawab de, user ki bhasha mein (Hinglish theek hai). Fluff nahi.
- Contact ambiguous/nahi mila to user se seedha poochh.
- WhatsApp/email: user ne exact words diye to wahi bhej; warna ${Prefs.userName} ki taraf se natural, chhota message likh. Email professional ho, sign-off "${Prefs.userName}".
- "kal", "parso", "shaam" jaise words current time se calculate kar. Shaam = 18-19, raat = 21-22, subah = 6-8 agar time na bataya ho.
- Alarm time ke baare mein KABHI AM/PM mat poochh. am/pm/subah/shaam diya → wahi. Sirf number ("10:26", "10 baje") → abhi ke baad aane wala sabse pehla wo time (24h mein convert kar). Example: abhi 10:20 hai, "10:26" → hour 10 minute 26. Abhi 11:00 hai, "10:26" → 22:26.
- Hukum clear ho to "kya main kar doon?" KABHI mat poochh — seedha tool call. Sirf send/call wale tools khud confirm card dikhate hain.
- Photo/selfie/"click photo" → take_photo (front side/selfie = front true).
- YouTube pe kuch chalana/play → youtube_play (news: "<channel> live", channel na ho to "hindi news live").
- Kisi aur app mein kaam (jiska seedha tool nahi): open_app → screen_read → screen_tap/screen_type → screen_read se check. Kabhi "option nahi hai" mat bol jab tak ye try na kar le. Payment/pay/order place button KABHI mat dabana.
- "fir se try karo", "dobara" = pichhla hukum dobara kar.
- Tool result mein "NOT DONE" ho to user ko saaf bata kaam nahi hua. Jo tool ne nahi kiya wo kabhi "ho gaya" mat bol.
- Padhne wale tools (whatsapp_read) ke liye KABHI permission mat maang — seedha call kar.
- "X ko reply karo ..." → pehle khud whatsapp_read, phir whatsapp_reply. Notification na mile to usi contact ko whatsapp_send kar de.
- Message ke words: "X ko ok bhejo", "X ko reply karo ok", "ओके" → message text "OK". Hukum mein jo shabd message lagte hain wahi bhej; sirf tab poochh jab message ka koi text hi na ho.
- Voice typing naam bigaad deta hai ("ध्रुवीय" = Dhruv). Naam English mein contact field mein de. Tool "milte-julte naam" de to user se ek line mein poochh "Dhruv Patel?" jaisa.
- Blinkit/grocery order: blinkit_add se items cart mein daal (Hindi/Gujarati item naam English mein translate kar, jaise "बफैलो मिल्क" → "buffalo milk", "दूध" → "milk"). Checkout/payment KABHI nahi — user khud karega.
- Reminder/"yaad dilana" → remind (at = 'yyyy-MM-dd HH:mm', current time se calculate). "note karo"/"yaad rakho" permanent baat → memory_save.
- Torch/volume/silent/DND/WiFi/Bluetooth → device_control. "kisne call kiya"/missed call → call_log. Rasta/navigation/nearby → navigate. Meeting add/dekho → calendar_add/calendar_read. Trading P&L/positions/holdings → dhan_portfolio. "subah ka brief"/"aaj kya hai" → morning_brief.
- "Task/kaam add karo", birthday, anniversary → calendar_event (birthday/anniversary: repeat yearly, all_day true, start = di gayi date, saal purana bhi chalega; title "<naam/kiska> birthday"). Naam na ho to title "Birthday (2 Oct)" rakh, poochh mat.
- Kisi time par khud kaam karna ("kal 9 baje Amit ko msg kar dena", "5 baje mummy ko call karna") → schedule_action. Sirf yaad dilana ho → remind.
- SMS/OTP padhna → sms_read; SMS bhejna → sms_send. "kya kya aaya", notifications → notifications_read.
- Location share → my_location, phir us link ko whatsapp_send se bhej. Mausam → weather. News padhna/batana → news (YouTube pe chalana ho to youtube_play).
- Ride → ride_book (Uber default), phir screen_read se options bata. Book/confirm user dabayega.
- Trading app (Angel One, Zerodha, Groww) mein share kharidna/bechna: open_app → search pe screen_tap → screen_type stock naam → result tap → BUY/SELL tap → screen_fill Qty (aur price agar diya) → user ko bolo "order ready, final BUY aap dabaiye". Final order button tool BLOCK karega, ye sahi hai.
- screen_* tools: har 1-2 step ke baad screen_read. screen_back, screen_scroll, screen_fill (naam wale box mein likhna) bhi hain. 'BLOCKED' aaye to wahi ruk ke user ko bata.
- "Live location" bheje: my_location se current location link le ke whatsapp_send kar, aur user ko bata ki ye abhi ki location hai (WhatsApp live-tracking nahi).
- "kya hua", "kya bheja", "what happened" → pichhle tool results se sach bata. Tool nahi chala tha to "kuch nahi hua" bol.
- Jawab user ki bhasha mein: Gujarati likhe to Gujarati, Hindi to Hindi/Hinglish.
- Sehat: "paani yaad dilana", "walk", "uth ke chalne ka reminder", "BP ki goli 9 baje", "11 baje sona hai" → health_setup. "paani piya", "goli le li" → health_log. "aaj kitna paani/steps" → health_status. Medical salah mat do; dawai/dose user jo bole wahi save kar.
- 📷 Photo aaye: visiting card → details bata ke contact_save (naam, number, company, title, email, note mein address/GSTIN). Bill/invoice → vendor, date, amount, GSTIN 2-3 line mein bata aur expense_add (category guess kar). Aadhaar/PAN/GST certificate/RC jaisa document → vault_save sahi label se. User ne text mein kuch aur bola ho to wahi kar.
- Lena baaki / udhaar / payment follow-up → due_add ("har Monday" = every_days 7, first_date agla Monday). "X ne paise de diye" → due_paid. "kisse kitna lena hai" → due_list.
- Share price → stock_price. "X ka alert lagao 15 ke upar" → price_alert add. Watchlist → watchlist. "market kaisa hai"/global cues → market_brief open; "aaj market ka hisaab" → market_brief close; roz brief → market_brief on.
- Kharcha ("500 petrol") → expense_add; hisaab → expense_report; "SMS se kharcha jodo" → expense_from_sms.
- Bijli/mobile/EMI/rent har mahine → bill_add; "bill bhar diya" → bill_paid.
- Document bhejna ("PAN card Amit ko bhejo") → vault_send.
- Birthday list → birthdays; wish bhejna → birthday_wish.
- Screen time → screen_time; "Instagram 30 min se zyada nahi" → app_limit.
- Shortcut/mode ("office mode", "sone ka mode" ya user ka banaya naam) → shortcut_run, phir jo steps aaye wo tools se kar. Naya mode banana → shortcut_save. Saved shortcuts: ${Biz.shortcuts(this).keys.joinToString()}.
- Jo bilkul na ho, saaf bata do."""
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

    // ---------- Photo attach (bill / visiting card / document) ----------
    private var camFile: java.io.File? = null

    private fun pickImage() {
        if (busy) return
        android.app.AlertDialog.Builder(this).setTitle("📎 Photo bhejo")
            .setItems(arrayOf("📷 Camera se kheencho", "🖼️ Gallery se chuno")) { _, w ->
                if (w == 0) {
                    val f = java.io.File(cacheDir, "cam.jpg"); f.delete(); camFile = f
                    val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, Files.uri(this, f))
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { startActivityForResult(i, 41) } catch (e: Exception) { bubble("⚠️ Camera app nahi khula.", false) }
                } else startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 42)
            }.show()
    }

    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || (req != 41 && req != 42)) return
        thread {
            try {
                val src: java.io.InputStream? = if (req == 41) camFile?.takeIf { it.exists() && it.length() > 0 }?.inputStream() else data?.data?.let { contentResolver.openInputStream(it) }
                val bytes = src?.use { it.readBytes() } ?: throw Exception("photo nahi mili")
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1; while (maxOf(o.outWidth, o.outHeight) / sample > 2000) sample *= 2
                var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                val rot = try { when (android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f } } catch (e: Exception) { 0f }
                val scale = minOf(1f, 1400f / maxOf(bmp.width, bmp.height))
                val mtx = android.graphics.Matrix().apply { postRotate(rot); postScale(scale, scale) }
                bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mtx, true)
                val out = java.io.File(cacheDir, "last_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
                cacheDir.listFiles { f -> f.name.startsWith("last_") && f != out }?.forEach { it.delete() }
                Biz.lastImage = out
                val b64 = android.util.Base64.encodeToString(out.readBytes(), android.util.Base64.NO_WRAP)
                ui.post {
                    val iv = android.widget.ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; background = round(C.USER, dp(14).toFloat()); setPadding(dp(4), dp(4), dp(4), dp(4)) }
                    list.addView(iv, lp(dp(180), WRAP).apply { gravity = Gravity.END; topMargin = dp(6) })
                    val typed = input.text.toString().trim(); input.setText("")
                    run(typed.ifBlank { "Ye photo dekho." }, voice = false, image = b64)
                }
            } catch (e: Exception) { ui.post { bubble("⚠️ Photo: ${e.message}", false) } }
        }
    }

    private fun run(text: String, voice: Boolean, image: String? = null) {
        bubble(text, true)
        busy = true
        val thinking = bubble("…", false)
        val mark = history.length()
        val userIdx = history.length()
        if (image == null) history.put(JSONObject().put("role", "user").put("content", text))
        else history.put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "$text\n[User ne photo bheji hai — Biz vault/expense/contact tools ke liye 'last photo' yahi hai]"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")))))
        thread {
            var reply = ""
            val used = ArrayList<String>()
            var nudged = false
            var content2: String? = null
            try {
                for (step in 0 until 16) {
                    trimHistory()
                    val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
                    for (i in 0 until history.length()) msgs.put(history.get(i))
                    val m = Llm.chat(msgs, Tools.defs)
                    val calls = m.optJSONArray("tool_calls")
                    val content = if (m.isNull("content")) "" else m.optString("content").trim()
                    val clean = JSONObject().put("role", "assistant").put("content", content)
                    if (calls != null && calls.length() > 0) clean.put("tool_calls", calls)
                    if (calls == null || calls.length() == 0) {
                        // Guard: model claims it did something but called no tool this turn → force a retry once.
                        if (used.isEmpty() && !nudged && CLAIM.containsMatchIn(content.lowercase())) {
                            nudged = true
                            history.put(JSONObject().put("role", "system").put("content",
                                "Tune koi tool call nahi kiya, isliye kuch hua NAHI. Abhi sahi tool call kar. Agar ye kaam kisi tool se nahi ho sakta to saaf bol 'ye nahi ho saka'. Bina tool ke kabhi 'kar diya/bhej diya/mokalyu' mat bol."))
                            continue
                        }
                        if (used.isEmpty() && CLAIM.containsMatchIn(content.lowercase())) content2 = "⚠️ Kuch nahi hua — koi action nahi chala. Dobara thoda alag shabdon mein bolo."
                        history.put(clean)
                        reply = content2 ?: content; break
                    }
                    history.put(clean)
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
                        used.add((if (result.startsWith("NOT DONE") || result.startsWith("BLOCKED") || result.startsWith("Error") || result.contains("cancel kiya")) "❌ " else "✅ ") + name.replace('_', ' '))
                        history.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", result))
                    }
                }
            } catch (e: Exception) {
                // Roll back this turn so a half-finished tool exchange can't break the next request.
                while (history.length() > mark) history.remove(history.length() - 1)
                reply = "⚠️ ${e.message}"
            }
            val out = reply.ifBlank { if (used.isEmpty()) "Kuch nahi hua." else "Ho gaya ✅" } +
                (if (used.isNotEmpty()) "\n\n" + used.distinct().joinToString(" · ") else "")
            if (image != null && history.length() > userIdx) try {
                history.put(userIdx, JSONObject().put("role", "user").put("content", "$text [📷 photo bheji thi]"))
            } catch (_: Exception) {}
            if (image != null && reply.startsWith("⚠️") && (reply.contains("image") || reply.contains("400") || reply.contains("vision")))
                reply += "\n\nPhoto ke liye vision model chahiye — Settings mein OpenRouter + google/gemini-2.5-flash rakho."
            val planned = used.any { it.startsWith("✅") && (it.contains("remind") || it.contains("schedule") || it.contains("calendar")) }
            ui.post {
                thinking.text = out
                refreshTasks()
                if (planned) {
                    val chip = text("📋 Aane wale kaam dekho ›", 13.5f, C.GOLD, true).apply {
                        background = round(C.CARD, dp(14).toFloat(), 1, C.GOLD); setPadding(dp(14), dp(8), dp(14), dp(8))
                        setOnClickListener { startActivity(Intent(this@MainActivity, TasksActivity::class.java)) }
                    }
                    list.addView(chip, lp(WRAP, WRAP).apply { topMargin = dp(6) })
                }
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
        val lang = when {
            s.any { it.code in 0x0A80..0x0AFF } -> "gu-IN"
            s.any { it.code in 0x0900..0x097F } -> "hi-IN"
            else -> Prefs.lang
        }
        if (t.setLanguage(Locale.forLanguageTag(lang)) < 0) t.language = Locale.forLanguageTag("hi-IN")
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
