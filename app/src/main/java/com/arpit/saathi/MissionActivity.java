package com.arpit.saathi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One mission: the master agent's report on top, each agent's own work underneath. */
public class MissionActivity extends Activity {
    private static final int REQ_PDF = 22;

    private long id;
    private Team.Mission m;
    private final Runnable onChange = this::render;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_mission);
        id = getIntent().getLongExtra("id", -1);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPdf).setOnClickListener(v -> {
            if (m != null) Pdf.askWhere(this, m.title.isEmpty() ? "mission" : m.title, REQ_PDF);
        });
        findViewById(R.id.btnShare).setOnClickListener(v -> share());
        findViewById(R.id.btnRetry).setOnClickListener(v -> {
            ContentValues cv = new ContentValues();
            cv.put("status", "queued");
            cv.put("error", "");
            Db.get(this).setMission(id, cv);
            ProcessJob.kick(this, ProcessJob.JOB_TEAM);
        });
        findViewById(R.id.btnDelete).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Delete this mission and its report?")
                .setPositiveButton("Delete", (d, w) -> {
                    Db.removeListener(onChange);
                    Db.get(this).deleteMission(id);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        id = intent.getLongExtra("id", -1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Db.addListener(onChange);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Db.removeListener(onChange);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PDF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final long mid = id;
            Pdf.save(this, data.getData(), () -> Pdf.ofMission(getApplicationContext(), mid));
        }
    }

    private void render() {
        m = Db.get(this).mission(id);
        if (m == null) {
            finish();
            return;
        }
        List<Team.Step> steps = Db.get(this).steps(id);
        int searches = 0;
        for (Team.Step s : steps) searches += s.searches;

        TeamActivity.statusPill((TextView) findViewById(R.id.pill), m.status);
        ((TextView) findViewById(R.id.title)).setText(m.title.isEmpty() ? m.goal : m.title);
        TextView goal = findViewById(R.id.goal);
        goal.setVisibility("report".equals(m.kind) || m.title.isEmpty() ? View.GONE : View.VISIBLE);
        goal.setText(m.goal);
        ((TextView) findViewById(R.id.meta)).setText(
                new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(new Date(m.created))
                        + ("report".equals(m.kind) ? "" : "  ·  " + steps.size() + " agent tasks  ·  " + searches
                        + " web searches"));
        findViewById(R.id.btnRetry).setVisibility("failed".equals(m.status) ? View.VISIBLE : View.GONE);

        int accent = getColor(R.color.accent);
        TextView report = findViewById(R.id.report);
        if (!m.report.isEmpty()) report.setText(format(m.report, accent));
        else if ("failed".equals(m.status)) report.setText("This mission didn't finish: " + m.error);
        else if (steps.isEmpty()) report.setText("Chief is planning who does what…");
        else report.setText("The team is working. The report appears here when everyone is done.");

        findViewById(R.id.stepsLabel).setVisibility(steps.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout box = findViewById(R.id.steps);
        box.removeAllViews();
        for (Team.Step s : steps) box.addView(stepCard(s));
    }

    private View stepCard(Team.Step s) {
        int color = Team.agentColor(this, s.agent);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(this, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);

        String state;
        switch (s.status) {
            case "done": state = s.searches > 0 ? s.searches + " web searches" : "done"; break;
            case "run": state = "working…"; break;
            case "fail": state = "failed"; break;
            default: state = "waiting";
        }
        TextView head = new TextView(this);
        head.setText(Team.agentName(s.agent).toUpperCase(Locale.US) + "  ·  " + state);
        head.setTextSize(11);
        head.setLetterSpacing(0.08f);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setTextColor(color);
        card.addView(head);

        TextView task = new TextView(this);
        task.setText(s.task);
        task.setTextSize(14);
        task.setTextColor(getColor(R.color.sub));
        task.setLineSpacing(Ui.dp(this, 2), 1f);
        task.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(task);

        if (!s.output.isEmpty()) {
            TextView out = new TextView(this);
            out.setText(format(s.output, color));
            out.setTextSize(14);
            out.setTextColor(getColor(R.color.text));
            out.setLineSpacing(Ui.dp(this, 3), 1f);
            out.setTextIsSelectable(true);
            out.setPadding(0, Ui.dp(this, 10), 0, 0);
            card.addView(out);
        }
        return card;
    }

    /** "## " lines become coloured headings, "- " lines become bullets. */
    static CharSequence format(String s, int accent) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : s.replace("**", "").split("\n")) {
            String t = line.trim();
            if (t.startsWith("#")) {
                if (sb.length() > 0) sb.append("\n");
                int start = sb.length();
                sb.append(t.replaceFirst("^#+\\s*", "")).append("\n");
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), 0);
                sb.setSpan(new ForegroundColorSpan(accent), start, sb.length(), 0);
            } else if (t.startsWith("- ") || t.startsWith("* ")) {
                sb.append("•  ").append(t.substring(2)).append("\n");
            } else if (!t.isEmpty()) {
                sb.append(t).append("\n");
            }
        }
        return sb;
    }

    private void share() {
        if (m == null) return;
        StringBuilder sb = new StringBuilder(m.title.isEmpty() ? m.goal : m.title).append("\n\n");
        if (!m.report.isEmpty()) sb.append(m.report).append("\n");
        for (Team.Step s : Db.get(this).steps(id)) {
            sb.append("\n— ").append(Team.agentName(s.agent)).append(": ").append(s.task).append("\n")
                    .append(s.output).append("\n");
        }
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, m.title)
                .putExtra(Intent.EXTRA_TEXT, sb.toString()), "Share"));
    }
}
