package com.arpit.saathi.gate;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.arpit.saathi.Llm;
import com.arpit.saathi.R;
import com.arpit.saathi.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** One question with its full solution, rendered with proper maths. Swipe through with the arrows. */
public class QuestionActivity extends Activity {
    /** Questions being solved right now, so leaving and coming back doesn't start a second request. */
    private static final Set<Long> SOLVING = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Long> ASKING = Collections.synchronizedSet(new HashSet<>());

    private final Runnable onChange = this::show;
    private long[] ids;
    private int pos;
    private boolean retry;
    private Views.Study study;
    private TextView titleView, subView;
    private EditText ask;
    private long lastId = -1;
    private final java.util.Map<Long, String> errors = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ids = getIntent().getLongArrayExtra("ids");
        pos = getIntent().getIntExtra("pos", 0);
        retry = getIntent().getBooleanExtra("retry", false);
        if (ids == null || ids.length == 0) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        LinearLayout bar = Views.topBar(this, "Question", "");
        titleView = bar.findViewWithTag("title");
        subView = bar.findViewWithTag("sub");
        TextView talk = Views.text(this, "Talk", 14, getColor(R.color.accent));
        talk.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        talk.setOnClickListener(v -> startActivity(new android.content.Intent(this,
                com.arpit.saathi.VoiceActivity.class).putExtra("mode", "question").putExtra("qid", ids[pos])));
        bar.addView(talk);
        TextView page = Views.text(this, "Page", 14, getColor(R.color.accent));
        page.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        page.setOnClickListener(v -> showPage());
        bar.addView(page);
        TextView prev = Views.text(this, "‹", 28, getColor(R.color.text));
        prev.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        prev.setOnClickListener(v -> move(-1));
        bar.addView(prev);
        TextView next = Views.text(this, "›", 28, getColor(R.color.text));
        next.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        next.setOnClickListener(v -> move(1));
        bar.addView(next);
        root.addView(bar);

        study = new Views.Study(this, null);
        root.addView(study.web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout input = new LinearLayout(this);
        input.setGravity(Gravity.BOTTOM);
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 10));
        ask = new EditText(this);
        ask.setHint("Ask about this question");
        ask.setTextColor(getColor(R.color.text));
        ask.setHintTextColor(getColor(R.color.grey));
        ask.setBackgroundResource(R.drawable.bg_input);
        ask.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        ask.setMaxLines(4);
        input.addView(ask, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView send = Views.button(this, "Ask", true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 72), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(this, 8));
        send.setOnClickListener(v -> sendQuestion());
        input.addView(send, lp);
        root.addView(input);
        setContentView(root);
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

    private void move(int d) {
        int np = pos + d;
        if (np < 0 || np >= ids.length) {
            Toast.makeText(this, d < 0 ? "This is the first one" : "That was the last one", Toast.LENGTH_SHORT).show();
            return;
        }
        pos = np;
        show();
    }

    private void show() {
        final long qid = ids[pos];
        Gate.Q q = GateDb.get(this).q(qid);
        if (q == null) {
            finish();
            return;
        }
        titleView.setText("Question " + q.n);
        subView.setVisibility(android.view.View.VISIBLE);
        subView.setText((pos + 1) + " of " + ids.length + (q.topic.isEmpty() ? "" : "  ·  " + q.topic));
        if (q.solution.isEmpty() && !SOLVING.contains(qid) && !errors.containsKey(qid)) solve(qid);
        try {
            JSONObject d = new JSONObject();
            d.put("kind", "question");
            d.put("id", qid);
            d.put("n", q.n);
            d.put("marks", GateWork.fmt(q.marks));
            d.put("typeLabel", Gate.typeLabel(q.type));
            d.put("subject", q.subject);
            d.put("topic", q.topic);
            d.put("question", q.question);
            d.put("options", new JSONArray(q.options.isEmpty() ? "[]" : q.options));
            d.put("yours", q.yours);
            d.put("correct", q.correct);
            d.put("result", q.result);
            d.put("score", GateWork.fmt(q.score));
            d.put("max", GateWork.fmt(q.max));
            d.put("retry", retry);
            d.put("solution", q.solution);
            d.put("solving", SOLVING.contains(qid));
            d.put("error", errors.containsKey(qid) ? errors.get(qid) : "");
            d.put("chat", new JSONArray(q.chat.isEmpty() ? "[]" : q.chat));
            d.put("keepScroll", qid == lastId);
            lastId = qid;
            study.render(d);
        } catch (Exception ignored) {
        }
    }

    private void solve(final long qid) {
        if (!Llm.any(this)) {
            errors.put(qid, "Add a Gemini key in Settings to get solutions.");
            return;
        }
        SOLVING.add(qid);
        new Thread(() -> {
            try {
                GateWork.solve(getApplicationContext(), qid);
            } catch (Exception e) {
                final String m = e.getMessage() == null ? "Couldn't get a solution." : e.getMessage();
                runOnUiThread(() -> errors.put(qid, "Couldn't get a solution: " + m));
            } finally {
                SOLVING.remove(qid);
                runOnUiThread(this::show);
            }
        }, "gate-solve").start();
    }

    private void sendQuestion() {
        final String text = ask.getText().toString().trim();
        final long qid = ids[pos];
        if (text.isEmpty() || ASKING.contains(qid)) return;
        if (!Llm.any(this)) {
            Toast.makeText(this, "Add an API key in Settings first", Toast.LENGTH_SHORT).show();
            return;
        }
        ask.setText("");
        ASKING.add(qid);
        new Thread(() -> {
            try {
                GateWork.ask(getApplicationContext(), qid, text);
            } catch (Exception ignored) {
            } finally {
                ASKING.remove(qid);
            }
        }, "gate-ask").start();
    }

    /** The original screenshot page(s), for figures or to check what was read. */
    private void showPage() {
        Gate.Q q = GateDb.get(this).q(ids[pos]);
        if (q == null) return;
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFFFFFFFF);
        int found = 0;
        for (int pg = Math.max(1, q.page); pg <= Math.max(q.page, q.pageTo); pg++) {
            File f = GateWork.page(this, q.mockId, pg);
            if (!f.exists()) continue;
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
            col.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            found++;
        }
        if (found == 0) {
            Toast.makeText(this, "The original page isn't stored for this one", Toast.LENGTH_SHORT).show();
            return;
        }
        sv.addView(col);
        d.setContentView(sv);
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
