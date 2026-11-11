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

## What it does

- Capture any screen with the assistant gesture, or Share → Saathi. Several captures of one long post
  (same app, within 90 seconds) join into one item.
- Sorts into opportunities, government notices and exams, algorithms, tools and notes; researches each
  in the background; reminds you before deadlines.
- Tasks with reminders, and an Ask screen that answers from what you saved.
- Models: Gemini, with Groq and OpenRouter (open-source models) as optional backups.

## Privacy

- Items, screenshots, notes and tasks stay in the app's private storage on the phone. No server, no
  analytics, no backups.
- API keys are encrypted with the Android Keystore.
- Captures are sent to the model provider you configured so they can be read. Banking, payment and
  password apps are never captured.
- Optional app lock using the phone's fingerprint or screen lock.

## Layout

- `assist/` — the assistant hook that captures the screen
- `Capture.java` — storing and merging captures
- `Processor.java` — sorting and research prompts
- `Llm.java`, `Gemini.java` — model providers
- `Memory.java`, `AskActivity.java` — answering from saved items
- `Reminders.java`, `AlarmReceiver.java` — deadline alerts, task reminders, daily digest
- `Secure.java`, `LockActivity.java` — key encryption and app lock
