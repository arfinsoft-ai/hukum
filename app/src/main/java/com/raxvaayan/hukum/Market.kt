package com.raxvaayan.hukum

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone

/** 📈 Watchlist, price alerts (polled every 5 min in market hours), 9:10 open + 3:35 close brief. Quotes via Yahoo Finance. */
object Market {
    private const val CH = "hukum_market"
    private const val RC = 777_200
    private val IST = TimeZone.getTimeZone("Asia/Kolkata")
    private fun sp(c: Context) = c.getSharedPreferences("hukum_market", Context.MODE_PRIVATE)
    private fun arr(c: Context, k: String) = try { JSONArray(sp(c).getString(k, "[]")) } catch (e: Exception) { JSONArray() }
    private fun put(c: Context, k: String, a: JSONArray) = sp(c).edit().putString(k, a.toString()).apply()

    fun briefOn(c: Context) = sp(c).getBoolean("brief", false)
    fun setBrief(c: Context, on: Boolean) { sp(c).edit().putBoolean("brief", on).apply(); schedule(c) }

    // ---------- quotes ----------
    private val ALIAS = mapOf(
        "nifty" to "^NSEI", "nifty50" to "^NSEI", "nifty 50" to "^NSEI", "banknifty" to "^NSEBANK", "bank nifty" to "^NSEBANK",
        "sensex" to "^BSESN", "finnifty" to "NIFTY_FIN_SERVICE.NS", "midcap" to "^NSEMDCP50", "vix" to "^INDIAVIX", "india vix" to "^INDIAVIX",
        "gold" to "GC=F", "silver" to "SI=F", "crude" to "CL=F", "crude oil" to "CL=F", "usdinr" to "INR=X", "dollar" to "INR=X",
        "dow" to "^DJI", "nasdaq" to "^IXIC", "s&p" to "^GSPC", "sp500" to "^GSPC", "nikkei" to "^N225", "hang seng" to "^HSI", "btc" to "BTC-USD", "bitcoin" to "BTC-USD")

