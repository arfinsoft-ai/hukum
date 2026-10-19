package com.raxvaayan.hukum

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exact-alarm backed reminders: fire a notification (with TTS) at a set time. User-initiated only. */
object Sched {
    private const val CH = "hukum_remind"
    private val HM = SimpleDateFormat("d MMM, hh:mm a", Locale.ENGLISH)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("hukum_sched", Context.MODE_PRIVATE)

    fun add(ctx: Context, atStr: String, text: String, act: String? = null): String {
        val t = Extra.parseTime(atStr) ?: return "NOT DONE: time '$atStr' samajh nahi aaya (yyyy-MM-dd HH:mm)."
        if (t < System.currentTimeMillis() - 60000) return "NOT DONE: wo time nikal chuka hai."
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            try { ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return "NOT DONE: exact-alarm permission chahiye — abhi screen khuli hai, 'Allow' karo, phir dobara bolo."
        }
        val id = (System.currentTimeMillis() % 1_000_000).toInt()
        val arr = read(ctx); arr.put(JSONObject().put("id", id).put("at", t).put("t", text.trim()).apply { if (act != null) put("act", act) })
        sp(ctx).edit().putString("items", arr.toString()).apply()
        val pi = pending(ctx, id, text, act)
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) }
        catch (e: SecurityException) { am.set(AlarmManager.RTC_WAKEUP, t, pi) }
        return if (act != null) "DONE: ${HM.format(Date(t))} par ye kaam khud kar dunga — $text" else "DONE: ${HM.format(Date(t))} par yaad dila dunga — \"$text\"."
    }

    fun list(ctx: Context): String {
        prune(ctx)
        val arr = read(ctx); val out = ArrayList<String>()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); out.add("⏰ ${HM.format(Date(o.getLong("at")))} — ${o.getString("t")}") }
        return if (out.isEmpty()) "Koi reminder set nahi." else out.sorted().joinToString("\n")
    }

    fun cancel(ctx: Context, query: String): String {
        val arr = read(ctx); val q = query.trim().lowercase(); val keep = JSONArray(); var done = false
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!done && o.getString("t").lowercase().contains(q)) { am.cancel(pending(ctx, o.getInt("id"), o.getString("t"))); done = true }
            else keep.put(o)
        }
        sp(ctx).edit().putString("items", keep.toString()).apply()
        return if (done) "DONE: reminder cancel." else "Wo reminder nahi mila."
    }

    fun reschedulePending(ctx: Context) {
        val arr = read(ctx); val now = System.currentTimeMillis()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val t = o.getLong("at")
            if (t > now) try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(ctx, o.getInt("id"), o.getString("t"), o.optString("act").ifEmpty { null })) } catch (_: Exception) {}
        }
    }

    /** Upcoming items, soonest first. Each: id, at, t, act? */
    fun items(ctx: Context): List<JSONObject> {
        prune(ctx)
        val arr = read(ctx)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.getLong("at") > System.currentTimeMillis() - 60000 }.sortedBy { it.getLong("at") }
    }

    fun cancelId(ctx: Context, id: Int) {
        val arr = read(ctx); val keep = JSONArray()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getInt("id") == id) am.cancel(pending(ctx, id, o.getString("t"), o.optString("act").ifEmpty { null })) else keep.put(o)
        }
        sp(ctx).edit().putString("items", keep.toString()).apply()
    }

    private fun prune(ctx: Context) {
        val arr = read(ctx); val now = System.currentTimeMillis(); val keep = JSONArray()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getLong("at") > now - 3600000) keep.put(o) }
        sp(ctx).edit().putString("items", keep.toString()).apply()
    }

    private fun read(ctx: Context) = try { JSONArray(sp(ctx).getString("items", "[]")) } catch (e: Exception) { JSONArray() }

    private fun pending(ctx: Context, id: Int, text: String, act: String? = null): PendingIntent {
        val i = Intent(ctx, Alarm::class.java).setAction("fire_$id").putExtra("text", text).putExtra("id", id).putExtra("act", act)
        val fl = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, id, i, fl)
    }

    fun notifyNow(ctx: Context, text: String, id: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val open = PendingIntent.getActivity(ctx, id + 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val n = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CH) else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx))
            .setSmallIcon(R.drawable.ic_mono).setContentTitle("👑 HUKUM yaad dila raha hai").setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setAutoCancel(true).setContentIntent(open).build()
        nm.notify(id, n)
    }

    /** Receiver fired at reminder time. */
    class Alarm : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val text = i.getStringExtra("text") ?: "Reminder"
            val id = i.getIntExtra("id", 1)
            val act = i.getStringExtra("act")
            cancel(ctx, text)
            if (act != null) {
                val pr = goAsync()
                Thread { try { More.runScheduled(ctx, act, id) } finally { pr.finish() } }.start()
                return
            }
            notifyNow(ctx, text, id)
            Speaker.say(ctx, "Yaad dila raha hoon: $text")
        }
    }
}
