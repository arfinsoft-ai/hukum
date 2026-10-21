package com.raxvaayan.hukum

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 📋 Upcoming: scheduled actions, reminders, morning brief, next 7 days of calendar. */
class TasksActivity : Activity() {
    private lateinit var box: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Crash.install(this)
        Prefs.init(this)
        box = vbox().apply { setPadding(dp(16), dp(16), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); fitsSystemWindows = true; addView(box) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        box.removeAllViews()
        val head = hbox()
        head.addView(text("‹", 28f, C.GOLD).apply { setPadding(0, 0, dp(14), 0); setOnClickListener { finish() } })
        head.addView(text("📋 Aane wale kaam", 21f, C.GOLD, true), lp(0, WRAP, 1f))
        box.addView(head)
        box.addView(text("Reminders aur khud hone wale kaam. ✕ se cancel.", 12.5f, C.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(4) })

        safe("Tasks") {
        val items = Sched.items(this)
        val acts = items.filter { it.optString("act").isNotEmpty() }
        val rems = items.filter { it.optString("act").isEmpty() }

        section("⚡ Khud hone wale kaam", acts.size)
        if (acts.isEmpty()) empty("Kuch scheduled nahi. Bolo: \"kal 9 baje Amit ko WhatsApp karna ki payment bhejo\"")
        acts.forEach { card(it, true) }

        section("⏰ Reminders", rems.size)
        if (rems.isEmpty()) empty("Koi reminder nahi. Bolo: \"5 baje yaad dilana GST file karna\"")
        rems.forEach { card(it, false) }

        }
        safe("Business") { bizSection() }
        safe("Sehat") { healthSection() }
        safe("Calendar") {

        if (Prefs.briefOn) {
            section("🌅 Morning brief", -1)
            val c = nextBrief()
            row("🌅", "Roz ka brief", "${dayLabel(c)} ${TIME.format(Date(c))} · mausam, meetings, calls, news", null)
        }

        section("📅 Calendar — agle 7 din", -1)
        val ev = calendar()
        if (ev.isEmpty()) empty(if (has(Manifest.permission.READ_CALENDAR)) "Agle 7 din koi event nahi." else "Calendar permission do (⚙️ → 1).")
        ev.forEach { (t, title, allDay) -> row(if (title.contains("birthday", true)) "🎂" else "📅", title,
            "${dayLabel(t)}${if (allDay) " · pura din" else " " + TIME.format(Date(t))}", null) }

        }
        box.addView(text("Powered by RAXVAAYAN AI", 11.5f, C.MUTED).apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP).apply { topMargin = dp(28) })
    }

    private fun safe(name: String, f: () -> Unit) {
        try { f() } catch (e: Throwable) {
            box.addView(text("⚠️ $name section error: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(3).joinToString("\n") { "  at ${it.fileName}:${it.lineNumber}" }}", 12f, C.RED).apply {
                setTextIsSelectable(true); background = round(C.CARD, dp(12).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10)) },
                lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
    }

    private fun inr(v: Double) = "₹" + "%,.0f".format(v)

    private fun bizSection() {
        val dues = Biz.dues(this)
        section("💰 Lena baaki", dues.size)
        if (dues.isEmpty()) empty("Bolo: \"Mehta se 45000 lena hai, har Monday yaad dilana\"")
        dues.sortedByDescending { it.getDouble("amount") }.forEach { o ->
            row("💰", "${o.getString("name")} — ${inr(o.getDouble("amount"))}", (o.optString("note").takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") +
                "agla reminder ${dayLabel(o.getLong("next"))}", {
                    android.app.AlertDialog.Builder(this).setMessage("${o.getString("name")} ka poora payment aa gaya?")
                        .setPositiveButton("Haan, clear") { _, _ -> Tools.run(More.Headless(this), "due_paid", JSONObject().put("name", o.getString("name"))); render() }
                        .setNegativeButton("Nahi", null).show() }, "✓")
        }
        val bills = Biz.bills(this)
        if (bills.isNotEmpty()) {
            section("🧾 Bills / EMI", bills.size)
            bills.sortedBy { Biz.nextBillDate(it) }.forEach { o -> val paid = Biz.billPaid(this, o)
                row("🧾", o.getString("name") + (if (o.optDouble("amount", 0.0) > 0) " — " + inr(o.getDouble("amount")) else ""),
                    if (paid) "Is mahine paid ✅" else "Due ${dayLabel(Biz.nextBillDate(o))} (${o.getInt("day")} tarikh)",
                    if (paid) null else ({ Tools.run(More.Headless(this), "bill_paid", JSONObject().put("name", o.getString("name"))); render() }), "✓") }
        }
        val alerts = Market.alerts(this)
        if (alerts.isNotEmpty()) {
            section("🔔 Price alerts", alerts.size)
            alerts.forEach { o -> row("🔔", "${o.getString("name")} ${if (o.getBoolean("above")) "▲ upar" else "▼ neeche"} ₹${o.getDouble("price")}", "Market time mein har 5 min check",
                { Market.alertRemove(this, o.getString("id")); render() }) }
        }
        val bd = Biz.birthdays(this, 7)
        if (bd.isNotEmpty()) { section("🎂 Birthdays — 7 din", bd.size); bd.forEach { row("🎂", it.name, dayLabel(it.date) + if (it.phone == null) " · number nahi" else " · 8:30 baje wish button aayega", null) } }
        section("💸 Is mahine kharcha", -1)
        empty("${inr(Biz.monthTotal(this))}  ·  bolo \"is mahine ka hisaab\" ya \"SMS se kharcha jodo\"")
    }

    private fun healthSection() {
        section("💪 Sehat aaj", -1)
        val g = Health.Cfg(this)
        val w = Health.water(this)
        val r = hbox().apply { background = round(C.CARD, dp(14).toFloat()); setPadding(dp(14), dp(12), dp(10), dp(12)) }
        r.addView(text("💧", 22f).apply { setPadding(0, 0, dp(12), 0) })
        val col = vbox()
        col.addView(text("Paani: $w / ${g.waterGoal} glass", 15f, C.TEXT, true))
        col.addView(bar(w.toFloat() / g.waterGoal))
        r.addView(col, lp(0, WRAP, 1f))
        r.addView(text("+1", 16f, android.graphics.Color.BLACK, true).apply {
            gravity = Gravity.CENTER; background = round(C.GOLD, dp(16).toFloat()); setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { Health.addWater(this@TasksActivity); render() } }, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        box.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(8) })

        val sr = hbox().apply { background = round(C.CARD, dp(14).toFloat()); setPadding(dp(14), dp(12), dp(10), dp(12)) }
        sr.addView(text("🚶", 22f).apply { setPadding(0, 0, dp(12), 0) })
        val scol = vbox()
        val st = text("Steps: …", 15f, C.TEXT, true); scol.addView(st)
        val sbar = bar(0f); scol.addView(sbar)
        sr.addView(scol, lp(0, WRAP, 1f))
        box.addView(sr, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        Thread {
            val n = try { Health.steps(this) } catch (e: Throwable) { null }
            if (!isFinishing) runOnUiThread { try {
                st.text = if (n != null) "Steps: $n / ${g.stepGoal}" else "Steps: ⚙️ → 1. Permissions (Physical activity) do"
                (sbar.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = ((n ?: 0).toFloat() / g.stepGoal).coerceIn(0f, 1f)
                (sbar.getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = 1f - ((n ?: 0).toFloat() / g.stepGoal).coerceIn(0f, 1f)
                sbar.requestLayout()
            } catch (_: Throwable) {} }
        }.start()

        for (i in 0 until g.meds.length()) {
            val m = g.meds.getJSONObject(i); val nm = m.getString("name"); val done = Health.medTaken(this, nm)
            row("💊", nm, "Roz ${m.getString("time")} · ${if (done) "aaj le li ✅" else "aaj baaki ⏳"}", if (done) null else ({ Health.markMed(this, nm); render() }), "✓")
        }
        val up = Health.upcoming(this, 4)
        if (up.isEmpty()) empty("Sehat reminders OFF. Bolo: \"har ghante paani yaad dilana\", \"roz 8000 steps\", \"11 baje sona hai\" — ya ⚙️ → 💪 Sehat.")
        else box.addView(text("Agle: " + up.joinToString("  ·  ") { "${it.second} ${TIME.format(Date(it.first))}" }, 12.5f, C.MUTED),
            lp(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    private fun bar(f: Float): LinearLayout {
        val b = hbox().apply { background = round(C.LINE, dp(4).toFloat()) }
        val p = f.coerceIn(0f, 1f)
        b.addView(android.view.View(this).apply { background = round(C.GOLD, dp(4).toFloat()) }, lp(0, dp(6), p))
        b.addView(android.view.View(this), lp(0, dp(6), 1f - p))
        return b.also { it.layoutParams = lp(MATCH, dp(6)).apply { topMargin = dp(6) } }
    }

    private fun card(o: JSONObject, isAct: Boolean) {
        val t = o.getLong("at")
        val txt = o.getString("t")
        val icon = when {
            !isAct -> "⏰"
            txt.startsWith("💬") -> "💬"; txt.startsWith("📞") -> "📞"; txt.startsWith("✉️") -> "✉️"; txt.startsWith("📧") -> "📧"
            else -> "⚡"
        }
        val title = txt.removePrefix(icon).trim()
        row(icon, title, "${dayLabel(t)} ${TIME.format(Date(t))} · ${left(t)}", { Sched.cancelId(this, o.getInt("id")); render() })
    }

    private fun row(icon: String, title: String, sub: String, onCancel: (() -> Unit)?, act: String = "✕") {
        val r = hbox().apply { background = round(C.CARD, dp(14).toFloat()); setPadding(dp(14), dp(12), dp(10), dp(12)) }
        r.addView(text(icon, 22f).apply { setPadding(0, 0, dp(12), 0) })
        val col = vbox()
        col.addView(text(title, 15f, C.TEXT, true).apply { maxLines = 3 })
        col.addView(text(sub, 12.5f, C.GOLD).apply { setPadding(0, dp(3), 0, 0) })
        r.addView(col, lp(0, WRAP, 1f))
        if (onCancel != null) r.addView(text(act, 18f, if (act == "✕") C.MUTED else C.GREEN).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(6), dp(6)); setOnClickListener { onCancel() } })
        box.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    private fun section(t: String, n: Int) = box.addView(text(if (n >= 0) "$t  ($n)" else t, 15.5f, C.TEXT, true),
        lp(MATCH, WRAP).apply { topMargin = dp(22) })
    private fun empty(t: String) = box.addView(text(t, 13f, C.MUTED).apply {
        background = round(C.CARD, dp(12).toFloat()); setPadding(dp(14), dp(12), dp(14), dp(12)) }, lp(MATCH, WRAP).apply { topMargin = dp(8) })

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun calendar(): List<Triple<Long, String, Boolean>> {
        if (!has(Manifest.permission.READ_CALENDAR)) return emptyList()
        val now = System.currentTimeMillis(); val end = now + 7 * 86400000L
        val out = ArrayList<Triple<Long, String, Boolean>>()
        try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, now - 86400000L); android.content.ContentUris.appendId(it, end) }.build()
            contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY),
                null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < 25) {
                    val allDay = c.getInt(2) == 1
                    var t = c.getLong(0)
                    if (allDay) { // all-day instances are stored at UTC midnight
                        val u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = t }
                        t = Calendar.getInstance().apply { clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH)) }.timeInMillis
                        if (t < startOfDay(now)) continue
                    } else if (t < now) continue
                    out.add(Triple(t, c.getString(1) ?: "(no title)", allDay))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun nextBrief(): Long {
        val (h, m) = Prefs.briefTime.split(":").map { it.trim().toIntOrNull() ?: 0 }.let { (it.getOrNull(0) ?: 8) to (it.getOrNull(1) ?: 0) }
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    companion object {
        private val TIME = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
        private val DAY = SimpleDateFormat("EEE, d MMM", Locale.ENGLISH)
        private fun startOfDay(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

        fun dayLabel(t: Long): String {
            val d = ((startOfDay(t) - startOfDay(System.currentTimeMillis())) / 86400000L).toInt()
            return when (d) { 0 -> "Aaj"; 1 -> "Kal"; 2 -> "Parso"; else -> DAY.format(Date(t)) }
        }

        fun left(t: Long): String {
            val m = ((t - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            return when { m < 60 -> "$m min baad"; m < 1440 -> "${m / 60} ghante ${m % 60} min baad"; else -> "${m / 1440} din baad" }
        }

        fun count(ctx: android.content.Context) = Sched.items(ctx).size
    }
}
