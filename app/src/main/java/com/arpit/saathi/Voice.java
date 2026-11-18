package com.arpit.saathi;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speech in and out, using the phone's own engines (free, no API key).
 *
 * Listening deliberately avoids Saathi's own recognition service: when Saathi is the digital assistant,
 * Android makes Saathi's empty stub the default recogniser, so asking for "the default" would hear nothing.
 */
public final class Voice {
    public interface Listener {
        void onListening(boolean on);

        void onPartial(String text);

        void onHeard(String text);

        void onSpeaking(boolean on);

        void onProblem(String message);
    }

    public static final int REQ_DIALOG = 71;
    private static final String GOOGLE = "com.google.android.googlequicksearchbox";

    private final Activity a;
    private final Listener l;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final List<String> queued = new ArrayList<>();
    private SpeechRecognizer rec;
    private boolean useDialog;
    private int pendingUtterances;
    /** Bumped on every stop, so callbacks from cancelled speech are ignored. */
    private int session;

    public Voice(Activity activity, Listener listener) {
        a = activity;
        l = listener;
        tts = new TextToSpeech(a.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                a.runOnUiThread(() -> l.onProblem("This phone has no text-to-speech voice. Install \"Speech "
                        + "Services by Google\" from the Play Store."));
                return;
            }
            Locale in = new Locale("en", "IN");
            int r = tts.setLanguage(in);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            tts.setSpeechRate(Prefs.sp(a).getFloat("voiceRate", 1.0f));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    a.runOnUiThread(() -> l.onSpeaking(true));
                }

                @Override
                public void onDone(String id) {
                    finished(id);
                }

                @Override
                public void onError(String id) {
                    finished(id);
                }

