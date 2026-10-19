package com.raxvaayan.hukum

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 💪 Sehat: water reminders + log, step count + walk nudge, sit-break, medicine reminders, bedtime.
 * One exact alarm chained to the next due event; everything stays on the phone.
 */
object Health {
    private const val CH = "hukum_health"
    private const val RC = 777_100
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("hukum_health", Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH).format(Date())

    // ---------- settings ----------
    class Cfg(c: Context) {
        private val s = sp(c)
        val waterOn = s.getBoolean("waterOn", false)
        val waterEvery = s.getInt("waterEvery", 60)
        val dayFrom = s.getInt("dayFrom", 9)
        val dayTo = s.getInt("dayTo", 21)
        val waterGoal = s.getInt("waterGoal", 8)
        val walkOn = s.getBoolean("walkOn", false)
        val stepGoal = s.getInt("stepGoal", 8000)
        val sitOn = s.getBoolean("sitOn", false)
        val bedOn = s.getBoolean("bedOn", false)
        val bedTime = s.getString("bedTime", "23:00")!!
        val meds: JSONArray = try { JSONArray(s.getString("meds", "[]")) } catch (e: Exception) { JSONArray() }
    }

    fun set(c: Context, f: SharedPreferences.Editor.() -> Unit) { sp(c).edit().apply(f).apply(); schedule(c) }

    // ---------- logs ----------
    fun water(c: Context) = sp(c).getInt("w_" + today(), 0)
    fun addWater(c: Context, n: Int = 1): Int { val v = (water(c) + n).coerceAtLeast(0); sp(c).edit().putInt("w_" + today(), v).apply(); return v }
    fun medTaken(c: Context, name: String) = sp(c).getBoolean("m_${today()}_${name.lowercase()}", false)
    fun markMed(c: Context, name: String) = sp(c).edit().putBoolean("m_${today()}_${name.lowercase()}", true).apply()

