# Tidyo

AI file manager for Android. It reads your files' names and dates, asks your AI provider (with **your own API key (Gemini, OpenAI, Claude, Groq, OpenRouter, xAI, Mistral or DeepSeek, detected automatically)**) how to tidy them, shows the plan as a receipt, applies it, and lets you undo everything for 30 days.

- **UI**: the exact screens from `design/` (HTML/CSS + animations) hosted in a WebView — `app/src/main/assets/www`.
- **Native layer** (Kotlin): Gemini client, file scan, duplicate detection, plan, apply/undo, 30-day trash — `app/src/main/java/app/tidyo`.
- **Privacy**: file names, sizes and dates go to Gemini; for badly named photos/PDFs a small preview (max 512px) is sent so they can be named properly. Originals never leave the phone. The API key is stored encrypted with the Android Keystore.

## Install
Every push to `main` builds an APK: open the **Releases** page → `latest` → `Tidyo.apk`. On first launch allow "all files access".

## Build locally
Android Studio (JDK 17), open this folder, run `app`. The shared signing key in `app/tidyo-shared.jks` is public on purpose; replace it for a store release.

## Dev preview in a browser
`cd app/src/main/assets/www && python3 -m http.server 8765`, then load `index.html` with `dev/mock.js` injected (see `dev/shots.js`).
