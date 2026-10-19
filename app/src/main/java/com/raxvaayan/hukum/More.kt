package com.raxvaayan.hukum

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.2 tools: scheduled actions, SMS, all-app notifications, location, rides, recurring calendar,
 * weather/news, auto morning brief, screen back/scroll. All user-commanded.
 */
object More {
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    private fun fn(name: String, desc: String, props: JSONObject, req: List<String> = listOf()) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    private val HM = SimpleDateFormat("d MMM, hh:mm a", Locale.ENGLISH)
    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    val SCHEDULABLE = setOf("whatsapp_send", "call", "sms_send", "send_email", "set_alarm", "youtube_play", "open_app")

    fun defs(arr: JSONArray) {
        arr.put(fn("schedule_action", "Kisi time par koi kaam khud karna (jaise kal 9 baje Amit ko WhatsApp). tool = whatsapp_send | call | sms_send | send_email | youtube_play | open_app; args = us tool ke arguments.",
            JSONObject().put("at", p("string", "yyyy-MM-dd HH:mm 24h"))
                .put("tool", p("string", "tool ka naam"))
                .put("args", JSONObject().put("type", "object").put("description", "us tool ke arguments, jaise {contact, message}")),
            listOf("at", "tool", "args")))
        arr.put(fn("sms_read", "Phone ke recent SMS padho (OTP, bank, kisi ka msg).",
            JSONObject().put("from", p("string", "sender naam/number ka hissa, optional")).put("count", p("integer", "kitne, default 8"))))
        arr.put(fn("sms_send", "SMS bhejo (normal text message, WhatsApp nahi).",
            JSONObject().put("contact", p("string", "naam ya number")).put("message", p("string", "text")), listOf("contact", "message")))
        arr.put(fn("notifications_read", "Phone par aaye sab apps ke recent notifications padho ('kya kya aaya hai').",
            JSONObject().put("app", p("string", "sirf is app ke, optional"))))
        arr.put(fn("my_location", "Phone ki abhi ki location + Google Maps link. Location share karne ke liye ye link whatsapp_send/sms_send se bhejo.", JSONObject()))
        arr.put(fn("ride_book", "Uber/Ola/Rapido mein drop location bhar ke ride screen kholo. Book/confirm user dabayega.",
            JSONObject().put("app", p("string", "uber | ola | rapido")).put("drop", p("string", "kahan jaana hai")), listOf("drop")))
        arr.put(fn("calendar_event", "Calendar event — repeat aur all-day ke saath. Birthday/anniversary = repeat yearly, all_day true, start = asli date (saal purana ho to bhi chalega).",
            JSONObject().put("title", p("string", "jaise 'Kavya ka birthday'")).put("start", p("string", "yyyy-MM-dd HH:mm (all-day ho to time 00:00)"))
                .put("all_day", p("boolean", "pura din")).put("repeat", p("string", "none | daily | weekly | monthly | yearly"))
                .put("remind_min", p("integer", "kitne minute pehle yaad, default all-day: 9 baje subah (−900), warna 15"))
                .put("minutes", p("integer", "duration, default 60")),
            listOf("title", "start")))
        arr.put(fn("weather", "Abhi ka mausam + aaj ka forecast (current location ya city).",
            JSONObject().put("city", p("string", "optional city, warna current location"))))
        arr.put(fn("news", "Taaza headlines (Google News).",
            JSONObject().put("topic", p("string", "optional: business, sports, ahmedabad, stock market..."))))
        arr.put(fn("screen_back", "Khuli app mein Back dabao.", JSONObject()))
        arr.put(fn("screen_scroll", "Khuli app mein scroll karo.", JSONObject().put("dir", p("string", "down | up, default down"))))
        arr.put(fn("screen_fill", "Khuli app ke specific input box (jaise Quantity, Price, Search) mein text likho.",
            JSONObject().put("field", p("string", "box ka naam/hint jaise 'Qty', 'Quantity', 'Search'")).put("text", p("string", "likhna kya hai")),
            listOf("field", "text")))
    }