    /** Steps today from the hardware step counter (counts since boot) using a per-day baseline. */
    fun steps(c: Context): Int? {
        if (Build.VERSION.SDK_INT >= 29 && c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return null
        val sm = c.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        var v = -1f; val l = CountDownLatch(1)
        val lis = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { v = e.values[0]; l.countDown() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(lis, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        l.await(4, TimeUnit.SECONDS); sm.unregisterListener(lis)
        if (v < 0) return null
        val s = sp(c); val key = "sb_" + today(); val last = s.getFloat("s_last", -1f)
        var base = s.getFloat(key, -1f)
        if (base < 0 || v < base) { base = if (last in 0f..v && s.getString("s_lastDay", "") != today()) last else if (v < base) 0f else v; s.edit().putFloat(key, base).apply() }
        s.edit().putFloat("s_last", v).putString("s_lastDay", today()).apply()
        return (v - base).toInt()
    }

    fun status(c: Context): String {
        val g = Cfg(c); val sb = StringBuilder("💪 Aaj ki sehat:\n")
        sb.append("💧 Paani: ${water(c)}/${g.waterGoal} glass\n")
        val st = steps(c)
        sb.append(if (st != null) "🚶 Steps: $st/${g.stepGoal}\n" else "🚶 Steps: permission/sensor nahi (Settings → 1)\n")
        for (i in 0 until g.meds.length()) { val m = g.meds.getJSONObject(i); sb.append("💊 ${m.getString("name")} (${m.getString("time")}): ${if (medTaken(c, m.getString("name"))) "✅ li" else "⏳ baaki"}\n") }
        if (g.bedOn) sb.append("😴 Sone ka time: ${g.bedTime}\n")
        val on = listOfNotNull("paani".takeIf { g.waterOn }, "walk".takeIf { g.walkOn }, "sit-break".takeIf { g.sitOn }, "bedtime".takeIf { g.bedOn })
        sb.append("Reminders ON: ${on.ifEmpty { listOf("koi nahi") }.joinToString(", ")}")
        return sb.toString()
    }

    // ---------- schedule ----------
    private data class Ev(val at: Long, val kind: String, val arg: String = "")

    private fun at(day: Calendar, h: Int, m: Int) = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun hm(s: String) = s.split(":").map { it.trim().toIntOrNull() ?: 0 }.let { (it.getOrNull(0) ?: 0) to (it.getOrNull(1) ?: 0) }

    private fun events(c: Context, day: Calendar): List<Ev> {
        val g = Cfg(c); val out = ArrayList<Ev>()
        if (g.waterOn) { var m = g.dayFrom * 60 + 30; while (m <= g.dayTo * 60) { out.add(Ev(at(day, m / 60, m % 60), "water")); m += g.waterEvery.coerceIn(20, 240) } }
        if (g.sitOn) for (h in maxOf(g.dayFrom + 1, 10)..minOf(g.dayTo - 1, 19)) out.add(Ev(at(day, h, 50), "sit"))
        if (g.walkOn) { out.add(Ev(at(day, 13, 0), "walk", "mid")); out.add(Ev(at(day, 19, 30), "walk", "eve")) }
        for (i in 0 until g.meds.length()) { val o = g.meds.getJSONObject(i); val (h, m) = hm(o.getString("time")); out.add(Ev(at(day, h, m), "med", o.getString("name"))) }
        if (g.bedOn) { val (h, m) = hm(g.bedTime); out.add(Ev(at(day, h, m) - 30 * 60000L, "bed")) }
        return out
    }

    fun upcoming(c: Context, limit: Int = 6): List<Pair<Long, String>> {
        val now = System.currentTimeMillis()
        val d0 = Calendar.getInstance(); val d1 = (d0.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        return (events(c, d0) + events(c, d1)).filter { it.at > now }.sortedBy { it.at }.take(limit).map { it.at to label(it) }
    }

    private fun label(e: Ev) = when (e.kind) {
        "water" -> "💧 Paani"; "sit" -> "🧍 Uth ke 2 min chalo"; "walk" -> "🚶 Walk check"; "med" -> "💊 ${e.arg}"; else -> "😴 Sone ki taiyari"
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, RC, Intent(c, Alarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        val next = upcoming(c, 1).firstOrNull()?.first ?: return
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) }
    }

    private fun fire(c: Context) {
        val now = System.currentTimeMillis(); val g = Cfg(c)
        val due = events(c, Calendar.getInstance()).filter { Math.abs(it.at - now) < 90_000 }
        for (e in due) when (e.kind) {
            "water" -> if (water(c) < g.waterGoal) notify(c, 1, "💧 Paani ka time", "Ek glass paani pi lo. Aaj ${water(c)}/${g.waterGoal}.", "water")
            "sit" -> notify(c, 2, "🧍 Thoda break", "Kaafi der se baithe ho — uth ke 2 minute chal lo, stretch karo.", null)
            "walk" -> { val st = steps(c); val target = if (e.arg == "mid") g.stepGoal / 3 else g.stepGoal * 3 / 4
                if (st == null || st < target) notify(c, 3, "🚶 Walk ka time", if (st != null) "Aaj $st steps hue, goal ${g.stepGoal}. 15 min ki walk kar lo." else "15 minute ki walk kar lo.", null) }
            "med" -> if (!medTaken(c, e.arg)) notify(c, 100 + e.arg.hashCode() % 800, "💊 Dawai: ${e.arg}", "${e.arg} lene ka time ho gaya.", "med:" + e.arg)
            "bed" -> notify(c, 4, "😴 Sone ki taiyari", "${g.bedTime} pe sona hai — phone side mein rakho, screen band.", null)
        }
    }

    private fun notify(c: Context, id: Int, title: String, text: String, action: String?) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Sehat reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, id, Intent(c, TasksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_mono).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open)
        if (action != null) {
            val pi = PendingIntent.getBroadcast(c, id + 5000, Intent(c, Act::class.java).setAction(action).putExtra("nid", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, if (action == "water") "💧 Piya" else "✅ Le li", pi).build())
        }
        nm.notify(7000 + id, b.build())
    }

    class Alarm : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pr = goAsync()
            Thread { try { Prefs.init(c); fire(c) } finally { schedule(c); pr.finish() } }.start()
        }
    }

    /** Notification button: 💧 Piya / ✅ Le li */
    class Act : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val a = i.action ?: return
            if (a == "water") addWater(c) else if (a.startsWith("med:")) markMed(c, a.removePrefix("med:"))
            c.getSystemService(NotificationManager::class.java).cancel(7000 + i.getIntExtra("nid", 0))
        }
    }

    // ---------- LLM tools ----------
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    private fun fn(name: String, desc: String, props: JSONObject, req: List<String> = listOf()) = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    fun defs(arr: JSONArray) {
        arr.put(fn("health_status", "Aaj ka paani, steps, dawai status aur kaunse sehat reminders ON hain.", JSONObject()))
        arr.put(fn("health_log", "Sehat log: 'maine paani piya' (water +glasses), 'dawai le li' (med naam).",
            JSONObject().put("water", p("integer", "kitne glass piye, optional")).put("med", p("string", "kaunsi dawai li, optional"))))
        arr.put(fn("health_setup", "Sehat reminders set/badlo. Sirf jo bola wahi bhejo.",
            JSONObject()
                .put("water_on", p("boolean", "paani reminder")).put("water_every_min", p("integer", "har kitne minute, default 60")).put("water_goal", p("integer", "glass/din, default 8"))
                .put("day_from", p("integer", "reminder shuru (ghanta 0-23), default 9")).put("day_to", p("integer", "reminder band (ghanta), default 21"))
                .put("walk_on", p("boolean", "walk/steps nudge")).put("step_goal", p("integer", "steps/din, default 8000"))
                .put("sit_on", p("boolean", "har ghante uth ke chalne ka break"))
                .put("bed_on", p("boolean", "sone ka reminder")).put("bed_time", p("string", "HH:mm"))
                .put("med_add", p("string", "dawai naam (med_time ke saath)")).put("med_time", p("string", "HH:mm roz"))
                .put("med_remove", p("string", "dawai naam hatana"))))
    }

    fun run(h: ToolHost, name: String, a: JSONObject): String? = when (name) {
        "health_status" -> status(h.ctx)
        "health_log" -> {
            val c = h.ctx; val out = ArrayList<String>()
            if (a.has("water")) out.add("💧 ${addWater(c, a.optInt("water", 1))}/${Cfg(c).waterGoal} glass")
            if (a.optString("med").isNotBlank()) { markMed(c, a.getString("med")); out.add("💊 ${a.getString("med")} ✅") }
            if (out.isEmpty()) { addWater(c, 1); out.add("💧 ${water(c)}/${Cfg(c).waterGoal} glass") }
            "DONE: " + out.joinToString(", ")
        }
        "health_setup" -> setup(h.ctx, a)
        else -> null
    }

    private fun setup(c: Context, a: JSONObject): String {
        if (a.optBoolean("walk_on") && Build.VERSION.SDK_INT >= 29 && c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            return "NOT DONE: Steps ke liye 'Physical activity' permission chahiye — Settings ⚙️ → 1. Permissions dabao, phir dobara bolo."
        val meds = Cfg(c).meds
        set(c) {
            if (a.has("water_on")) putBoolean("waterOn", a.getBoolean("water_on"))
            if (a.has("water_every_min")) putInt("waterEvery", a.getInt("water_every_min"))
            if (a.has("water_goal")) putInt("waterGoal", a.getInt("water_goal"))
            if (a.has("day_from")) putInt("dayFrom", a.getInt("day_from"))
            if (a.has("day_to")) putInt("dayTo", a.getInt("day_to"))
            if (a.has("walk_on")) putBoolean("walkOn", a.getBoolean("walk_on"))
            if (a.has("step_goal")) putInt("stepGoal", a.getInt("step_goal"))
            if (a.has("sit_on")) putBoolean("sitOn", a.getBoolean("sit_on"))
            if (a.has("bed_on")) putBoolean("bedOn", a.getBoolean("bed_on"))
            if (a.has("bed_time")) { putString("bedTime", a.getString("bed_time")); putBoolean("bedOn", true) }
            val keep = JSONArray()
            val rm = a.optString("med_remove").lowercase()
            for (i in 0 until meds.length()) { val m = meds.getJSONObject(i); if (rm.isEmpty() || !m.getString("name").lowercase().contains(rm)) keep.put(m) }
            if (a.optString("med_add").isNotBlank()) keep.put(JSONObject().put("name", a.getString("med_add").trim()).put("time", a.optString("med_time", "09:00")))
            putString("meds", keep.toString())
        }
        val nx = upcoming(c, 1).firstOrNull()
        return "DONE: sehat reminders update.\n" + status(c) + (nx?.let { "\nAgla: ${it.second} — ${SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date(it.first))}" } ?: "")
    }
}
