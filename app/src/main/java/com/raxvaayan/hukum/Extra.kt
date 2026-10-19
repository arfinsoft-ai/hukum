package com.raxvaayan.hukum

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v1.1 helper tools, all driven by the user's explicit command in HUKUM:
 * personal notes, reminders, phone controls, call log, calendar, maps, app aliases, Dhan portfolio, morning brief.
 * Nothing runs in the background without the user asking; sending actions still go through the confirm card.
 */
object Extra {
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    private fun fn(name: String, desc: String, props: JSONObject, req: List<String> = listOf()) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    const val NO_PERM = "NOT DONE: permission nahi hai — Settings se de do."
    private val HM = SimpleDateFormat("d MMM, hh:mm a", Locale.ENGLISH)

    fun defs(arr: JSONArray) {
        arr.put(fn("memory_save", "User ne kaha hamesha yaad rakhna (gaadi number, GST no, pref, note). Sirf jab user bole.",
            JSONObject().put("text", p("string", "yaad rakhne wali baat")), listOf("text")))
        arr.put(fn("memory_list", "User ki saved baatein/notes dekho.",
            JSONObject().put("query", p("string", "search word, optional"))))
        arr.put(fn("memory_delete", "Saved note hatao.", JSONObject().put("query", p("string", "note ka hissa")), listOf("query")))
        arr.put(fn("remind", "Diye gaye time par yaad dilao (notification). at = 'yyyy-MM-dd HH:mm' 24h local time.",
            JSONObject().put("at", p("string", "yyyy-MM-dd HH:mm")).put("text", p("string", "reminder text")), listOf("at", "text")))
        arr.put(fn("reminders_list", "Aane wale reminders dekho.", JSONObject()))
        arr.put(fn("reminder_cancel", "Reminder cancel karo (text ka hissa do).", JSONObject().put("query", p("string", "reminder text")), listOf("query")))
        arr.put(fn("device_control", "Phone setting badlo: torch_on, torch_off, silent, vibrate, sound, dnd_on, dnd_off, wifi_settings, bluetooth_settings, volume_up, volume_down, volume_max.",
            JSONObject().put("action", p("string", "action name upar ki list se")), listOf("action")))
        arr.put(fn("call_log", "Recent call history dekho (kisne call kiya, missed calls).",
            JSONObject().put("missed_only", p("boolean", "sirf missed, default false"))))
        arr.put(fn("navigate", "Google Maps mein jagah ka rasta/navigation kholo.",
            JSONObject().put("place", p("string", "destination")).put("nearby", p("boolean", "aaspaas search (petrol pump, atm), default false")), listOf("place")))
        arr.put(fn("calendar_add", "Calendar mein event/meeting add karo. start = 'yyyy-MM-dd HH:mm'.",
            JSONObject().put("title", p("string", "event")).put("start", p("string", "yyyy-MM-dd HH:mm"))
                .put("minutes", p("integer", "duration, default 60")).put("location", p("string", "optional")),
            listOf("title", "start")))
        arr.put(fn("calendar_read", "Aane wale din ke events dekho.",
            JSONObject().put("days", p("integer", "kitne din aage, default 1"))))
        arr.put(fn("dhan_portfolio", "Dhan trading account se holdings/positions aur aaj ka P&L dekho.",
            JSONObject().put("what", p("string", "holdings | positions | funds, default positions"))))
        arr.put(fn("morning_brief", "Subah ka brief: time, aaj ke events, recent missed calls, saved reminders — ek saath.", JSONObject()))
    }

    fun run(h: ToolHost, name: String, a: JSONObject): String? = when (name) {
        "memory_save" -> { Store.add(h.ctx, "mem", a.getString("text")); "DONE: yaad rakh liya." }
        "memory_list" -> Store.list(h.ctx, "mem", a.optString("query")).ifEmpty { "Koi saved note nahi." }
        "memory_delete" -> if (Store.remove(h.ctx, "mem", a.getString("query"))) "DONE: note hata diya." else "Wo note nahi mila."
        "remind" -> Sched.add(h.ctx, a.getString("at"), a.getString("text"))
        "reminders_list" -> Sched.list(h.ctx)
        "reminder_cancel" -> Sched.cancel(h.ctx, a.getString("query"))
        "device_control" -> h.onUi { device(h.ctx, a.getString("action")) }
        "call_log" -> callLog(h.ctx, a.optBoolean("missed_only", false))
        "navigate" -> navigate(h, a.getString("place"), a.optBoolean("nearby", false))
        "calendar_add" -> calAdd(h, a)
        "calendar_read" -> calRead(h.ctx, a.optInt("days", 1))
        "dhan_portfolio" -> Dhan.portfolio(a.optString("what", "positions"))
        "morning_brief" -> More.fullBrief(h.ctx, brief(h.ctx))
        else -> null
    }

    fun parseTime(s: String): Long? {
        val fmts = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss")
        for (f in fmts) try { return SimpleDateFormat(f, Locale.ENGLISH).parse(s.trim())?.time } catch (_: Exception) {}
        return null
    }

