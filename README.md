# Saathi

A personal capture assistant for Android. When you see something worth keeping (an internship post,
an algorithm, a tool), call the assistant on that screen. Saathi saves the screen text and a
screenshot, sorts it with Gemini, researches it in the background, and reminds you before deadlines.

Everything runs on the phone. The only network calls are to the Gemini API with your own key.

## Get the APK without installing anything

1. Push this folder to a GitHub repository (branch `main`).
2. GitHub builds it automatically (Actions tab, about 5 minutes).
3. Open the repository's **Releases** on your phone and download `Saathi.apk`.

Every later push builds a new APK that installs over the old one and keeps your data.

## First run

1. Settings → paste a free Gemini key (aistudio.google.com/apikey) → Test key.
2. Settings → Open assistant settings → choose Saathi as the digital assistant app, and keep
   "Use text from screen" and "Use screenshot" on.
3. On any post: long-press home or power (or swipe up from a bottom corner). "Share → Saathi" also works.

## Layout

- `assist/` — the assistant hook that captures the screen
- `Processor.java` — sorting and research prompts
- `Reminders.java`, `AlarmReceiver.java` — deadline alerts and the daily digest
- `MainActivity`, `DetailActivity`, `SettingsActivity` — the three screens
