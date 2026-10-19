package com.raxvaayan.hukum

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** What the tool layer needs from the UI. confirm() and onUi() are called from a background thread and block. */
interface ToolHost {
    val ctx: Context
    fun confirm(title: String, detail: String): Boolean
    fun onUi(block: () -> String): String
}

object Tools {
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    val defs: JSONArray by lazy {
        JSONArray()
            .put(fn("set_alarm", "Phone ke Clock app mein alarm lagao.",
                JSONObject()
                    .put("hour", p("integer", "0-23, 24-hour format"))
                    .put("minute", p("integer", "0-59"))
                    .put("label", p("string", "alarm ka naam, optional"))
                    .put("days", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                        .put("description", "repeat days, optional: mon,tue,wed,thu,fri,sat,sun")),
                listOf("hour", "minute")))
            .put(fn("cancel_alarm", "Laga hua alarm OFF karo (Clock list mein switch band). hour/minute do. Alarm time change = cancel_alarm(purana) + set_alarm(naya).",
                JSONObject()
                    .put("hour", p("integer", "0-23, optional"))
                    .put("minute", p("integer", "0-59, optional")),
                listOf()))
            .put(fn("set_timer", "Countdown timer lagao.",
                JSONObject().put("seconds", p("integer", "total seconds")).put("label", p("string", "optional")),
                listOf("seconds")))
            .put(fn("call", "Kisi contact ya number ko phone call karo.",
                JSONObject().put("contact", p("string", "contact ka naam (jaisa user ne bola) ya phone number")),
                listOf("contact")))
            .put(fn("whatsapp_send", "Kisi contact/number ko WhatsApp message bhejo (naya message).",
                JSONObject()
                    .put("contact", p("string", "contact naam ya phone number"))
                    .put("message", p("string", "bhejne wala exact message text")),
                listOf("contact", "message")))
            .put(fn("whatsapp_read", "Recent aaye WhatsApp messages dekho (notifications se). Reply se pehle ya 'kisne message kiya' pe use karo.",
                JSONObject(), listOf()))
            .put(fn("whatsapp_reply", "Jisne recently WhatsApp message kiya hai uske chat mein seedha reply bhejo (notification reply).",
                JSONObject()
                    .put("sender", p("string", "sender ka naam jaisa whatsapp_read mein aaya"))
                    .put("message", p("string", "reply text")),
                listOf("sender", "message")))
            .put(fn("send_email", "Gmail se email bhejo.",
                JSONObject()
                    .put("to", p("string", "email address ya contact naam"))
                    .put("subject", p("string", "subject"))
                    .put("body", p("string", "poora email body")),
                listOf("to", "subject", "body")))
            .put(fn("blinkit_add", "Blinkit app mein items search karke cart mein ADD karo (qty ke saath). Payment/checkout nahi karta. Item naam English mein do (jaise 'buffalo milk', 'amul butter').",
                JSONObject().put("items", JSONObject().put("type", "array")
                    .put("items", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("name", p("string", "item ka search naam, English")).put("qty", p("integer", "kitne, default 1")))
                        .put("required", JSONArray(listOf("name"))))
                    .put("description", "items list")),
                listOf("items")))
            .put(fn("take_photo", "Camera khol ke photo khud click karo. front=true selfie/front side ke liye.",
                JSONObject().put("front", p("boolean", "true = front/selfie camera, false = back")),
                listOf()))
            .put(fn("youtube_play", "YouTube pe search karke pehla video play karo. News ke liye channel + 'live' ya 'latest news' query do (jaise 'India TV live').",
                JSONObject().put("query", p("string", "search query")), listOf("query")))
            .put(fn("screen_read", "Abhi phone screen par jo app khuli hai uske texts/buttons padho. Kisi app mein aage ka step karne se pehle use karo.",
                JSONObject(), listOf()))
            .put(fn("screen_tap", "Khuli app mein us text/button ko dabao (screen_read mein jo dikha).",
                JSONObject().put("text", p("string", "button/text jaisa screen pe likha hai")).put("nth", p("integer", "same text multiple ho to kaunsa, default 1")),
                listOf("text")))
            .put(fn("screen_type", "Khuli app ke input box mein text likho.",
                JSONObject().put("text", p("string", "text")).put("enter", p("boolean", "likhne ke baad search/enter dabana hai")),
                listOf("text")))
            .put(fn("open_app", "Phone ki koi app kholo.",
                JSONObject().put("app", p("string", "app ka naam, jaise YouTube, Blinkit, Uber")),
                listOf("app")))
            .also { Extra.defs(it) }
    }

    private const val NO_ACC = "NOT DONE: Accessibility OFF hai — Settings ⚙️ → 2. Accessibility ON karwao."

    fun run(h: ToolHost, name: String, a: JSONObject): String = try {
        Extra.run(h, name, a) ?: when (name) {
            "set_alarm" -> alarm(h, a)
            "cancel_alarm" -> cancelAlarm(h, a)
            "set_timer" -> timer(h, a)
            "call" -> call(h, a)
            "whatsapp_send" -> waSend(h, a)
            "whatsapp_read" -> WaListener.summary()
            "whatsapp_reply" -> waReply(h, a)
            "send_email" -> email(h, a)
            "blinkit_add" -> blinkit(a)
            "take_photo" -> photo(a)
            "youtube_play" -> HukumAccess.inst?.let { Screen.youtube(it, a.getString("query")) } ?: NO_ACC
            "screen_read" -> HukumAccess.inst?.let { Screen.read(it) } ?: NO_ACC
            "screen_tap" -> HukumAccess.inst?.let { Screen.tap(it, a.getString("text"), a.optInt("nth", 1)) } ?: NO_ACC
            "screen_type" -> HukumAccess.inst?.let { Screen.type(it, a.getString("text"), a.optBoolean("enter", true)) } ?: NO_ACC
            "open_app" -> openApp(h, a)
            else -> "Unknown tool $name"
        }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    private fun ask(h: ToolHost, title: String, detail: String) = Prefs.autoConfirm || h.confirm(title, detail)

    private fun alarm(h: ToolHost, a: JSONObject): String {
        val hr = a.getInt("hour"); val mn = a.optInt("minute", 0)
        val label = a.optString("label", "HUKUM")
        val dayMap = mapOf("sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY)
        val days = ArrayList<Int>()
        a.optJSONArray("days")?.let { arr -> for (i in 0 until arr.length()) dayMap[arr.getString(i).take(3).lowercase()]?.let { days.add(it) } }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hr)
            .putExtra(AlarmClock.EXTRA_MINUTES, mn)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, days)
        h.onUi { h.ctx.startActivity(i); "" }
        return "Alarm set: %02d:%02d %s".format(hr, mn, if (days.isNotEmpty()) "(repeat)" else "")
    }

    private fun cancelAlarm(h: ToolHost, a: JSONObject): String {
        val i = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        h.onUi { h.ctx.startActivity(i); "" }
        if (!a.has("hour")) return "Alarm list khol di. Kaunsa band karna hai user se time poochho."
        val hr = a.getInt("hour"); val mn = a.optInt("minute", 0)
        val svc = HukumAccess.inst
            ?: return "NOT DONE: Accessibility OFF hai, alarm band nahi hua. List khuli hai — user khud %02d:%02d OFF kare.".format(hr, mn)
        val err = Clock.turnOff(svc, hr, mn)
        svc.backToHukum()
        return if (err == null) "DONE: %02d:%02d ka alarm OFF kar diya.".format(hr, mn)
        else "NOT DONE: $err. User ko sach batao ki band nahi hua."
    }

    private fun timer(h: ToolHost, a: JSONObject): String {
        val s = a.getInt("seconds")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "HUKUM"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        h.onUi { h.ctx.startActivity(i); "" }
        return "Timer set: $s seconds"
    }

    private fun call(h: ToolHost, a: JSONObject): String {
        val r = Contacts.phone(h.ctx, a.getString("contact"))
        if (r is Resolved.Err) return r.msg
        val ct = (r as Resolved.One).ct
        if (!ask(h, "📞 Call karun?", "${ct.name}\n${ct.value}")) return "User ne cancel kiya."
        val canCall = h.ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(ct.value)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        h.onUi { h.ctx.startActivity(i); "" }
        return "Calling ${ct.name} (${ct.value})"
    }

    private fun waPackage(ctx: Context): String? {
        val pm = ctx.packageManager
        fun has(p: String) = try { pm.getPackageInfo(p, 0); true } catch (e: Exception) { false }
        val pref = Prefs.waPkg
        return when {
            has(pref) -> pref
            has("com.whatsapp") -> "com.whatsapp"
            has("com.whatsapp.w4b") -> "com.whatsapp.w4b"
            else -> null
        }
    }

    private fun waSend(h: ToolHost, a: JSONObject): String {
        val msg = a.getString("message")
        val r = Contacts.phone(h.ctx, a.getString("contact"))
        if (r is Resolved.Err) return r.msg
        val ct = (r as Resolved.One).ct
        val pkg = waPackage(h.ctx) ?: return "WhatsApp installed nahi hai."
        if (!ask(h, "💬 WhatsApp bhejun?", "To: ${ct.name}\n\n$msg")) return "User ne cancel kiya."
        val num = Contacts.intl(ct.value)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$num&text=" + Uri.encode(msg)))
            .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val auto = HukumAccess.isOn()
        if (auto) HukumAccess.arm(pkg)
        h.onUi { h.ctx.startActivity(i); "" }
        return if (auto) "WhatsApp message ${ct.name} ko bheja ja raha hai (auto-send)."
        else "WhatsApp chat khol diya, message type hai. Auto-send ke liye Accessibility ON karna hoga — user ko batao."
    }

    private fun waReply(h: ToolHost, a: JSONObject): String {
        val sender = a.getString("sender"); val msg = a.getString("message")
        val item = WaListener.find(sender) ?: return "'$sender' ka recent WhatsApp notification nahi mila. (Notification access ON hai? Ya whatsapp_send use karo.)"
        if (!ask(h, "↩️ Reply bhejun?", "To: ${item.sender}\n\n$msg")) return "User ne cancel kiya."
        return WaListener.reply(h.ctx, item, msg)
    }

    private fun email(h: ToolHost, a: JSONObject): String {
        val r = Contacts.email(h.ctx, a.getString("to"))
        if (r is Resolved.Err) return r.msg
        val ct = (r as Resolved.One).ct
        val subject = a.optString("subject"); val body = a.optString("body")
        if (!ask(h, "📧 Email bhejun?", "To: ${ct.value}\nSubject: $subject\n\n$body")) return "User ne cancel kiya."
        val gmail = "com.google.android.gm"
        val hasGmail = try { h.ctx.packageManager.getPackageInfo(gmail, 0); true } catch (e: Exception) { false }
        val i = Intent(Intent.ACTION_SEND).setType("message/rfc822")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(ct.value))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (hasGmail) i.setPackage(gmail)
        val auto = hasGmail && HukumAccess.isOn()
        if (auto) HukumAccess.arm(gmail)
        h.onUi { h.ctx.startActivity(if (hasGmail) i else Intent.createChooser(i, "Email").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "" }
        return if (auto) "Email ${ct.value} ko bheja ja raha hai (auto-send)." else "Email compose khul gaya, user Send dabaye."
    }

    private fun blinkit(a: JSONObject): String {
        val svc = HukumAccess.inst ?: return "Blinkit mein add karne ke liye Accessibility (HUKUM Auto-Send) ON karna hoga — Settings ⚙️ → 2. Accessibility."
        val arr = a.optJSONArray("items") ?: return "Items nahi diye."
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val n = o.optString("name").trim()
            if (n.isEmpty()) null else Blinkit.Item(n, o.optInt("qty", 1).coerceIn(1, 20))
        }
        if (items.isEmpty()) return "Items nahi diye."
        val r = Blinkit.addAll(svc, items)
        svc.backToHukum()
        return r
    }

    private fun photo(a: JSONObject): String {
        val svc = HukumAccess.inst ?: return "NOT DONE: Accessibility OFF hai, photo click nahi kar sakta."
        val front = a.optBoolean("front", false)
        val err = Cam.shoot(svc, front)
        return if (err == null) "DONE: ${if (front) "front" else "back"} camera se photo click kar di (Gallery mein hai)." else "NOT DONE: $err."
    }

    private val appAlias = mapOf(
        "angelbroking" to "angelone", "angel" to "angelone", "angelbroker" to "angelone",
        "phonepay" to "phonepe", "gpay" to "googlepay", "gpayupi" to "googlepay",
        "insta" to "instagram", "wa" to "whatsapp", "yt" to "youtube", "maps" to "googlemaps",
        "zerodha" to "kite", "dhanapp" to "dhan")

    private fun openApp(h: ToolHost, a: JSONObject): String {
        var q = a.getString("app").lowercase().replace(" ", "")
        q = appAlias[q] ?: q
        val pm = h.ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase().replace(" ", "") == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().replace(" ", "").contains(q) }
            ?: run {
                val names = apps.map { it.loadLabel(pm).toString() }.distinct()
                val near = names.filter { it.lowercase().replace(" ", "").let { n -> n.contains(q.take(4)) || q.contains(n.take(4)) } }.take(5)
                return "'${a.getString("app")}' app install nahi hai." + if (near.isNotEmpty()) " Milti-julti: ${near.joinToString(", ")}." else ""
            }
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "App launch nahi ho saki."
        h.onUi { h.ctx.startActivity(i); "" }
        return "${match.loadLabel(pm)} khol diya."
    }
}
