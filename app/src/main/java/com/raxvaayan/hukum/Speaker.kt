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
                    tts?.language = Locale.forLanguageTag(Prefs.lang)
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rem")
                }
            }
        } catch (_: Exception) {}
    }
}
