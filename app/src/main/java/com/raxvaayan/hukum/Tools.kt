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
            .put(fn("open_app", "Phone ki koi app kholo.",
                JSONObject().put("app", p("string", "app ka naam, jaise YouTube, Blinkit, Uber")),
                listOf("app")))
    }

    fun run(h: ToolHost, name: String, a: JSONObject): String = try {
        when (name) {
            "set_alarm" -> alarm(h, a)
            "set_timer" -> timer(h, a)
            "call" -> call(h, a)
            "whatsapp_send" -> waSend(h, a)
            "whatsapp_read" -> WaListener.summary()
            "whatsapp_reply" -> waReply(h, a)
            "send_email" -> email(h, a)
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

    private fun openApp(h: ToolHost, a: JSONObject): String {
        val q = a.getString("app").lowercase().replace(" ", "")
        val pm = h.ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase().replace(" ", "") == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().replace(" ", "").contains(q) }
            ?: return "'${a.getString("app")}' app nahi mili."
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "App launch nahi ho saki."
        h.onUi { h.ctx.startActivity(i); "" }
        return "${match.loadLabel(pm)} khol diya."
    }
}