                @Override
                public void onStop(String id, boolean interrupted) {
                    finished(id);
                }
            });
            ttsReady = true;
            a.runOnUiThread(() -> {
                for (String s : queued) speak(s);
                queued.clear();
            });
        });
    }

    private synchronized void finished(String id) {
        if (id == null || !id.startsWith("s" + session + "-")) return; // from speech that was stopped
        pendingUtterances = Math.max(0, pendingUtterances - 1);
        if (pendingUtterances == 0) a.runOnUiThread(() -> l.onSpeaking(false));
    }

    // ---------------------------------------------------------------- speaking

    public void speak(String text) {
        String say = forSpeech(text);
        if (say.isEmpty()) return;
        if (!ttsReady) {
            queued.add(text);
            return;
        }
        stopListening();
        // engines cap one utterance at a few thousand characters: speak sentence groups one after another
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : say.split("(?<=[.!?])\\s+")) {
            if (cur.length() + sentence.length() > 400 && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(sentence).append(' ');
        }
        if (cur.length() > 0) chunks.add(cur.toString().trim());
        int s;
        synchronized (this) {
            pendingUtterances += chunks.size();
            s = session;
        }
        for (int i = 0; i < chunks.size(); i++) {
            tts.speak(chunks.get(i), TextToSpeech.QUEUE_ADD, null, "s" + s + "-" + System.nanoTime() + "-" + i);
        }
    }

    public void stopSpeaking() {
        boolean was;
        synchronized (this) {
            was = pendingUtterances > 0 || (tts != null && tts.isSpeaking());
            session++;
            pendingUtterances = 0;
        }
        if (tts != null) tts.stop();
        if (was) l.onSpeaking(false);
    }

    public boolean speaking() {
        return tts != null && tts.isSpeaking();
    }

    public void setRate(float rate) {
        Prefs.sp(a).edit().putFloat("voiceRate", rate).apply();
        if (tts != null) tts.setSpeechRate(rate);
    }

    // ---------------------------------------------------------------- listening

    /** A recogniser that is not Saathi's own stub: on-device first, then Google's, then any other. */
    private SpeechRecognizer recogniser() {
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(a)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(a);
        }
        List<ResolveInfo> services = a.getPackageManager().queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        ComponentName pick = null;
        for (ResolveInfo ri : services) {
            if (ri.serviceInfo == null || a.getPackageName().equals(ri.serviceInfo.packageName)) continue;
            ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            if (GOOGLE.equals(ri.serviceInfo.packageName)) {
                pick = cn;
                break;
            }
            if (pick == null) pick = cn;
        }
        return pick == null ? null : SpeechRecognizer.createSpeechRecognizer(a, pick);
    }

    private Intent intent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, a.getPackageName());
    }

    public void listen() {
        stopSpeaking();
        if (!useDialog && rec == null) {
            try {
                rec = recogniser();
            } catch (Exception e) {
                rec = null;
            }
            if (rec == null) useDialog = true;
            else rec.setRecognitionListener(new Heard());
        }
        if (useDialog) { // the system's own "speak now" popup, which needs no recogniser binding
            try {
                a.startActivityForResult(intent(), REQ_DIALOG);
            } catch (Exception e) {
                l.onProblem("No speech recogniser on this phone. Install the Google app, or type instead.");
            }
            return;
        }
        try {
            rec.startListening(intent());
            l.onListening(true);
        } catch (Exception e) {
            l.onListening(false);
            l.onProblem("Couldn't start listening: " + e.getMessage());
        }
    }

    public void stopListening() {
        if (rec != null) {
            try {
                rec.cancel();
            } catch (Exception ignored) {
            }
        }
        l.onListening(false);
    }

    /** Call from the activity's onActivityResult; returns true when it was the speech popup. */
    public boolean handleResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_DIALOG) return false;
        if (resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) l.onHeard(r.get(0).trim());
        }
        return true;
    }

    public void destroy() {
        if (rec != null) {
            try {
                rec.destroy();
            } catch (Exception ignored) {
            }
            rec = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    private class Heard implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { l.onListening(true); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) l.onPartial(r.get(0));
        }

        @Override
        public void onResults(Bundle b) {
            l.onListening(false);
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) l.onHeard(r.get(0).trim());
            else l.onProblem("I didn't catch that.");
        }

        @Override
        public void onError(int error) {
            l.onListening(false);
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    l.onProblem("I didn't catch that.");
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    l.onProblem("Saathi needs microphone permission.");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    l.onProblem("Speech recognition needs the internet right now.");
                    break;
                default: // the in-app recogniser misbehaves on this phone: use the system popup from now on
                    useDialog = true;
                    if (rec != null) {
                        try {
                            rec.destroy();
                        } catch (Exception ignored) {
                        }
                        rec = null;
                    }
                    l.onProblem("Switching to the system microphone. Tap the mic again.");
            }
        }
    }

    // ---------------------------------------------------------------- text → something worth hearing

    private static final String[][] TEX = {
            {"\\\\[{}]", ""}, {"\\\\[,;! ]", " "},
            {"\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}", "$1 over $2"},
            {"\\\\sqrt\\{([^{}]*)\\}", "square root of $1"},
            {"\\^\\{?2\\}?", " squared"}, {"\\^\\{?3\\}?", " cubed"}, {"\\^\\{?T\\}?", " transpose"},
            {"\\^\\{?-1\\}?", " inverse"},
            {"\\^\\{([^{}]*)\\}", " to the power $1"}, {"\\^(\\w)", " to the power $1"},
            {"_\\{([^{}]*)\\}", " sub $1"}, {"_(\\w)", " $1"},
            {"\\\\(times|cdot)", " times "}, {"\\\\leq?", " at most "}, {"\\\\geq?", " at least "},
            {"\\\\neq", " not equal to "}, {"\\\\approx", " approximately "}, {"\\\\to|\\\\rightarrow", " tends to "},
            {"\\\\infty", "infinity"}, {"\\\\sum", "the sum of "}, {"\\\\int", "the integral of "},
            {"\\\\prod", "the product of "}, {"\\\\partial", "partial "}, {"\\\\nabla", "gradient "},
            {"\\\\in\\b", " in "}, {"\\\\log", "log "}, {"\\\\ln", "natural log "}, {"\\\\exp", "e to the "},
            {"\\\\det", "determinant of "}, {"\\\\(begin|end)\\{[a-z]*\\}", " "}, {"\\\\(left|right|big|Big)", ""},
            {"\\\\(alpha|beta|gamma|delta|epsilon|theta|lambda|mu|sigma|pi|rho|phi|omega|tau|eta)", "$1"},
            {"\\\\[a-zA-Z]+", " "}, {"[{}]", ""}, {"=", " equals "}, {"(?<=\\s)-(?=\\s)", " minus "},
            {"\\+", " plus "}, {"\\\\", " "},
    };

    /** Removes markdown and turns LaTeX into words, so the voice doesn't read symbols out. */
    public static String forSpeech(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?s)```.*?```", " (code shown on screen) ");
        // maths: $$..$$, $..$, \( \), \[ \]
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\$\\$(.+?)\\$\\$|\\$([^$]+?)\\$|\\\\\\((.+?)\\\\\\)|\\\\\\[(.+?)\\\\\\]", java.util.regex.Pattern.DOTALL)
                .matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String tex = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2)
                    : m.group(3) != null ? m.group(3) : m.group(4);
            for (int pass = 0; pass < 3; pass++) {
                for (String[] r : TEX) tex = tex.replaceAll(r[0], r[1]);
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(" " + tex + " "));
        }
        m.appendTail(out);
        s = out.toString()
                .replaceAll("(?m)^\\s*#+\\s*", "")
                .replaceAll("(?m)^\\s*[-*•]\\s+", "")
                .replaceAll("(?m)^\\s*>\\s?", "")
                .replaceAll("(?m)^\\s*(TASK|LOG|MISSION|REVISED):.*$", "")
                .replace("**", "").replace("`", "").replace("|", ", ")
                .replaceAll("https?://\\S+", "the link on screen")
                .replaceAll("\\s+", " ").trim();
        return s;
    }
}
