package com.raxvaayan.hukum

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Dhan trading API — read-only portfolio/positions/funds. Needs access token in Settings. */
object Dhan {
    private fun get(path: String): JSONArray {
        val token = Prefs.dhanToken
        if (token.isBlank()) throw Exception("Dhan access-token Settings mein daalo (⚙️ → Dhan).")
        val conn = URL("https://api.dhan.co/v2$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        conn.setRequestProperty("access-token", token)
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw Exception("Dhan $code: ${txt.take(150)}")
        return if (txt.trimStart().startsWith("[")) JSONArray(txt) else JSONArray().put(JSONObject(txt))
    }

    fun portfolio(what: String): String = try {
        when (what.lowercase()) {
            "holdings" -> holdings()
            "funds" -> funds()
            else -> positions()
        }
    } catch (e: Exception) { "NOT DONE: ${e.message}" }

    private fun inr(v: Double) = "₹" + "%,.0f".format(v)

    private fun positions(): String {
        val a = get("/positions")
        if (a.length() == 0) return "Aaj koi open position nahi."
        var pnl = 0.0; val rows = ArrayList<String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val p = o.optDouble("realizedProfit", 0.0) + o.optDouble("unrealizedProfit", 0.0)
            pnl += p
            val sym = o.optString("tradingSymbol", o.optString("securityId"))
            val qty = o.optInt("netQty")
            if (qty != 0 || p != 0.0) rows.add("$sym  qty $qty  ${if (p >= 0) "+" else ""}${inr(p)}")
        }
        return "Aaj ka P&L: ${if (pnl >= 0) "+" else ""}${inr(pnl)}\n" + rows.take(12).joinToString("\n")
    }

    private fun holdings(): String {
        val a = get("/holdings")
        if (a.length() == 0) return "Koi holding nahi."
        val rows = ArrayList<String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            rows.add("${o.optString("tradingSymbol")}  ${o.optInt("totalQty")} qty  avg ${inr(o.optDouble("avgCostPrice"))}")
        }
        return rows.take(15).joinToString("\n")
    }

    private fun funds(): String {
        val o = get("/fundlimit").getJSONObject(0)
        return "Available: ${inr(o.optDouble("availabelBalance"))}\nUsed: ${inr(o.optDouble("utilizedAmount"))}"
    }
}
