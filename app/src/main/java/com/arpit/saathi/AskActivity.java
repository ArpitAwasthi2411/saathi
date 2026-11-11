package com.arpit.saathi;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ask questions about what you saved, or ask to be reminded of something. */
public class AskActivity extends Activity {
    private final List<String[]> history = new ArrayList<>();
    private LinearLayout msgs;
    private ScrollView scroll;
    private EditText input;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_ask);
        msgs = findViewById(R.id.msgs);
        scroll = findViewById(R.id.scroll);
        input = findViewById(R.id.input);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSend).setOnClickListener(v -> send());
        bubble("Ask me about anything you've saved: \"which deadlines are closest?\", \"what was that "
                + "planning paper about?\". I can also add tasks: \"remind me to apply on Friday at 6 pm\".", false);
    }

    private TextView bubble(String text, boolean mine) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 12);
        t.setPadding(Ui.dp(this, 14), p, Ui.dp(this, 14), p);
        t.setTextColor(getColor(mine ? R.color.onAccent : R.color.text));
        t.setBackgroundResource(mine ? R.drawable.bg_bubble_me : R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = Ui.dp(this, 10);
        if (mine) lp.leftMargin = Ui.dp(this, 48);
        else lp.rightMargin = Ui.dp(this, 48);
        msgs.addView(t, lp);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        return t;
    }

    private void send() {
        final String q = input.getText().toString().trim();
        if (q.isEmpty() || busy) return;
        if (!Llm.any(this)) {
            bubble("Add an API key in Settings first.", false);
            return;
        }
        busy = true;
        input.setText("");
        bubble(q, true);
        final TextView answer = bubble("Thinking…", false);
        final List<String[]> past = new ArrayList<>(history);
        new Thread(() -> {
            String out;
            try {
                out = handle(Llm.chat(getApplicationContext(), Memory.prompt(getApplicationContext(), q, past)));
            } catch (Exception e) {
                out = "That didn't work: " + (e.getMessage() == null ? "no connection?" : e.getMessage());
            }
            final String shown = out;
            runOnUiThread(() -> {
                busy = false;
                history.add(new String[]{q, shown});
                answer.setText(shown);
                scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
            });
        }, "saathi-ask").start();
    }

    /** Pulls "TASK: title | time" lines out of the answer and turns them into real tasks. */
    private String handle(String reply) {
        StringBuilder text = new StringBuilder(), added = new StringBuilder();
        for (String line : reply.replace("**", "").split("\n")) {
            String t = line.trim();
            if (!t.toUpperCase(Locale.US).startsWith("TASK:")) {
                text.append(line).append("\n");
                continue;
            }
            String body = t.substring(5).trim();
            int bar = body.lastIndexOf('|');
            String title = (bar >= 0 ? body.substring(0, bar) : body).trim();
            if (title.isEmpty()) continue;
            if (title.length() > 200) title = title.substring(0, 200);
            long due = 0;
            if (bar >= 0) {
                try {
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                    f.setLenient(false);
                    due = f.parse(body.substring(bar + 1).trim()).getTime();
                } catch (Exception ignored) {
                }
            }
            long id = Db.get(this).addTask(title, due, 0);
            Reminders.scheduleTask(this, Db.get(this).task(id));
            added.append("\n✓ Task added: ").append(title);
            if (due > 0) {
                added.append(" (").append(Ui.when(due)).append(due < System.currentTimeMillis()
                        ? ", already past, so no reminder" : "").append(")");
            } else {
                added.append(" (no reminder time)");
            }
        }
        return (text.toString().trim() + (added.length() > 0 ? "\n" + added : "")).trim();
    }
}
