package com.raxvaayan.hukum

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI-compatible chat completions client (Groq, Gemini, OpenRouter, RAX AI router...). */
object Llm {
    fun chat(messages: JSONArray, tools: JSONArray): JSONObject {
        if (Prefs.apiKey.isBlank()) throw Exception("Settings mein API key daalo (⚙️)")
        val conn = URL(Prefs.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${Prefs.apiKey}")
        val body = JSONObject()
            .put("model", Prefs.model)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
            .put("temperature", 0.2)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw Exception("LLM error $code: ${txt.take(250)}")
        return JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    }
}
