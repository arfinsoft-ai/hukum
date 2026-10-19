# 👑 HUKUM

Personal Android assistant — type, bolo, ya "Hukum" bol ke jagao. Kaam karta hai: alarm, timer, call, WhatsApp send/reply, email, app kholna.

## Install
Latest APK: **Releases → latest → `hukum.apk`** (har push pe GitHub Actions naya build banata hai, same signing key — update seedha install ho jaata hai).

## First-time setup (OnePlus / OxygenOS)
App → ⚙️ Settings → "Phone setup" ke steps 0–4:
0. App info → ⋮ → **Allow restricted settings** (sideloaded app ke liye zaroori)
1. Permissions — contacts, call, mic, notifications
2. Accessibility → **HUKUM Auto-Send** ON (WhatsApp/Gmail ka Send button dabata hai)
3. Notification access → **HUKUM WhatsApp Reply** ON
4. Battery → **Unrestricted**

Phir AI section mein API key daalo (Groq / Gemini / OpenRouter / koi bhi OpenAI-compatible endpoint).

## Stack
Kotlin, no AndroidX. LLM tool-calling (OpenAI format) → native executors. Android SpeechRecognizer (hi/gu/en) + TextToSpeech. Offline wake word: Vosk small Hindi model (downloaded on first enable).

## Roadmap
- Phase 2: Blinkit add-to-cart (v1.0.5, accessibility; checkout manual)
- Phase 3: Uber booking
- Phase 4: chaining, reminders, RAXVAAYAN apps integration
