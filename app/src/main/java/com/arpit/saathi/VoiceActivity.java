package com.arpit.saathi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.arpit.saathi.gate.Gate;
import com.arpit.saathi.gate.GateDb;
import com.arpit.saathi.gate.Tutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A spoken conversation. Three modes:
 * "assistant" (everything Saathi knows), "coach" (GATE progress and teaching),
 * "topic" (teach one syllabus topic), "question" (talk through one mock question).
 * Everything said is also shown as text, and typing works too: replies are always spoken.
 */
public class VoiceActivity extends Activity implements Voice.Listener {
    private static final int REQ_MIC = 81;
    private static final float[] RATES = {1.0f, 1.15f, 1.3f, 0.9f};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String[]> turns = new ArrayList<>(); // {what the user said, the reply}
    private Voice voice;
    private String mode, key;
    private long qid, mockId;
    // review mode: the questions to go through, where we are (-1 = intro), and replies made ahead of time
    private List<Long> order = new ArrayList<>();
    private int idx = -1, covered;
    private final java.util.Map<Integer, String> ready = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> making = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final List<String[]> qTurns = new ArrayList<>();
    private String lastReply = "";
    /** A question the student asked about by number, outside the review order (-1 when none). */
    private long detour = -1;
    private static final String[] WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty"};
    private boolean busy, handsFree, closing, listening;
    /** Whether to start listening by itself when the current speech ends (hands-free). */
    private boolean listenAfterSpeech;
    private int misses;
    private long started;
    private LinearLayout msgs;
    private ScrollView scroll;
    private TextView status, handsChip, rateChip, partialBubble;
    private ImageButton mic;
    private EditText input;

    public static Intent assistant(Activity a) {
        return new Intent(a, VoiceActivity.class).putExtra("mode", "assistant");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "assistant";
        key = getIntent().getStringExtra("key");
        qid = getIntent().getLongExtra("qid", -1);
        mockId = getIntent().getLongExtra("mock", -1);
        handsFree = Prefs.sp(this).getBoolean("handsFree", true);
        started = System.currentTimeMillis();

        String title, sub;
        switch (mode) {
            case "topic": {
                Gate.Topic t = GateDb.get(this).topic(key);
                title = "Revise: " + t.name;
                sub = "Voice tutor";
                break;
            }
            case "question": {
                Gate.Q q = GateDb.get(this).q(qid);
                title = "Question " + (q == null ? "" : q.n);
                sub = "Talk it through";
                break;
            }
            case "review": {
                com.arpit.saathi.gate.Gate.Mock m = GateDb.get(this).mock(mockId);
                title = "Review: " + (m == null ? "mock" : m.title);
                sub = "Say next, back, repeat, or ask anything";
                break;
            }
            case "coach":
                title = "GATE coach";
                sub = "Knows your mocks, topics and log";
                break;
            default:
                title = "Talk to Saathi";
                sub = "Knows your inbox, tasks and GATE prep";
        }
        setContentView(build(title, sub));
        voice = new Voice(this, this);

        if ("assistant".equals(mode)) {
            reply("I'm listening. Ask me anything, give me something to do, or tell me what you studied.", true);
        } else if ("review".equals(mode)) {
            order = Tutor.reviewOrder(this, mockId);
            if (order.isEmpty()) {
                reply("Nothing to review in this mock: every question was right. Well done.", false);
            } else {
                step(-1);
            }
        } else {
            send(""); // the tutor opens the session
        }
    }

    // ---------------------------------------------------------------- layout

