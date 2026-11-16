package com.arpit.saathi.gate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.arpit.saathi.Prefs;
import com.arpit.saathi.ProcessJob;
import com.arpit.saathi.R;
import com.arpit.saathi.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One mock: the score, every question as a coloured square, and how each topic went. */
public class MockActivity extends Activity {
    private static final String[] FILTERS = {"All", "Wrong", "Skipped", "Partial", "Correct"};
    private static final String[] RESULTS = {"", "wrong", "unattempted", "partial", "correct"};

    private final Runnable onChange = this::render;
    private long id;
    private int filter;
    private LinearLayout content;
    private TextView titleView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        id = getIntent().getLongExtra("id", -1);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = Views.topBar(this, "Mock", "");
        titleView = bar.findViewWithTag("title");
        ImageButton del = Views.icon(this, R.drawable.ic_delete, "Delete");
        del.setColorFilter(getColor(R.color.sub));
        del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Delete this mock and its questions?")
                .setPositiveButton("Delete", (d, w) -> {
                    GateDb.removeListener(onChange);
                    java.io.File[] files = GateWork.dir(this, id).listFiles();
                    if (files != null) for (java.io.File f : files) f.delete();
                    GateWork.dir(this, id).delete();
                    GateDb.get(this).deleteMock(id);
                    finish();
                }).setNegativeButton("Cancel", null).show());
        bar.addView(del);
        root.addView(bar);
        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        content.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 40));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        GateDb.addListener(onChange);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        GateDb.removeListener(onChange);
    }

    private boolean retry() {
        return Prefs.sp(this).getBoolean("gate_retry", false);
    }

    private void render() {
        Gate.Mock m = GateDb.get(this).mock(id);
        if (m == null) {
            finish();
            return;
        }
        titleView.setText(m.title);
        content.removeAllViews();

        if (!m.ready()) {
            LinearLayout card = Views.card(this);
            if ("failed".equals(m.status)) {
                card.addView(Views.text(this, "Couldn't read this PDF.\n" + m.error, 15, getColor(R.color.red)));
                TextView again = Views.button(this, "Try again", true);
                LinearLayout.LayoutParams lp = Views.wide();
                lp.topMargin = Ui.dp(this, 12);
                again.setOnClickListener(v -> {
                    ContentValues cv = new ContentValues();
                    cv.put("status", "queued");
                    cv.put("error", "");
                    GateDb.get(this).setMock(id, cv);
                    ProcessJob.kick(this, ProcessJob.JOB_GATE);
                });
                card.addView(again, lp);
            } else {
                card.addView(Views.text(this, (m.progress.isEmpty() ? "Waiting to be read" : m.progress) + "…\n"
                        + "Questions appear here as they are read.", 15, getColor(R.color.amber)));
            }
            content.addView(card);
        } else {
            LinearLayout card = Views.card(this);
            card.addView(Views.title(this, GateWork.fmt(m.score) + " / " + GateWork.fmt(m.max) + "   ·   "
                    + Math.round(m.pct()) + "%", 26));
            card.addView(Views.text(this, m.correct + " right  ·  " + m.wrong + " wrong  ·  " + m.partial
                    + " partial  ·  " + (m.questions - m.attempted) + " skipped", 14, getColor(R.color.text)));
            card.addView(Views.text(this, new SimpleDateFormat("d MMM yyyy", Locale.US).format(new Date(m.taken))
                    + "  ·  " + m.questions + " questions", 12, getColor(R.color.grey)));
            content.addView(card);
        }

        List<Gate.Q> qs = GateDb.get(this).qs(id);
        if (qs.isEmpty()) return;

        CheckBox retry = new CheckBox(this);
        retry.setText("Retry mode: hide answers until I try");
        retry.setTextColor(getColor(R.color.text));
        retry.setChecked(retry());
        retry.setOnCheckedChangeListener((v, on) -> Prefs.sp(this).edit().putBoolean("gate_retry", on).apply());
        content.addView(retry);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        for (int i = 0; i < FILTERS.length; i++) {
            final int idx = i;
            int count = 0;
            for (Gate.Q q : qs) if (q.max > 0 && (i == 0 || RESULTS[i].equals(q.result))) count++;
            TextView c = Views.chip(this, FILTERS[i] + " " + count, i == filter);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 8));
            c.setOnClickListener(v -> {
                filter = idx;
                render();
            });
            chips.addView(c, lp);
        }
        hs.addView(chips);
        content.addView(hs);

        final List<Gate.Q> shown = new ArrayList<>();
        for (Gate.Q q : qs) if (filter == 0 || RESULTS[filter].equals(q.result)) shown.add(q);
        final long[] ids = new long[shown.size()];
        for (int i = 0; i < shown.size(); i++) ids[i] = shown.get(i).id;

        // the grid: one square per question, coloured by how it went
        int perRow = 7, size = Ui.dp(this, 42), gap = Ui.dp(this, 6);
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams lp = Views.wide();
                lp.topMargin = gap;
                content.addView(row, lp);
            }
            Gate.Q q = shown.get(i);
            int col = Views.resultColor(this, q.max <= 0 ? "" : q.result);
            TextView sq = Views.text(this, String.valueOf(q.n), 14, col);
            sq.setGravity(Gravity.CENTER);
            sq.setBackground(Ui.round(Views.faint(col, 40), Ui.dp(this, 10)));
            if (!q.solution.isEmpty()) sq.setTypeface(null, android.graphics.Typeface.BOLD);
            final int pos = i;
            sq.setOnClickListener(v -> startActivity(new Intent(this, QuestionActivity.class)
                    .putExtra("ids", ids).putExtra("pos", pos).putExtra("retry", retry())));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            row.addView(sq, lp);
        }
        TextView legend = Views.text(this, "Green right · red wrong · amber partial · grey skipped. Bold ones "
                + "already have a solution.", 12, getColor(R.color.grey));
        legend.setPadding(0, Ui.dp(this, 10), 0, 0);
        content.addView(legend);

        // topics in this mock, worst first
        Map<String, int[]> byTopic = new LinkedHashMap<>();
        for (Gate.Q q : qs) {
            if (q.max <= 0 || q.topic.isEmpty()) continue;
            String k = Gate.key(q.subject, q.topic);
            int[] s = byTopic.get(k);
            if (s == null) byTopic.put(k, s = new int[2]);
            s[0]++;
            if ("correct".equals(q.result)) s[1]++;
        }
        if (byTopic.isEmpty()) return;
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(byTopic.entrySet());
        rows.sort((a, b) -> Double.compare((double) a.getValue()[1] / a.getValue()[0],
                (double) b.getValue()[1] / b.getValue()[0]));
        content.addView(Views.label(this, "By topic (tap to revise)"));
        LinearLayout card = Views.card(this);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        for (Map.Entry<String, int[]> e : rows) {
            final String key = e.getKey();
            int[] s = e.getValue();
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
            TextView name = Views.text(this, key.substring(key.indexOf(" / ") + 3), 14, getColor(R.color.text));
            r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView score = Views.text(this, s[1] + "/" + s[0], 14,
                    getColor(s[1] == s[0] ? R.color.green : s[1] == 0 ? R.color.red : R.color.amber));
            score.setPadding(Ui.dp(this, 12), 0, 0, 0);
            r.addView(score);
            r.setOnClickListener(v -> startActivity(new Intent(this, TopicActivity.class).putExtra("key", key)));
            card.addView(r);
        }
        content.addView(card);
    }
}
