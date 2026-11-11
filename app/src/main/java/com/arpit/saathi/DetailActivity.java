package com.arpit.saathi;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** One saved item: the facts, the research, and the actions you can take on it. */
public class DetailActivity extends Activity {
    private long id;
    private Item it;
    private final Runnable onChange = this::render;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        id = getIntent().getLongExtra("id", -1);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btnRetry).setOnClickListener(v -> retry());
        findViewById(R.id.btnCal).setOnClickListener(v -> addToCalendar());
        findViewById(R.id.btnTask).setOnClickListener(v -> addTask());
        findViewById(R.id.btnShare).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{"Share as text", "Save as PDF"}, (d, which) -> {
                    if (which == 0) share();
                    else if (it != null) Pdf.askWhere(this, it.title.isEmpty() ? "item" : it.title, 31);
                })
                .show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 31 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Item snap = Db.get(this).item(id);
            if (snap != null) Pdf.save(this, data.getData(), () -> Pdf.ofItem(snap));
        }
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

    private void render() {
        it = Db.get(this).item(id);
        if (it == null) {
            finish();
            return;
        }
        int accent = Ui.typeColor(this, it.type);
        Ui.pill((TextView) findViewById(R.id.pill), it.type);

        TextView title = findViewById(R.id.title), summary = findViewById(R.id.summary);
        if (it.state == 0) {
            title.setText(!Llm.any(this) ? "Waiting for an API key" : "Sorting…");
            summary.setText("Saathi reads the capture and fills this in, usually within a minute.");
        } else if (it.state == -1) {
            title.setText("Couldn't sort this one");
            summary.setText(it.error);
        } else {
            title.setText(it.title.isEmpty() ? "Untitled" : it.title);
            summary.setText(it.summary);
        }

        renderStatus(accent);

        TextView next = findViewById(R.id.nextBox);
        next.setVisibility(it.action.isEmpty() ? View.GONE : View.VISIBLE);
        if (!it.action.isEmpty()) {
            SpannableStringBuilder sb = new SpannableStringBuilder("Next step\n");
            sb.setSpan(new ForegroundColorSpan(accent), 0, sb.length(), 0);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
            sb.append(it.action);
            next.setText(sb);
        }

        LinearLayout facts = findViewById(R.id.facts);
        facts.removeAllViews();
        fact(facts, "Organisation", it.org, false);
        fact(facts, "Role", it.role, false);
        fact(facts, "Location", it.location, false);
        fact(facts, "Stipend", it.stipend, false);
        fact(facts, "Eligibility", it.eligibility, false);
        String due = Ui.dueLabel(it.deadline);
        fact(facts, "Deadline", it.deadline.isEmpty() ? "Not set — tap to add"
                : Ui.prettyDate(it.deadline) + "  ·  " + due, true);
        if (it.fit >= 0) {
            fact(facts, "Fit", it.fit + "/100" + (it.fitWhy.isEmpty() ? "" : " — " + it.fitWhy), false);
        }

        final String[] links = it.links.isEmpty() ? new String[0] : it.links.split("\n");
        TextView open = findViewById(R.id.btnOpen);
        open.setVisibility(links.length > 0 ? View.VISIBLE : View.GONE);
        if (links.length > 0) {
            open.setText("internship".equals(it.type) ? "Open apply link"
                    : "govt".equals(it.type) ? "Open official link" : "Open link");
            open.setOnClickListener(v -> openUrl(links[0]));
        }
        findViewById(R.id.btnCal).setVisibility(Ui.parseDay(it.deadline) != null ? View.VISIBLE : View.GONE);

        TextView research = findViewById(R.id.research);
        TextView retry = findViewById(R.id.btnRetry);
        retry.setVisibility(View.GONE);
        if (it.state == 2 && !it.research.isEmpty()) {
            research.setText(format(it.research, accent));
        } else if (it.state == 2) {
            research.setText("Nothing to research for this one.");
        } else if (it.state == 1) {
            research.setText(Prefs.wifiOnly(this)
                    ? "Queued. Saathi will research this when you are on Wi-Fi and tell you when it is ready."
                    : "Researching in the background. You'll get a notification when it is ready.");
        } else if (it.state < 0) {
            research.setText((it.state == -2 ? "Research didn't work: " : "") + it.error);
            retry.setVisibility(View.VISIBLE);
        } else {
            research.setText("Starts after sorting.");
        }

        LinearLayout box = findViewById(R.id.linksBox);
        box.removeAllViews();
        if (links.length > 0) {
            box.addView(label("Links"));
            for (String u : links) box.addView(linkRow(u, u));
        }
        if (!it.sources.isEmpty()) {
            box.addView(label("Sources"));
            for (String s : it.sources.split("\n")) {
                int bar = s.lastIndexOf('|');
                if (bar > 0) box.addView(linkRow(s.substring(0, bar), s.substring(bar + 1)));
            }
        }

        LinearLayout shots = findViewById(R.id.shots);
        shots.removeAllViews();
        for (String path : it.images()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setContentDescription("Captured screenshot");
            iv.setImageBitmap(BitmapFactory.decodeFile(path, o));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 10);
            shots.addView(iv, lp);
        }

        String from = Ui.appName(it.source);
        ((TextView) findViewById(R.id.meta)).setText("Saved "
                + new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(new Date(it.created))
                + (from.isEmpty() ? "" : "  ·  " + from));
    }

    private void renderStatus(int accent) {
        LinearLayout row = findViewById(R.id.statusRow);
        row.removeAllViews();
        String[] keys = {"new", "acting", "done"};
        String[] labels = {"To do", "Working on it", "Done"};
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(13);
            boolean on = k.equals(it.status);
            t.setTextColor(getColor(on ? R.color.bg : R.color.sub));
            if (on) t.setBackground(Ui.round(accent, Ui.dp(this, 10)));
            t.setOnClickListener(v -> {
                ContentValues cv = new ContentValues();
                cv.put("status", k);
                Db.get(this).set(id, cv);
                Item fresh = Db.get(this).item(id);
                if (fresh != null) Reminders.schedule(this, fresh);
            });
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void fact(LinearLayout parent, String name, String value, boolean isDeadline) {
        if (value == null || value.isEmpty()) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
        TextView k = new TextView(this);
        k.setText(name);
        k.setTextSize(13);
        k.setTextColor(getColor(R.color.sub));
        row.addView(k, new LinearLayout.LayoutParams(Ui.dp(this, 104), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setLineSpacing(Ui.dp(this, 2), 1f);
        v.setTextColor(isDeadline ? Ui.dueColor(this, it.deadline) : getColor(R.color.text));
        if (isDeadline && it.deadline.isEmpty()) v.setTextColor(getColor(R.color.accent));
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (isDeadline) row.setOnClickListener(x -> pickDeadline());
        parent.addView(row);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(Locale.US));
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTextColor(getColor(R.color.sub));
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 4));
        return t;
    }

    private TextView linkRow(String text, final String url) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextColor(getColor(R.color.accent));
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        t.setOnClickListener(v -> openUrl(url));
        return t;
    }

    /** Light formatting for the research text: "## " lines become headings, "- " lines become bullets. */
    private CharSequence format(String s, int accent) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : s.replace("**", "").split("\n")) {
            String t = line.trim();
            if (t.startsWith("#")) {
                String h = t.replaceFirst("^#+\\s*", "");
                if (sb.length() > 0) sb.append("\n");
                int start = sb.length();
                sb.append(h).append("\n");
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

    private void addTask() {
        if (it == null) return;
        long due = 0;
        Calendar day = Ui.parseDay(it.deadline);
        if (day != null) { // suggest the evening before the deadline, if that is still ahead
            day.add(Calendar.DAY_OF_YEAR, -1);
            day.set(Calendar.HOUR_OF_DAY, 18);
            if (day.getTimeInMillis() > System.currentTimeMillis()) due = day.getTimeInMillis();
        }
        TaskDialog.show(this, it.action.isEmpty() ? it.title : it.action, due, it.id);
    }

    /** Sends the item as plain text to any app: notes, WhatsApp, NotebookLM, email. */
    private void share() {
        if (it == null) return;
        StringBuilder sb = new StringBuilder(it.title).append("\n\n").append(it.summary).append("\n");
        if (!it.org.isEmpty()) sb.append("\nOrganisation: ").append(it.org);
        if (!it.role.isEmpty()) sb.append("\nRole: ").append(it.role);
        if (!it.location.isEmpty()) sb.append("\nLocation: ").append(it.location);
        if (!it.stipend.isEmpty()) sb.append("\nStipend: ").append(it.stipend);
        if (!it.eligibility.isEmpty()) sb.append("\nEligibility: ").append(it.eligibility);
        if (!it.deadline.isEmpty()) sb.append("\nDeadline: ").append(Ui.prettyDate(it.deadline));
        if (!it.action.isEmpty()) sb.append("\nNext step: ").append(it.action);
        if (!it.research.isEmpty()) sb.append("\n\n").append(it.research);
        if (!it.links.isEmpty()) sb.append("\n\nLinks:\n").append(it.links);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, it.title)
                .putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, "Share"));
    }

    private void openUrl(String url) {
        try {
            Uri u = Uri.parse(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                Toast.makeText(this, "Not a web link, so Saathi won't open it", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickDeadline() {
        Calendar cal = Ui.parseDay(it.deadline);
        if (cal == null) cal = Calendar.getInstance();
        new DatePickerDialog(this, (view, y, m, d) -> {
            String ymd = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
            ContentValues cv = new ContentValues();
            cv.put("deadline", ymd);
            Db.get(this).set(id, cv);
            Item fresh = Db.get(this).item(id);
            if (fresh != null) Reminders.schedule(this, fresh);
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void addToCalendar() {
        Calendar local = Ui.parseDay(it.deadline);
        if (local == null) return;
        // all-day events are stored at UTC midnight; local midnight would land on the previous day in IST
        Calendar day = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        day.clear();
        day.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        Intent i = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, "Deadline: " + it.title)
                .putExtra(CalendarContract.Events.DESCRIPTION,
                        it.summary + (it.links.isEmpty() ? "" : "\n\n" + it.links))
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, day.getTimeInMillis())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, day.getTimeInMillis());
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void retry() {
        boolean researchOnly = it.state == -2;
        ContentValues cv = new ContentValues();
        cv.put("state", researchOnly ? 1 : 0);
        cv.put("attempts", 0);
        cv.put("error", "");
        Db.get(this).set(id, cv);
        ProcessJob.kick(this, researchOnly ? ProcessJob.JOB_RESEARCH : ProcessJob.JOB_SORT);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage("Delete this item?")
                .setPositiveButton("Delete", (d, w) -> {
                    Db.removeListener(onChange);
                    if (it != null) Capture.deleteItem(this, it);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