    // ---- device ----
    @SuppressLint("MissingPermission")
    private fun device(ctx: Context, action: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        fun vol(dir: Int) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        return when (action) {
            "torch_on", "torch_off" -> try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return "NOT DONE: torch nahi mila."
                cm.setTorchMode(id, action == "torch_on"); "DONE: torch ${if (action == "torch_on") "ON" else "OFF"}."
            } catch (e: Exception) { "NOT DONE: torch error ${e.message}" }
            "silent" -> if (dnd(ctx, nm)) { am.ringerMode = AudioManager.RINGER_MODE_SILENT; "DONE: silent." } else needDnd
            "vibrate" -> { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE; "DONE: vibrate." }
            "sound" -> { am.ringerMode = AudioManager.RINGER_MODE_NORMAL; "DONE: sound ON." }
            "dnd_on" -> if (dnd(ctx, nm)) { nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY); "DONE: DND ON." } else needDnd
            "dnd_off" -> if (dnd(ctx, nm)) { nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL); "DONE: DND OFF." } else needDnd
            "volume_up" -> { vol(AudioManager.ADJUST_RAISE); "DONE." }
            "volume_down" -> { vol(AudioManager.ADJUST_LOWER); "DONE." }
            "volume_max" -> { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI); "DONE: full volume." }
            "wifi_settings" -> { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "WiFi settings khol diye." }
            "bluetooth_settings" -> { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Bluetooth settings khol diye." }
            else -> "NOT DONE: '$action' pata nahi."
        }
    }
    private const val needDnd = "NOT DONE: DND permission chahiye — Settings → Apps → HUKUM → Do Not Disturb access ON karo."
    private fun dnd(ctx: Context, nm: android.app.NotificationManager): Boolean {
        if (nm.isNotificationPolicyAccessGranted) return true
        try { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        return false
    }

    // ---- call log ----
    private fun callLog(ctx: Context, missedOnly: Boolean): String {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return "NOT DONE: call-log permission do (Settings → Apps → HUKUM → Permissions → Call logs)."
        val sel = if (missedOnly) "${CallLog.Calls.TYPE}=${CallLog.Calls.MISSED_TYPE}" else null
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                sel, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 8) {
                    val who = c.getString(0) ?: c.getString(1) ?: "Unknown"
                    val t = when (c.getInt(2)) { CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.INCOMING_TYPE -> "aaya"; CallLog.Calls.OUTGOING_TYPE -> "kiya"; else -> "" }
                    out.add("$who — $t (${HM.format(Date(c.getLong(3)))})"); n++
                }
            }
        } catch (e: Exception) { return "NOT DONE: ${e.message}" }
        return if (out.isEmpty()) "Koi call nahi." else out.joinToString("\n")
    }

    // ---- maps ----
    private fun navigate(h: ToolHost, place: String, nearby: Boolean): String {
        val uri = if (nearby) Uri.parse("geo:0,0?q=" + Uri.encode(place))
        else Uri.parse("google.navigation:q=" + Uri.encode(place))
        val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return h.onUi {
            try { h.ctx.startActivity(i) } catch (e: Exception) { h.ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (nearby) "Maps mein '$place' aaspaas dikha diya." else "Maps navigation '$place' shuru."
        }
    }

    // ---- calendar ----
    private fun calAdd(h: ToolHost, a: JSONObject): String {
        if (h.ctx.checkSelfPermission("android.permission.WRITE_CALENDAR") != PackageManager.PERMISSION_GRANTED) {
            // fall back to the calendar app's insert screen (no permission needed)
            val t = parseTime(a.getString("start")) ?: return "NOT DONE: time samajh nahi aaya."
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t + a.optInt("minutes", 60) * 60000L)
                .putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return h.onUi { h.ctx.startActivity(i); "Calendar add screen khol diya — user Save dabaye." }
        }
        val t = parseTime(a.getString("start")) ?: return "NOT DONE: time samajh nahi aaya."
        return try {
            val id = queryCalId(h.ctx) ?: return "NOT DONE: koi calendar account nahi."
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, id)
                put(CalendarContract.Events.TITLE, a.getString("title"))
                put(CalendarContract.Events.DTSTART, t)
                put(CalendarContract.Events.DTEND, t + a.optInt("minutes", 60) * 60000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                if (a.has("location")) put(CalendarContract.Events.EVENT_LOCATION, a.getString("location"))
            }
            h.ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            "DONE: '${a.getString("title")}' ${HM.format(Date(t))} par add kiya."
        } catch (e: Exception) { "NOT DONE: ${e.message}" }
    }
    private fun queryCalId(ctx: Context): Long? {
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE}=1", null, null)?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }
    fun calRead(ctx: Context, days: Int): String {
        if (ctx.checkSelfPermission("android.permission.READ_CALENDAR") != PackageManager.PERMISSION_GRANTED) return "NOT DONE: calendar permission do."
        val now = System.currentTimeMillis(); val end = now + days.coerceIn(1, 14) * 86400000L
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
                "${CalendarContract.Events.DTSTART}>=? AND ${CalendarContract.Events.DTSTART}<=?",
                arrayOf(now.toString(), end.toString()), "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 12) { out.add("${HM.format(Date(c.getLong(1)))} — ${c.getString(0) ?: "(no title)"}"); n++ }
            }
        } catch (e: Exception) { return "NOT DONE: ${e.message}" }
        return if (out.isEmpty()) "Is dauraan koi event nahi." else out.joinToString("\n")
    }

    private fun brief(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("🕘 ").append(SimpleDateFormat("EEEE, d MMM hh:mm a", Locale.ENGLISH).format(Date())).append("\n")
        sb.append("\n📅 Aaj-kal:\n").append(calRead(ctx, 1).lines().take(6).joinToString("\n"))
        sb.append("\n\n📞 Recent calls:\n").append(callLog(ctx, true).lines().take(4).joinToString("\n"))
        val rem = Sched.list(ctx)
        if (!rem.startsWith("Koi")) sb.append("\n\n⏰ Reminders:\n").append(rem.lines().take(5).joinToString("\n"))
        return sb.toString()
    }
}
