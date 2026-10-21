package com.raxvaayan.hukum

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** One-shot TTS for reminders firing when the app may be in background. */
object Speaker {
    private var tts: TextToSpeech? = null
    fun say(ctx: Context, text: String) {
        try {
            tts?.shutdown()
            tts = TextToSpeech(ctx.applicationContext) { s ->
                if (s == TextToSpeech.SUCCESS) {
                    val lang = when { text.any { it.code in 0x0A80..0x0AFF } -> "gu-IN"; text.any { it.code in 0x0900..0x097F } -> "hi-IN"; else -> Prefs.lang }
                    if ((tts?.setLanguage(Locale.forLanguageTag(lang)) ?: -1) < 0) tts?.language = Locale.forLanguageTag("hi-IN")
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rem")
                }
            }
        } catch (_: Exception) {}
    }
}