    fun run(h: ToolHost, name: String, a: JSONObject): String? = when (name) {
        "schedule_action" -> schedule(h, a)
        "sms_read" -> smsRead(h.ctx, a.optString("from"), a.optInt("count", 8))
        "sms_send" -> smsSend(h, a)
        "notifications_read" -> WaListener.allSummary(a.optString("app"))
        "my_location" -> loc(h.ctx)?.let { "Location: https://maps.google.com/?q=${it.latitude},${it.longitude} (±${it.accuracy.toInt()}m)" }
            ?: "NOT DONE: location nahi mili (permission/GPS ON karo)."
        "ride_book" -> ride(h, a.optString("app", "uber"), a.getString("drop"))
        "calendar_event" -> calEvent(h, a)
        "weather" -> weather(h.ctx, a.optString("city"))
        "news" -> news(a.optString("topic"))
        "screen_back" -> HukumAccess.inst?.let { it.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); Thread.sleep(900); "DONE: back." } ?: ACC
        "screen_scroll" -> HukumAccess.inst?.let { scroll(it, a.optString("dir", "down") != "up") } ?: ACC
        "screen_fill" -> HukumAccess.inst?.let { fill(it, a.getString("field"), a.getString("text")) } ?: ACC
        else -> null
    }

    private const val ACC = "NOT DONE: Accessibility OFF hai."

    // ---------- scheduled actions ----------
    private fun schedule(h: ToolHost, a: JSONObject): String {
        val tool = a.getString("tool")
        if (tool !in SCHEDULABLE) return "NOT DONE: '$tool' schedule nahi ho sakta. Allowed: ${SCHEDULABLE.joinToString()}"
        val args = a.optJSONObject("args") ?: JSONObject()
        val t = Extra.parseTime(a.getString("at")) ?: return "NOT DONE: time samajh nahi aaya."
        val desc = when (tool) {
            "whatsapp_send" -> "💬 WhatsApp → ${args.optString("contact")}: ${args.optString("message")}"
            "sms_send" -> "✉️ SMS → ${args.optString("contact")}: ${args.optString("message")}"
            "call" -> "📞 Call → ${args.optString("contact")}"
            "send_email" -> "📧 Email → ${args.optString("to")}: ${args.optString("subject")}"
            else -> "$tool ${args}"
        }
        if (!Prefs.autoConfirm && !h.confirm("⏰ Schedule karun?", "${HM.format(Date(t))}\n$desc")) return "User ne cancel kiya."
        return Sched.add(h.ctx, a.getString("at"), desc, JSONObject().put("tool", tool).put("args", args).toString())
    }

    /** Runs a scheduled tool call without UI. User already approved it when scheduling. */
    class Headless(base: Context) : ToolHost {
        override val ctx: Context = HukumAccess.inst ?: base.applicationContext
        override fun confirm(title: String, detail: String) = true
        override fun onUi(block: () -> String): String {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var r = ""; val l = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { r = try { block() } catch (e: Exception) { "Error: ${e.message}" }; l.countDown() }
            l.await(20, TimeUnit.SECONDS); return r
        }
    }

    fun runScheduled(ctx: Context, act: String, id: Int) {
        Prefs.init(ctx)
        val o = try { JSONObject(act) } catch (e: Exception) { return }
        val res = Tools.run(Headless(ctx), o.getString("tool"), o.optJSONObject("args") ?: JSONObject())
        Sched.notifyNow(ctx, "Scheduled kaam: $res", id + 7)
    }

    // ---------- SMS ----------
    private fun smsRead(ctx: Context, from: String, count: Int): String {
        if (!has(ctx, Manifest.permission.READ_SMS)) return "NOT DONE: SMS permission do (Settings → 1. Permissions)."
        val out = ArrayList<String>()
        ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
            while (c.moveToNext() && out.size < count.coerceIn(1, 20)) {
                val addr = c.getString(0) ?: ""; val body = c.getString(1) ?: ""
                if (from.isNotBlank() && !addr.contains(from, true) && !body.contains(from, true)) continue
                out.add("${HM.format(Date(c.getLong(2)))} | $addr: ${body.replace('\n', ' ').take(160)}")
            }
        }
        return if (out.isEmpty()) "Koi SMS nahi mila." else out.joinToString("\n")
    }

    private fun smsSend(h: ToolHost, a: JSONObject): String {
        if (!has(h.ctx, Manifest.permission.SEND_SMS)) return "NOT DONE: SMS bhejne ki permission do."
        val r = Contacts.phone(h.ctx, a.getString("contact"))
        if (r is Resolved.Err) return r.msg
        val ct = (r as Resolved.One).ct; val msg = a.getString("message")
        if (!Prefs.autoConfirm && !h.confirm("✉️ SMS bhejun?", "To: ${ct.name} (${ct.value})\n\n$msg")) return "User ne cancel kiya."
        val sm = if (Build.VERSION.SDK_INT >= 31) h.ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        sm.sendMultipartTextMessage(ct.value, null, sm.divideMessage(msg), null, null)
        return "DONE: SMS ${ct.name} ko bheja."
    }

    // ---------- location ----------
    @SuppressLint("MissingPermission")
    fun loc(ctx: Context): Location? {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provs = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused").filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        if (Build.VERSION.SDK_INT >= 30) {
            for (pv in provs) {
                var got: Location? = null; val l = CountDownLatch(1)
                try { lm.getCurrentLocation(pv, null, ctx.mainExecutor) { got = it; l.countDown() } } catch (_: Exception) { l.countDown() }
                l.await(8, TimeUnit.SECONDS)
                if (got != null) return got
            }
        }
        return provs.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
    }

    // ---------- rides ----------
    private fun ride(h: ToolHost, app: String, drop: String): String {
        val a = app.lowercase()
        val i = when {
            a.contains("uber") -> Intent(Intent.ACTION_VIEW, Uri.parse("https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff%5Bformatted_address%5D=" + Uri.encode(drop) + "&dropoff%5Bnickname%5D=" + Uri.encode(drop)))
                .setPackage("com.ubercab")
            a.contains("ola") -> h.ctx.packageManager.getLaunchIntentForPackage("com.olacabs.customer")
            else -> h.ctx.packageManager.getLaunchIntentForPackage("com.rapido.passenger")
        } ?: return "NOT DONE: $app app installed nahi."
        h.onUi { try { h.ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
            h.ctx.startActivity(Intent(Intent.ACTION_VIEW, i.data ?: Uri.parse("https://m.uber.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; "" }
        return if (a.contains("uber")) "DONE: Uber khula, drop '$drop' bhar diya. Ab screen_read karke ride option dikha; Book/Confirm user dabayega."
        else "App khula. Ab screen_read → 'Where to'/search pe screen_tap → screen_type '$drop' → result tap. Book/Confirm user dabayega."
    }

    // ---------- calendar (recurring/all-day) ----------
    private fun calEvent(h: ToolHost, a: JSONObject): String {
        val ctx = h.ctx
        if (!has(ctx, Manifest.permission.WRITE_CALENDAR)) return "NOT DONE: calendar permission do (Settings → 1)."
        var t = Extra.parseTime(a.getString("start")) ?: Extra.parseTime(a.getString("start").take(10) + " 00:00") ?: return "NOT DONE: date samajh nahi aayi."
        val allDay = a.optBoolean("all_day", false)
        val rep = a.optString("repeat", "none").lowercase()
        val calId = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return "NOT DONE: phone mein koi calendar account nahi."
        if (allDay) {
            val c = Calendar.getInstance(); c.timeInMillis = t
            val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")); u.clear(); u.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)); t = u.timeInMillis
        }
        val rrule = when (rep) { "daily" -> "FREQ=DAILY"; "weekly" -> "FREQ=WEEKLY"; "monthly" -> "FREQ=MONTHLY"; "yearly" -> "FREQ=YEARLY"; else -> null }
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, a.getString("title"))
            put(CalendarContract.Events.DTSTART, t)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            val durMin = if (allDay) 1440 else a.optInt("minutes", 60)
            if (rrule != null) { put(CalendarContract.Events.RRULE, rrule); put(CalendarContract.Events.DURATION, if (allDay) "P1D" else "PT${durMin}M") }
            else put(CalendarContract.Events.DTEND, t + durMin * 60000L)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        return try {
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return "NOT DONE: event save nahi hua."
            val evId = uri.lastPathSegment!!.toLong()
            val rm = a.optInt("remind_min", if (allDay) -540 else 15)
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, evId); put(CalendarContract.Reminders.MINUTES, rm)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
            val d = SimpleDateFormat(if (allDay) "d MMM" else "d MMM, hh:mm a", Locale.ENGLISH).apply { if (allDay) timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
            "DONE: '${a.getString("title")}' calendar mein — $d" + (if (rrule != null) " (har ${mapOf("daily" to "din", "weekly" to "hafte", "monthly" to "mahine", "yearly" to "saal")[rep]})" else "") +
                (if (allDay) ", us din subah 9 baje yaad aayega." else ".")
        } catch (e: Exception) { "NOT DONE: ${e.message}" }
    }

    // ---------- web ----------
    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "Mozilla/5.0 HUKUM")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    private val WCODE = mapOf(0 to "saaf ☀️", 1 to "zyadatar saaf 🌤️", 2 to "halke baadal ⛅", 3 to "baadal ☁️", 45 to "kohra 🌫️", 48 to "kohra 🌫️",
        51 to "boondabaandi 🌦️", 53 to "boondabaandi 🌦️", 55 to "boondabaandi 🌦️", 61 to "halki baarish 🌧️", 63 to "baarish 🌧️", 65 to "tez baarish 🌧️",
        80 to "bauchhar 🌦️", 81 to "bauchhar 🌧️", 82 to "tez bauchhar ⛈️", 95 to "toofan ⛈️", 96 to "toofan ⛈️", 99 to "toofan ⛈️")

    fun weather(ctx: Context, city: String): String = try {
        var lat: Double; var lon: Double; var place: String
        if (city.isNotBlank()) {
            val g = JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + Uri.encode(city))).optJSONArray("results")?.optJSONObject(0)
                ?: throw Exception("'$city' nahi mila")
            lat = g.getDouble("latitude"); lon = g.getDouble("longitude"); place = g.getString("name")
        } else {
            val l = loc(ctx); lat = l?.latitude ?: 23.0225; lon = l?.longitude ?: 72.5714; place = if (l != null) "aapki location" else "Ahmedabad"
        }
        val j = JSONObject(http("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,relative_humidity_2m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1"))
        val cur = j.getJSONObject("current"); val d = j.getJSONObject("daily")
        "Mausam ($place): ${cur.getDouble("temperature_2m").toInt()}°C, ${WCODE[cur.getInt("weather_code")] ?: ""}, humidity ${cur.getInt("relative_humidity_2m")}%. " +
            "Aaj ${d.getJSONArray("temperature_2m_min").getDouble(0).toInt()}–${d.getJSONArray("temperature_2m_max").getDouble(0).toInt()}°C, baarish chance ${d.getJSONArray("precipitation_probability_max").optInt(0)}%."
    } catch (e: Exception) { "NOT DONE: weather nahi mila (${e.message})." }

    fun news(topic: String): String = try {
        val url = if (topic.isBlank()) "https://news.google.com/rss?hl=hi&gl=IN&ceid=IN:hi"
        else "https://news.google.com/rss/search?hl=hi&gl=IN&ceid=IN:hi&q=" + Uri.encode(topic)
        val xml = http(url)
        val titles = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map {
            it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        }.take(6).toList()
        if (titles.isEmpty()) "Koi news nahi mili." else titles.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
    } catch (e: Exception) { "NOT DONE: news nahi mili (${e.message})." }

    // ---------- screen extras ----------
    private fun allNodes(n: android.view.accessibility.AccessibilityNodeInfo) = ArrayList<android.view.accessibility.AccessibilityNodeInfo>().also {
        fun w(x: android.view.accessibility.AccessibilityNodeInfo?, d: Int) { if (x == null || d > 45) return; it.add(x); for (i in 0 until x.childCount) w(x.getChild(i), d + 1) }; w(n, 0) }

    private fun scroll(svc: HukumAccess, down: Boolean): String {
        val r = svc.rootInActiveWindow ?: return "NOT DONE: screen nahi mili."
        val sc = allNodes(r).filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull { val b = android.graphics.Rect(); it.getBoundsInScreen(b); b.width() * b.height() }
        val ok = sc?.performAction(if (down) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        if (!ok) {
            val dm = svc.resources.displayMetrics
            val path = android.graphics.Path().apply {
                moveTo(dm.widthPixels / 2f, dm.heightPixels * (if (down) 0.75f else 0.3f)); lineTo(dm.widthPixels / 2f, dm.heightPixels * (if (down) 0.3f else 0.75f)) }
            svc.dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 350)).build(), null, null)
        }
        Thread.sleep(900); return "DONE: scroll ${if (down) "down" else "up"}."
    }

    private fun fill(svc: HukumAccess, field: String, text: String): String {
        val r = svc.rootInActiveWindow ?: return "NOT DONE: screen nahi mili."
        val nodes = allNodes(r).filter { it.isVisibleToUser }
        val edits = nodes.filter { it.isEditable }
        if (edits.isEmpty()) return "NOT DONE: koi input box nahi."
        val f = field.lowercase()
        fun lbl(n: android.view.accessibility.AccessibilityNodeInfo) = listOfNotNull(n.hintText, n.contentDescription, n.viewIdResourceName, n.text).joinToString(" ").lowercase()
        var target = edits.firstOrNull { lbl(it).contains(f) }
        if (target == null) {
            // nearest edit below/right of a label containing the field name
            val label = nodes.firstOrNull { !it.isEditable && (it.text?.toString()?.lowercase()?.contains(f) == true) }
            if (label != null) {
                val lb = android.graphics.Rect().also { label.getBoundsInScreen(it) }
                target = edits.minByOrNull { val b = android.graphics.Rect().also { x -> it.getBoundsInScreen(x) }; Math.abs(b.centerY() - lb.bottom) + Math.abs(b.centerX() - lb.centerX()) / 3 }
            }
        }
        val e = target ?: return "NOT DONE: '$field' box nahi mila. screen_read se dekho."
        e.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
        e.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
        Thread.sleep(800); return "DONE: '$field' mein '$text' bhara."
    }

    // ---------- auto morning brief ----------
    private const val BRIEF_RC = 777_001

    fun scheduleBrief(ctx: Context) {
        Prefs.init(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, BRIEF_RC, Intent(ctx, BriefAlarm::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        if (!Prefs.briefOn) return
        val (hh, mm) = Prefs.briefTime.split(":").map { it.trim().toIntOrNull() ?: 0 }.let { (it.getOrNull(0) ?: 8) to (it.getOrNull(1) ?: 0) }
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm); set(Calendar.SECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi) } catch (e: SecurityException) { am.set(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi) }
    }

    fun fullBrief(ctx: Context, base: String): String {
        val w = weather(ctx, "")
        val n = news("").lines().take(3).joinToString("\n")
        return "$base\n\n🌤️ $w\n\n" + Health.status(ctx) + "\n\n📰 Headlines:\n$n"
    }

    class BriefAlarm : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val pr = goAsync()
            Thread {
                try {
                    Prefs.init(ctx)
                    val txt = Tools.run(Headless(ctx), "morning_brief", JSONObject())
                    Sched.notifyNow(ctx, txt, 777_002)
                    Speaker.say(ctx, "Suprabhat ${Prefs.userName}. " + txt.replace(Regex("[🕘📅📞⏰🌤️📰☀️⛅☁️🌧️🌦️⛈️🌫️]"), "").take(900))
                } finally { scheduleBrief(ctx); pr.finish() }
            }.start()
        }
    }
}
