package com.arpit.saathi.gate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.arpit.saathi.Llm;
import com.arpit.saathi.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Revision notes for one syllabus topic, practice questions, and the mock questions you missed on it. */
public class TopicActivity extends Activity {
    private static final Set<String> WRITING = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> PRACTISING = Collections.synchronizedSet(new HashSet<>());

    private final Runnable onChange = this::show;
    private String key;
    private Views.Study study;
    private String error = "";
    private boolean first = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        key = getIntent().getStringExtra("key");
        if (key == null) {
            finish();
            return;
        }
        Gate.Topic t = GateDb.get(this).topic(key);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Views.topBar(this, t.name, t.subject));
        study = new Views.Study(this, url -> {
            if (url.startsWith("saathi://q/")) {
                try {
                    long qid = Long.parseLong(url.substring(11));
                    startActivity(new Intent(this, QuestionActivity.class).putExtra("ids", new long[]{qid}));
                } catch (NumberFormatException ignored) {
                }
            }
        });
        root.addView(study.web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView talk = Views.button(this, "Revise this by voice", true);
        talk.setOnClickListener(v -> startActivity(new Intent(this, com.arpit.saathi.VoiceActivity.class)
                .putExtra("mode", "topic").putExtra("key", key)));
        LinearLayout.LayoutParams tl = Views.wide();
        tl.setMargins(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), 0);
        root.addView(talk, tl);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView practice = Views.button(this, "Practice", false);
        practice.setOnClickListener(v -> makePractice());
        TextView rewrite = Views.button(this, "Rewrite", false);
        rewrite.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Write these notes again from scratch?")
                .setPositiveButton("Rewrite", (d, w) -> writeNotes()).setNegativeButton("Cancel", null).show());
        TextView done = Views.button(this, "Revised", false);
        done.setOnClickListener(v -> markRevised());
        row.addView(practice, weight(true));
        row.addView(rewrite, weight(true));
        row.addView(done, weight(false));
        root.addView(row);
        setContentView(root);
    }

    private LinearLayout.LayoutParams weight(boolean gapAfter) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (gapAfter) lp.setMarginEnd(Ui.dp(this, 8));
        return lp;
    }

    @Override
    protected void onResume() {
        super.onResume();
        GateDb.addListener(onChange);
        show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        GateDb.removeListener(onChange);
    }

    private void show() {
        Gate.Topic t = GateDb.get(this).topic(key);
        if (t.notes.isEmpty() && !WRITING.contains(key) && error.isEmpty()) writeNotes();
        try {
            JSONObject d = new JSONObject();
            d.put("kind", "topic");
            d.put("subject", t.subject);
            d.put("notes", t.notes);
            d.put("loading", WRITING.contains(key));
            d.put("error", error);
            d.put("practicing", PRACTISING.contains(key));
            d.put("practice", new JSONArray(t.practice.isEmpty() ? "[]" : t.practice));
            d.put("mine", mine(t));
            d.put("keepScroll", !first);
            first = false;
            study.render(d);
        } catch (Exception ignored) {
        }
    }

    /** "In your mocks" summary with links to each question (built here, so it is safe HTML). */
    private String mine(Gate.Topic t) {
        List<Gate.Q> qs = new ArrayList<>();
        for (Gate.Q q : GateDb.get(this).allQs()) {
            if (q.max > 0 && key.equals(Gate.key(q.subject, q.topic))) qs.add(q);
        }
        StringBuilder sb = new StringBuilder();
        if (t.revised > 0) {
            String[] conf = {"", " · felt shaky", " · felt okay", " · felt strong"};
            sb.append("Last revised ").append(new SimpleDateFormat("d MMM", Locale.US).format(new Date(t.revised)))
                    .append(conf[Math.max(0, Math.min(3, t.confidence))]).append("<br>");
        }
        if (qs.isEmpty()) return sb.append("No mock questions on this topic yet.").toString();
        int ok = 0;
        for (Gate.Q q : qs) if ("correct".equals(q.result)) ok++;
        sb.append("In your mocks: ").append(ok).append(" of ").append(qs.size()).append(" right. ");
        for (Gate.Q q : qs) {
            if ("correct".equals(q.result)) continue;
            Gate.Mock m = GateDb.get(this).mock(q.mockId);
            String when = m == null ? "" : new SimpleDateFormat("d MMM", Locale.US).format(new Date(m.taken));
            sb.append("<a href=\"saathi://q/").append(q.id).append("\">Q").append(q.n)
                    .append(when.isEmpty() ? "" : " (" + TextUtils.htmlEncode(when) + ")").append("</a> ");
        }
        return sb.toString();
    }

    private void writeNotes() {
        if (!Llm.any(this)) {
            error = "Add an API key in Settings to get revision notes.";
            show();
            return;
        }
        error = "";
        WRITING.add(key);
        show();
        new Thread(() -> {
            try {
                GateWork.notes(getApplicationContext(), key);
            } catch (Exception e) {
                final String m = e.getMessage();
                runOnUiThread(() -> error = "Couldn't write the notes: " + m);
            } finally {
                WRITING.remove(key);
                runOnUiThread(this::show);
            }
        }, "gate-notes").start();
    }

    private void makePractice() {
        if (PRACTISING.contains(key)) return;
        if (!Llm.any(this)) {
            Toast.makeText(this, "Add an API key in Settings first", Toast.LENGTH_SHORT).show();
            return;
        }
        PRACTISING.add(key);
        show();
        new Thread(() -> {
            try {
                GateWork.practice(getApplicationContext(), key);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't make practice questions", Toast.LENGTH_SHORT).show());
            } finally {
                PRACTISING.remove(key);
                runOnUiThread(this::show);
            }
        }, "gate-practice").start();
    }

    private void markRevised() {
        new AlertDialog.Builder(this).setTitle("How does this topic feel now?")
                .setItems(new CharSequence[]{"Shaky", "Okay", "Strong"}, (d, which) -> {
                    GateDb db = GateDb.get(this);
                    db.setTopic(key, "revised", System.currentTimeMillis());
                    db.setTopic(key, "confidence", which + 1);
                    Gate.Topic t = db.topic(key);
                    db.log("revise", "Revised " + t.name + " (" + new String[]{"shaky", "okay", "strong"}[which] + ")", 0);
                    Toast.makeText(this, "Marked as revised", Toast.LENGTH_SHORT).show();
                }).show();
    }
}