    private View build(String title, String sub) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(getColor(R.color.text));
        back.setBackgroundResource(android.R.color.transparent);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 18, R.color.text);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(t);
        col.addView(text(sub, 12, R.color.sub));
        bar.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rateChip = chip("");
        rateChip.setOnClickListener(v -> cycleRate());
        bar.addView(rateChip);
        showRate();
        root.addView(bar);

        scroll = new ScrollView(this);
        msgs = new LinearLayout(this);
        msgs.setOrientation(LinearLayout.VERTICAL);
        msgs.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 16));
        scroll.addView(msgs);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = text("", 13, R.color.sub);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 2));
        root.addView(status);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        handsChip = chip("");
        handsChip.setOnClickListener(v -> {
            if ("review".equals(mode)) {
                Prefs.sp(this).edit().putBoolean("autoPlay", !autoPlay()).apply();
            } else {
                handsFree = !handsFree;
                Prefs.sp(this).edit().putBoolean("handsFree", handsFree).apply();
            }
            showHands();
        });
        showHands();
        controls.addView(handsChip);
        mic = new ImageButton(this);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setColorFilter(getColor(R.color.onAccent));
        mic.setBackgroundResource(R.drawable.bg_mic);
        mic.setContentDescription("Speak");
        mic.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        mic.setOnClickListener(v -> {
            listenAfterSpeech = false; // tap while it talks = interrupt it and speak now
            voice.stopSpeaking();
            if (listening) voice.stopListening();
            else startListening();
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76));
        mp.setMargins(Ui.dp(this, 22), 0, Ui.dp(this, 22), 0);
        controls.addView(mic, mp);
        TextView stop = chip("Stop");
        stop.setOnClickListener(v -> {
            listenAfterSpeech = false;
            voice.stopSpeaking();
            voice.stopListening();
            status.setText("Paused. Tap the mic to talk.");
        });
        controls.addView(stop);
        root.addView(controls);

        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setGravity(Gravity.BOTTOM);
        typeRow.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 10));
        input = new EditText(this);
        input.setHint("Or type; the answer is still spoken");
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.grey));
        input.setTextSize(14);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        input.setMaxLines(3);
        typeRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView send = new TextView(this);
        send.setText("Send");
        send.setGravity(Gravity.CENTER);
        send.setTextColor(getColor(R.color.onAccent));
        send.setBackgroundResource(R.drawable.bg_btn);
        send.setOnClickListener(v -> {
            String s = input.getText().toString().trim();
            if (s.isEmpty()) return;
            input.setText("");
            send(s);
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 44));
        sp.setMarginStart(Ui.dp(this, 8));
        typeRow.addView(send, sp);
        root.addView(typeRow);
        return root;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(getColor(color));
        return t;
    }

    private TextView chip(String s) {
        TextView t = text(s, 13, R.color.text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        t.setBackgroundResource(R.drawable.bg_btn_ghost);
        return t;
    }

    private boolean autoPlay() {
        return Prefs.sp(this).getBoolean("autoPlay", true);
    }

    private void showHands() {
        if ("review".equals(mode)) {
            handsChip.setText(autoPlay() ? "Auto-play on" : "Auto-play off");
            handsChip.setTextColor(getColor(autoPlay() ? R.color.accent : R.color.sub));
            return;
        }
        handsChip.setText(handsFree ? "Hands-free on" : "Hands-free off");
        handsChip.setTextColor(getColor(handsFree ? R.color.accent : R.color.sub));
    }

    private void showRate() {
        float r = Prefs.sp(this).getFloat("voiceRate", 1.0f);
        rateChip.setText(String.format(Locale.US, "%sx", r == (int) r ? String.valueOf((int) r) : String.valueOf(r)));
    }

    private void cycleRate() {
        float r = Prefs.sp(this).getFloat("voiceRate", 1.0f);
        int i = 0;
        for (int k = 0; k < RATES.length; k++) if (Math.abs(RATES[k] - r) < 0.01f) i = k;
        voice.setRate(RATES[(i + 1) % RATES.length]);
        showRate();
    }

    private TextView bubble(String s, boolean mine) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 12);
        t.setPadding(Ui.dp(this, 14), p, Ui.dp(this, 14), p);
        t.setTextColor(getColor(mine ? R.color.onAccent : R.color.text));
        t.setBackgroundResource(mine ? R.drawable.bg_bubble_me : R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = Ui.dp(this, 10);
        if (mine) lp.leftMargin = Ui.dp(this, 48);
        else lp.rightMargin = Ui.dp(this, 36);
        msgs.addView(t, lp);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        return t;
    }

    // ---------------------------------------------------------------- conversation

    private void startListening() {
        if (closing || busy || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        voice.listen();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) voice.listen();
        else status.setText("Microphone permission is off. You can still type; answers are spoken.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        voice.handleResult(requestCode, resultCode, data);
    }

    private static boolean wantsToStop(String s) {
        String t = s.toLowerCase(Locale.US).replaceAll("[^a-z ]", "").trim();
        return t.equals("stop") || t.equals("bye") || t.equals("goodbye") || t.equals("thats all")
                || t.equals("that is all") || t.equals("end session") || t.equals("pause");
    }

    private void send(final String said) {
        if (busy) return;
        if ("review".equals(mode) && !said.isEmpty()) {
            review(said);
            return;
        }
        if (!said.isEmpty()) {
            if (partialBubble != null) {
                partialBubble.setText(said);
                partialBubble = null;
            } else {
                bubble(said, true);
            }
            if (wantsToStop(said)) {
                handsFree = false;
                showHands();
                reply("Okay, stopping here. Tap the mic whenever you want to continue.", false);
                return;
            }
        }
        if (!Llm.any(this)) {
            reply("Add an API key in Settings first, then I can answer.", false);
            return;
        }
        busy = true;
        status.setText("Thinking…");
        final List<String[]> past = new ArrayList<>(turns);
        new Thread(() -> {
            String out;
            try {
                String prompt;
                switch (mode) {
                    case "topic": prompt = Tutor.topic(getApplicationContext(), key, past, said); break;
                    case "question": prompt = Tutor.question(getApplicationContext(), qid, past, said); break;
                    case "coach": prompt = Tutor.coach(getApplicationContext(), past, said); break;
                    default:
                        prompt = Memory.prompt(getApplicationContext(), said, past) + "\n" + Tutor.SPOKEN + "\n";
                }
                String raw = Llm.chat(getApplicationContext(), prompt);
                if ("topic".equals(mode)) markRevised(raw);
                out = Memory.applyTasks(getApplicationContext(), raw);
            } catch (Exception e) {
                out = "That didn't work: " + (e.getMessage() == null ? "no connection?" : e.getMessage());
            }
            final String shown = Ui.plain(out).trim();
            main.post(() -> {
                busy = false;
                turns.add(new String[]{said, shown});
                reply(shown, true);
            });
        }, "saathi-voice").start();
    }

    // ---------------------------------------------------------------- review mode

    private static String norm(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** What the student said during a review: a navigation word, or a question about this one. */
    private void review(String said) {
        if (partialBubble != null) {
            partialBubble.setText(said);
            partialBubble = null;
        } else {
            bubble(said, true);
        }
        String t = norm(said);
        int asked = questionNumber(said);
        if (asked > 0) {
            explainNumber(asked);
        } else if (t.matches("(ok |okay |yes |go )?(next|next one|next question|continue|go on|skip|move on|done)( please)?")) {
            step(idx + 1);
        } else if (t.matches("(go )?(back|previous|previous one|last one|go back)( please)?")) {
            step(Math.max(0, idx - 1));
        } else if (t.matches("(repeat|say (it|that) again|again|once more)( please)?")) {
            reply(lastReply, true);
        } else if (wantsToStop(said)) {
            Prefs.sp(this).edit().putBoolean("autoPlay", false).apply();
            showHands();
            reply("Stopping here. Say next or tap the mic when you want to carry on.", false);
        } else {
            boolean onQuestion = detour >= 0 || (idx >= 0 && idx < order.size());
            if (!onQuestion || !Llm.any(this)) {
                step(Math.max(0, idx));
                return;
            }
            busy = true;
            status.setText("Thinking…");
            final long q = detour >= 0 ? detour : order.get(idx);
            final List<String[]> past = new ArrayList<>(qTurns);
            new Thread(() -> {
                String out;
                try {
                    out = Memory.applyTasks(getApplicationContext(),
                            Llm.chat(getApplicationContext(), Tutor.reviewFollowup(getApplicationContext(), q, past, said)));
                } catch (Exception e) {
                    out = "That didn't work: " + e.getMessage();
                }
                final String shown = Ui.plain(out).trim();
                main.post(() -> {
                    busy = false;
                    qTurns.add(new String[]{said, shown});
                    turns.add(new String[]{said, shown});
                    lastReply = shown;
                    reply(shown, false); // wait for "next" after answering a question
                });
            }, "saathi-review").start();
        }
    }

    /** "question 12", "explain Q 12", "go to number twelve" → 12; 0 when no number was asked for. */
    static int questionNumber(String said) {
        String t = said.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(?:question|questions|q|number|no|explain|go to|open|show|about)\\s*(?:number\\s*)?(\\d{1,3})\\b")
                .matcher(t);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = java.util.regex.Pattern.compile("\\b(?:question|q|number)\\s+([a-z]+(?:\\s[a-z]+)?)").matcher(t);
        if (!m.find()) return 0;
        String[] w = m.group(1).split(" ");
        int n = 0;
        for (int i = 0; i < WORDS.length; i++) if (WORDS[i].equals(w[0])) n = i;
        for (int i = 2; i < TENS.length; i++) {
            if (TENS[i].equals(w[0])) {
                n = i * 10;
                if (w.length > 1) for (int k = 1; k < 10; k++) if (WORDS[k].equals(w[1])) n += k;
            }
        }
        return n;
    }

    /** Explain any question of this mock by its number, right ones included; "next" then resumes the review. */
    private void explainNumber(final int n) {
        com.arpit.saathi.gate.Gate.Q found = null;
        for (com.arpit.saathi.gate.Gate.Q q : GateDb.get(this).qs(mockId)) if (q.n == n) found = q;
        if (found == null) {
            reply("There's no question " + n + " in this mock. Say another number, or next to carry on.", true);
            return;
        }
        if (!Llm.any(this)) {
            reply("Add an API key in Settings first.", false);
            return;
        }
        final long id = found.id;
        detour = id;
        qTurns.clear();
        busy = true;
        status.setText("Question " + n + "…");
        new Thread(() -> {
            String out;
            try {
                out = Llm.chat(getApplicationContext(), Tutor.reviewQuestion(getApplicationContext(), id, 0, 0));
            } catch (Exception e) {
                out = "That didn't work: " + e.getMessage();
            }
            final String shown = Ui.plain(out).trim();
            main.post(() -> {
                busy = false;
                lastReply = shown;
                turns.add(new String[]{"Explain question " + n, shown});
                reply(shown + (idx >= 0 && idx < order.size() ? " Say next to go back to the review." : ""), true);
            });
        }, "saathi-review").start();
    }

    private String make(int i) throws Exception {
        String prompt;
        if (i < 0) prompt = Tutor.reviewIntro(getApplicationContext(), mockId, order.size());
        else if (i >= order.size()) prompt = Tutor.reviewOutro(getApplicationContext(), mockId, covered, order.size());
        else prompt = Tutor.reviewQuestion(getApplicationContext(), order.get(i), i + 1, order.size());
        return Llm.chat(getApplicationContext(), prompt);
    }

    /** Explanations are made one step ahead, so the next question starts as soon as this one ends. */
    private void prefetch(final int i) {
        if (i > order.size() || ready.containsKey(i) || making.contains(i) || !Llm.any(this)) return;
        making.add(i);
        new Thread(() -> {
            try {
                ready.put(i, make(i));
            } catch (Exception ignored) {
            } finally {
                making.remove(i);
            }
        }, "saathi-prefetch").start();
    }

    private void step(final int i) {
        if (busy) return;
        if (i > order.size()) {
            reply("That's the whole review. Tap back when you're done.", false);
            return;
        }
        idx = i;
        detour = -1;
        qTurns.clear();
        if (i >= 0 && i < order.size()) covered = Math.max(covered, i + 1);
        if (!Llm.any(this)) {
            reply("Add an API key in Settings first.", false);
            return;
        }
        busy = true;
        status.setText(i < 0 ? "Looking at your mock…" : i >= order.size() ? "Putting it together…"
                : "Question " + (i + 1) + " of " + order.size() + "…");
        new Thread(() -> {
            String out;
            try {
                String pre = ready.remove(i);
                out = pre != null ? pre : make(i);
                if (i >= order.size()) out = Memory.applyTasks(getApplicationContext(), out);
            } catch (Exception e) {
                out = "That didn't work: " + e.getMessage();
            }
            final String shown = Ui.plain(out).trim();
            main.post(() -> {
                busy = false;
                lastReply = shown;
                turns.add(new String[]{"", shown});
                bubble(i < 0 ? "Review" : i >= order.size() ? "Summary" : "Question " + (i + 1) + " of " + order.size(),
                        true);
                reply(shown, true);
                prefetch(i + 1);
            });
        }, "saathi-review").start();
    }

    /** "REVISED: strong" from the tutor marks the topic as revised with that confidence. */
    private void markRevised(String raw) {
        for (String line : raw.split("\n")) {
            String t = line.trim().toUpperCase(Locale.US);
            if (!t.startsWith("REVISED:")) continue;
            int conf = t.contains("STRONG") ? 3 : t.contains("OKAY") || t.contains("OK") ? 2 : t.contains("SHAKY") ? 1 : 0;
            if (conf == 0 || key == null) return;
            GateDb.get(this).setTopic(key, "revised", System.currentTimeMillis());
            GateDb.get(this).setTopic(key, "confidence", conf);
        }
    }

    private void reply(String text, boolean listenAfter) {
        bubble(text, false);
        status.setText("Speaking… tap the mic to interrupt");
        listenAfterSpeech = listenAfter;
        voice.speak(text);
    }

    // ---------------------------------------------------------------- Voice.Listener

    @Override
    public void onListening(boolean on) {
        listening = on;
        mic.setBackgroundResource(on ? R.drawable.bg_mic_live : R.drawable.bg_mic);
        if (on) status.setText("Listening…");
    }

    @Override
    public void onPartial(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (partialBubble == null) partialBubble = bubble(text, true);
        else partialBubble.setText(text);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    public void onHeard(String text) {
        misses = 0;
        send(text);
    }

    @Override
    public void onSpeaking(boolean on) {
        if (on) return;
        if ("review".equals(mode) && listenAfterSpeech && !busy && !closing && idx < order.size()) {
            listenAfterSpeech = false;
            if (autoPlay() && detour < 0) {
                status.setText("Next in a moment… tap the mic to ask something");
                final int at = idx;
                main.postDelayed(() -> {
                    if (idx == at && !listening && !busy && !closing && autoPlay()) step(idx + 1);
                }, 1800);
            } else {
                status.setText("Say next, back, repeat, \"question 12\", or ask about this one");
                main.postDelayed(this::startListening, 350);
            }
            return;
        }
        boolean listen = handsFree && listenAfterSpeech && !busy && !closing;
        listenAfterSpeech = false;
        status.setText(handsFree ? "" : "Tap the mic to answer");
        if (listen) main.postDelayed(this::startListening, 350);
    }

    @Override
    public void onProblem(String message) {
        if (partialBubble != null) {
            msgs.removeView(partialBubble);
            partialBubble = null;
        }
        status.setText(message);
        if (message.startsWith("I didn't catch") && handsFree && !closing) {
            misses++;
            if (misses < 2) main.postDelayed(this::startListening, 600);
            else status.setText("Paused. Tap the mic when you're ready.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) closing = true;
        voice.stopListening();
    }

    @Override
    protected void onDestroy() {
        closing = true;
        main.removeCallbacksAndMessages(null);
        voice.destroy();
        String where = "review".equals(mode) ? "voice mock review" : "topic".equals(mode) ? "voice revision of "
                + GateDb.get(this).topic(key).name : "question".equals(mode) ? "voice talk-through of a mock question"
                : "coach".equals(mode) ? "GATE coach chat" : "voice chat";
        Recall.summarise(getApplicationContext(), where, new ArrayList<>(turns));
        // a real tutoring session goes into the study log
        int spoken = 0;
        for (String[] t : turns) if (!t[0].isEmpty()) spoken++;
        if ("review".equals(mode) && covered > 0) {
            com.arpit.saathi.gate.Gate.Mock m = GateDb.get(this).mock(mockId);
            GateDb.get(this).log("revise", "Voice review of " + (m == null ? "a mock" : m.title) + ": went through "
                    + covered + " of " + order.size() + " missed questions",
                    (int) Math.max(1, (System.currentTimeMillis() - started) / 60000));
        } else if (spoken >= 2 && ("topic".equals(mode) || "question".equals(mode))) {
            int minutes = (int) Math.max(1, (System.currentTimeMillis() - started) / 60000);
            String what;
            if ("topic".equals(mode)) {
                what = "Voice revision: " + GateDb.get(this).topic(key).name;
            } else {
                Gate.Q q = GateDb.get(this).q(qid);
                what = "Talked through question " + (q == null ? "" : q.n) + (q == null || q.topic.isEmpty() ? ""
                        : " (" + q.topic + ")");
            }
            GateDb.get(this).log("revise", what, minutes);
        }
        super.onDestroy();
    }
}
