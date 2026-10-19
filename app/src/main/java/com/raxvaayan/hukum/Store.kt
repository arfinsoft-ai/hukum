package com.raxvaayan.hukum

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny persistent key→list store for user notes/memories. */
object Store {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("hukum_store", Context.MODE_PRIVATE)

    fun add(ctx: Context, key: String, text: String) {
        val arr = read(ctx, key)
        arr.put(JSONObject().put("t", text.trim()).put("at", System.currentTimeMillis()))
        sp(ctx).edit().putString(key, arr.toString()).apply()
    }

    fun list(ctx: Context, key: String, query: String = ""): String {
        val arr = read(ctx, key)
        val q = query.trim().lowercase()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i).optString("t")
            if (q.isEmpty() || t.lowercase().contains(q)) out.add("• $t")
        }
        return out.joinToString("\n")
    }

    fun remove(ctx: Context, key: String, query: String): Boolean {
        val arr = read(ctx, key); val q = query.trim().lowercase()
        val keep = JSONArray(); var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!removed && o.optString("t").lowercase().contains(q)) removed = true else keep.put(o)
        }
        sp(ctx).edit().putString(key, keep.toString()).apply()
        return removed
    }

    private fun read(ctx: Context, key: String) = try { JSONArray(sp(ctx).getString(key, "[]")) } catch (e: Exception) { JSONArray() }
}
