package com.arpit.saathi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
        List<Team.Finding> found = Db.get(this).findings(id);
        int searches = 0;
        for (Team.Step s : steps) searches += s.searches;

        TeamActivity.statusPill((TextView) findViewById(R.id.pill), m.status);
        ((TextView) findViewById(R.id.title)).setText(m.title.isEmpty() ? m.goal : m.title);
        TextView goal = findViewById(R.id.goal);
        goal.setVisibility("report".equals(m.kind) || m.title.isEmpty() ? View.GONE : View.VISIBLE);
        goal.setText("You asked: " + m.goal);
        ((TextView) findViewById(R.id.meta)).setText(
                new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(new Date(m.created))
                        + ("report".equals(m.kind) ? "" : "  ·  " + searches + " web searches  ·  "
                        + found.size() + " sourced results"));
        findViewById(R.id.btnRetry).setVisibility("failed".equals(m.status) ? View.VISIBLE : View.GONE);

        int accent = getColor(R.color.accent);
        TextView report = findViewById(R.id.report);
        if (!m.report.isEmpty()) report.setText(format(m.report, accent));
        else if ("failed".equals(m.status)) report.setText("This mission didn't finish.\n" + m.error);
        else if (steps.isEmpty()) report.setText("Chief is planning who does what…");
        else report.setText("The team is working. The summary appears here when everyone is done.");

        findViewById(R.id.resultsLabel).setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout results = findViewById(R.id.results);
        results.removeAllViews();
        int n = 0;
        for (Team.Finding f : found) results.addView(findingCard(f, ++n));

        findViewById(R.id.stepsLabel).setVisibility(steps.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout box = findViewById(R.id.steps);
        box.removeAllViews();
        for (Team.Step s : steps) box.addView(stepCard(s));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(this, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView line(String text, float size, int color, int topDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(Ui.dp(this, 2), 1f);
        t.setPadding(0, Ui.dp(this, topDp), 0, 0);
        return t;
    }

    /** One sourced result: what it is, the facts from its page, how well it fits, and the page itself. */
    private View findingCard(final Team.Finding f, int position) {
        LinearLayout card = card();

        StringBuilder top = new StringBuilder("#" + position);
        if (f.fit >= 0) top.append("  ·  FIT ").append(f.fit).append("/100");
        String due = Ui.dueLabel(f.deadline);
        if (!due.isEmpty()) top.append("  ·  ").append(due.toUpperCase(Locale.US));
        else if (!f.status.isEmpty()) top.append("  ·  ").append(f.status.toUpperCase(Locale.US));
        TextView head = line(top.toString(), 11, f.deadline.isEmpty() ? getColor(R.color.accent)
                : Ui.dueColor(this, f.deadline), 0);
        head.setLetterSpacing(0.06f);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(head);

        TextView title = line(f.title, 16, getColor(R.color.text), 6);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextIsSelectable(true);
        card.addView(title);
        if (!f.what.isEmpty()) card.addView(line(f.what, 14, getColor(R.color.sub), 4));

        SpannableStringBuilder facts = new SpannableStringBuilder();
        java.util.List<String> rows = new java.util.ArrayList<>(java.util.Arrays.asList(f.facts.split("\n")));
        if (!f.deadline.isEmpty()) rows.add("Deadline|" + Ui.prettyDate(f.deadline));
        for (String row : rows) {
            int bar = row.indexOf('|');
            if (bar <= 0) continue;
            if (facts.length() > 0) facts.append("\n");
            int start = facts.length();
            facts.append(row.substring(0, bar)).append("  ");
            facts.setSpan(new ForegroundColorSpan(getColor(R.color.grey)), start, facts.length(), 0);
            facts.append(row.substring(bar + 1));
        }
        if (facts.length() > 0) {
            TextView t = line("", 13, getColor(R.color.text), 8);
            t.setText(facts);
            card.addView(t);
        }
        if (!f.why.isEmpty()) card.addView(line("Why: " + f.why, 13, getColor(R.color.sub), 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView open = line(f.site.isEmpty() ? "Open source page" : "Open " + f.site, 14, accentColor(), 0);
        open.setMaxLines(1);
        open.setEllipsize(android.text.TextUtils.TruncateAt.END);
        open.setOnClickListener(v -> openUrl(f.url));
        row.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView track = line("Track in inbox", 14, accentColor(), 0);
        track.setOnClickListener(v -> {
            final String text = f.title + "\n" + f.what + "\n" + f.facts.replace("|", ": ")
                    + (f.deadline.isEmpty() ? "" : "\nDeadline: " + f.deadline);
            new Thread(() -> Capture.save(getApplicationContext(), text, f.url, "manual", null, false)).start();
            Toast.makeText(this, "Added to your inbox. Deadline reminders are on.", Toast.LENGTH_SHORT).show();
        });
        row.addView(track);
        card.addView(row);
        return card;
    }

    private int accentColor() {
        return getColor(R.color.accent);
    }

    private void openUrl(String url) {
        try {
            Uri u = Uri.parse(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
            if (scheme.equals("https") || scheme.equals("http")) startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    /** Collapsed by default: who did what, with counts. Tap to read that agent's own output. */
    private View stepCard(final Team.Step s) {
        int color = Team.agentColor(this, s.agent);
        LinearLayout card = card();
        Team.Agent a = Team.agent(s.agent);
        String state;
        switch (s.status) {
            case "done":
                state = a != null && a.search ? s.searches + " searches, " + s.found + " results" : "done";
                break;
            case "run": state = "working…"; break;
            case "fail": state = "failed"; break;
            default: state = "waiting";
        }
        TextView head = line(Team.agentName(s.agent).toUpperCase(Locale.US) + "  ·  " + state, 11, color, 0);
        head.setLetterSpacing(0.08f);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(head);
        TextView task = line(s.task, 13, getColor(R.color.sub), 5);
        task.setMaxLines(2);
        task.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(task);

        final TextView out = line("", 13, getColor(R.color.text), 10);
        out.setText(format(s.output, color));
        out.setTextIsSelectable(true);
        boolean open = "fail".equals(s.status);
        out.setVisibility(open && !s.output.isEmpty() ? View.VISIBLE : View.GONE);
        card.addView(out);
        if (!s.output.isEmpty()) {
            card.setOnClickListener(v -> {
                boolean show = out.getVisibility() != View.VISIBLE;
                out.setVisibility(show ? View.VISIBLE : View.GONE);
                task.setMaxLines(show ? 20 : 2);
            });
        }
        return card;
    }

    /** "## " lines become coloured headings, "- " lines become bullets, "✓" lines stay as they are. */
    static CharSequence format(String s, int accent) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : Ui.plain(s).split("\n")) {
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
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.delete(sb.length() - 1, sb.length());
        return sb;
    }

    private void share() {
        if (m == null) return;
        StringBuilder sb = new StringBuilder(m.title.isEmpty() ? m.goal : m.title).append("\n\n");
        if (!m.report.isEmpty()) sb.append(m.report).append("\n");
        int n = 0;
        for (Team.Finding f : Db.get(this).findings(id)) {
            sb.append("\n").append(++n).append(". ").append(f.title).append("\n").append(f.what);
            if (!f.facts.isEmpty()) sb.append("\n").append(f.facts.replace("|", ": "));
            if (!f.deadline.isEmpty()) sb.append("\nDeadline: ").append(Ui.prettyDate(f.deadline));
            if (f.fit >= 0) sb.append("\nFit ").append(f.fit).append("/100. ").append(f.why);
            sb.append("\n").append(f.url).append("\n");
        }
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, m.title)
                .putExtra(Intent.EXTRA_TEXT, sb.toString()), "Share"));
    }
}