    fun sym(q: String): String {
        val k = q.trim().lowercase()
        ALIAS[k]?.let { return it }
        if (k.startsWith("^") || k.contains(".") || k.contains("=")) return q.trim().uppercase()
        return q.trim().uppercase().replace(" ", "") + ".NS"
    }

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) HUKUM")
        val code = c.responseCode
        val t = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect(); if (code !in 200..299) throw Exception("quote $code"); return t
    }

    class Q(val sym: String, val name: String, val ltp: Double, val prev: Double) {
        val chg get() = ltp - prev
        val pct get() = if (prev != 0.0) chg / prev * 100 else 0.0
        fun line() = "$name ${fmt(ltp)} (${if (chg >= 0) "▲" else "▼"} ${"%.2f".format(Math.abs(pct))}%)"
    }

    private fun fmt(v: Double) = if (v >= 1000) "%,.0f".format(v) else "%.2f".format(v)

    fun quote(q: String): Q? {
        var s = sym(q)
        for (attempt in 0..1) {
            try {
                val j = JSONObject(http("https://query1.finance.yahoo.com/v8/finance/chart/" + Uri.encode(s) + "?range=1d&interval=1d"))
                val m = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                val ltp = m.getDouble("regularMarketPrice")
                val prev = m.optDouble("chartPreviousClose", m.optDouble("previousClose", ltp))
                val name = ALIAS.entries.firstOrNull { it.value == s }?.key?.replaceFirstChar { it.uppercase() } ?: s.removeSuffix(".NS").removeSuffix(".BO")
                return Q(s, name, ltp, prev)
            } catch (e: Exception) {
                if (attempt == 0 && s.endsWith(".NS")) s = search(q) ?: return null else return null
            }
        }
        return null
    }

    private fun search(q: String): String? = try {
        val j = JSONObject(http("https://query2.finance.yahoo.com/v1/finance/search?quotesCount=6&newsCount=0&q=" + Uri.encode(q)))
        val qs = j.getJSONArray("quotes")
        (0 until qs.length()).map { qs.getJSONObject(it) }.let { l -> (l.firstOrNull { it.optString("exchange") == "NSI" } ?: l.firstOrNull { it.optString("exchange") == "BSE" })?.optString("symbol") }
    } catch (e: Exception) { null }

    // ---------- watchlist ----------
    fun watchAdd(c: Context, names: List<String>): String {
        val a = arr(c, "watch"); val have = (0 until a.length()).map { a.getString(it) }.toMutableSet(); val added = ArrayList<String>()
        for (n in names) { val q = quote(n) ?: continue; if (have.add(q.sym)) { a.put(q.sym); added.add(q.name) } }
        put(c, "watch", a)
        return if (added.isEmpty()) "NOT DONE: stock nahi mila / pehle se hai." else "DONE: watchlist mein ${added.joinToString()}."
    }

    fun watchRemove(c: Context, name: String): String {
        val a = arr(c, "watch"); val s = sym(name); val keep = JSONArray(); var hit = false
        for (i in 0 until a.length()) { val x = a.getString(i); if (x.equals(s, true) || x.removeSuffix(".NS").equals(name.trim(), true)) hit = true else keep.put(x) }
        put(c, "watch", keep); return if (hit) "DONE: watchlist se hataya." else "Watchlist mein nahi tha."
    }

    fun watchList(c: Context): String {
        val a = arr(c, "watch")
        if (a.length() == 0) return "Watchlist khaali hai. Bolo: \"IDEA aur TATAMOTORS watchlist mein daalo\"."
        return "📈 Watchlist:\n" + (0 until a.length()).mapNotNull { quote(a.getString(it))?.line() }.joinToString("\n")
    }

    fun watchSyms(c: Context) = arr(c, "watch").let { a -> (0 until a.length()).map { a.getString(it) } }

    // ---------- alerts ----------
    fun alertAdd(c: Context, name: String, op: String, price: Double): String {
        val q = quote(name) ?: return "NOT DONE: '$name' ka price nahi mila."
        val above = op.trim().let { it == ">" || it.startsWith("above") || it.startsWith("upar") || it.startsWith("cross") }
        val a = arr(c, "alerts")
        a.put(JSONObject().put("id", System.currentTimeMillis()).put("sym", q.sym).put("name", q.name).put("above", above).put("price", price))
        put(c, "alerts", a); schedule(c)
        return "DONE: alert — ${q.name} ${if (above) "₹${fmt(price)} ke upar" else "₹${fmt(price)} ke neeche"} jaye to bataunga. Abhi ${fmt(q.ltp)}. (Market time mein har 5 min check)"
    }

    fun alerts(c: Context): List<JSONObject> = arr(c, "alerts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    fun alertRemove(c: Context, idOrName: String): String {
        val keep = JSONArray(); var hit = false
        for (o in alerts(c)) if (o.optString("id") == idOrName || o.getString("name").equals(idOrName.trim(), true) || o.getString("sym").equals(sym(idOrName), true)) hit = true else keep.put(o)
        put(c, "alerts", keep); return if (hit) "DONE: alert hataya." else "Wo alert nahi mila."
    }

    // ---------- timing ----------
    private fun ist() = Calendar.getInstance(IST)
    private fun weekday(c: Calendar) = c.get(Calendar.DAY_OF_WEEK) !in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
    private fun at(base: Calendar, h: Int, m: Int) = (base.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun marketOpen(): Boolean { val n = ist(); val m = n.get(Calendar.HOUR_OF_DAY) * 60 + n.get(Calendar.MINUTE); return weekday(n) && m in (9 * 60 + 15)..(15 * 60 + 30) }

    private fun next(c: Context): Long? {
        val now = System.currentTimeMillis(); val cands = ArrayList<Long>()
        for (d in 0..4) {
            val day = ist().apply { add(Calendar.DAY_OF_MONTH, d) }
            if (!weekday(day)) continue
            if (briefOn(c)) { cands.add(at(day, 9, 10)); cands.add(at(day, 15, 35)) }
            if (alerts(c).isNotEmpty()) cands.add(at(day, 9, 16))
        }
        if (alerts(c).isNotEmpty() && marketOpen()) cands.add(now + 5 * 60000L)
        return cands.filter { it > now + 30000 }.minOrNull()
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, RC, Intent(c, Alarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        val t = next(c) ?: return
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) }
    }

    private fun tick(c: Context) {
        val n = ist(); val m = n.get(Calendar.HOUR_OF_DAY) * 60 + n.get(Calendar.MINUTE)
        if (marketOpen()) checkAlerts(c)
        if (briefOn(c) && weekday(n)) {
            if (m in (9 * 60 + 9)..(9 * 60 + 13)) notify(c, 1, "📈 Market khulne wala hai", openBrief(c), true)
            if (m in (15 * 60 + 34)..(15 * 60 + 40)) notify(c, 2, "📊 Market band — aaj ka hisaab", closeBrief(c), true)
        }
    }

    private fun checkAlerts(c: Context) {
        val keep = JSONArray()
        for (o in alerts(c)) {
            val q = quote(o.getString("sym"))
            val hit = q != null && (if (o.getBoolean("above")) q.ltp >= o.getDouble("price") else q.ltp <= o.getDouble("price"))
            if (hit) notify(c, (o.getLong("id") % 1000).toInt() + 10, "🔔 ${q!!.name} ${if (o.getBoolean("above")) "upar gaya" else "neeche aaya"}: ₹${fmt(q.ltp)}",
                "Aapka alert ₹${fmt(o.getDouble("price"))} tha. ${q.line()}", true)
            else keep.put(o)
        }
        put(c, "alerts", keep)
    }

    fun openBrief(c: Context): String {
        val g = listOf("dow", "nasdaq", "nikkei", "hang seng", "crude", "gold", "dollar").mapNotNull { quote(it)?.line() }
        val idx = listOf("nifty", "bank nifty", "vix").mapNotNull { quote(it)?.let { q -> "${q.name} kal ${fmt(q.prev)}" } }
        val w = watchSyms(c).take(8).mapNotNull { quote(it)?.line() }
        return "🌍 Global: " + g.joinToString(" · ") + "\n🇮🇳 " + idx.joinToString(" · ") +
            (if (w.isNotEmpty()) "\n👀 Watchlist: " + w.joinToString(" · ") else "") +
            (if (alerts(c).isNotEmpty()) "\n🔔 ${alerts(c).size} alerts active" else "")
    }

    fun closeBrief(c: Context): String {
        val idx = listOf("nifty", "bank nifty", "sensex").mapNotNull { quote(it)?.line() }
        val w = watchSyms(c).take(10).mapNotNull { quote(it) }.sortedByDescending { it.pct }
        val dhan = if (Prefs.dhanToken.isNotBlank()) "\n💼 " + Dhan.portfolio("positions").lines().take(4).joinToString(" | ") else ""
        return "🇮🇳 " + idx.joinToString(" · ") +
            (if (w.isNotEmpty()) "\n🏆 Top: ${w.first().line()}\n🔻 Bottom: ${w.last().line()}" else "") + dhan
    }

    private fun notify(c: Context, id: Int, title: String, text: String, speak: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Market alerts", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(8000 + id, Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_mono).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build())
        if (speak && id >= 10) Speaker.say(c, title)
    }

    class Alarm : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pr = goAsync()
            Thread { try { Prefs.init(c); tick(c) } catch (_: Exception) {} finally { schedule(c); pr.finish() } }.start()
        }
    }

    // ---------- LLM tools ----------
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    private fun fn(name: String, desc: String, props: JSONObject, req: List<String> = listOf()) = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    fun defs(a: JSONArray) {
        a.put(fn("stock_price", "Share/index/commodity ka abhi ka price (NSE symbol jaise IDEA, TATAMOTORS; ya nifty, bank nifty, sensex, gold, crude).",
            JSONObject().put("names", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))), listOf("names")))
        a.put(fn("watchlist", "Watchlist: action add | remove | show.",
            JSONObject().put("action", p("string", "add | remove | show")).put("names", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))), listOf("action")))
        a.put(fn("price_alert", "Price alert: action add (name, op above/below, price) | remove (name) | list.",
            JSONObject().put("action", p("string", "add | remove | list")).put("name", p("string", "symbol")).put("op", p("string", "above | below")).put("price", p("number", "level")), listOf("action")))
        a.put(fn("market_brief", "Market brief abhi: which = open (global cues + watchlist) | close (aaj ka hisaab). on=true/false se roz 9:10 & 3:35 auto brief ON/OFF.",
            JSONObject().put("which", p("string", "open | close")).put("on", p("boolean", "roz auto brief"))))
    }

    fun run(h: ToolHost, name: String, a: JSONObject): String? = when (name) {
        "stock_price" -> { val l = a.optJSONArray("names") ?: JSONArray(); (0 until l.length()).map { quote(l.getString(it))?.line() ?: "${l.getString(it)}: nahi mila" }.joinToString("\n") }
        "watchlist" -> when (a.optString("action")) {
            "add" -> watchAdd(h.ctx, a.optJSONArray("names")?.let { l -> (0 until l.length()).map { l.getString(it) } } ?: listOf())
            "remove" -> a.optJSONArray("names")?.let { l -> (0 until l.length()).joinToString("\n") { watchRemove(h.ctx, l.getString(it)) } } ?: "Naam do."
            else -> watchList(h.ctx)
        }
        "price_alert" -> when (a.optString("action")) {
            "add" -> alertAdd(h.ctx, a.getString("name"), a.optString("op", "above"), a.getDouble("price"))
            "remove" -> alertRemove(h.ctx, a.getString("name"))
            else -> alerts(h.ctx).joinToString("\n") { "🔔 ${it.getString("name")} ${if (it.getBoolean("above")) ">" else "<"} ₹${fmt(it.getDouble("price"))}" }.ifEmpty { "Koi alert nahi." }
        }
        "market_brief" -> {
            if (a.has("on")) { setBrief(h.ctx, a.getBoolean("on")); "DONE: roz market brief ${if (a.getBoolean("on")) "ON (9:10 & 3:35, Mon–Fri)" else "OFF"}." }
            else if (a.optString("which") == "close") closeBrief(h.ctx) else openBrief(h.ctx)
        }
        else -> null
    }
}
