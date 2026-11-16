package com.arpit.saathi.gate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.arpit.saathi.ProcessJob;
import com.arpit.saathi.R;
import com.arpit.saathi.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GATE home: mocks with scores, the syllabus to revise, and the study log you can share with Claude. */
public class GateActivity extends Activity {
    private static final int REQ_PDF = 41, REQ_SAVE = 42;
    private static final String[] TABS = {"Mocks", "Revise", "Log"};

    private final Runnable onChange = this::render;
    private LinearLayout content, tabs;
    private TextView sub;
    private int tab;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tab = getIntent().getIntExtra("tab", 0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = Views.topBar(this, "GATE DA", "");
        sub = bar.findViewWithTag("sub");
        root.addView(bar);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 6));
        hs.addView(tabs);
        root.addView(hs);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        content.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 40));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        GateDb.addListener(onChange);
        render();
        ProcessJob.kick(this, ProcessJob.JOB_GATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        GateDb.removeListener(onChange);
    }

    private void render() {
        tabs.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = Views.chip(this, TABS[i], i == tab);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 8));
            t.setOnClickListener(v -> {
                tab = idx;
                render();
            });
            tabs.addView(t, lp);
        }
        content.removeAllViews();
        List<Gate.Mock> ready = new ArrayList<>();
        for (Gate.Mock m : GateDb.get(this).mocks()) if (m.ready() && m.max > 0) ready.add(m);
        sub.setVisibility(View.VISIBLE);
        sub.setText(ready.isEmpty() ? "Data Science and AI" : ready.size() + " mocks  ·  last "
                + Math.round(ready.get(0).pct()) + "%");
        if (tab == 0) mocks(ready);
        else if (tab == 1) revise();
        else log();
    }

    // ---------------------------------------------------------------- mocks tab

    private void mocks(List<Gate.Mock> ready) {
        if (!ready.isEmpty()) {
            LinearLayout card = Views.card(this);
            double best = 0, recent = 0;
            int n = Math.min(3, ready.size());
            for (Gate.Mock m : ready) best = Math.max(best, m.pct());
            for (int i = 0; i < n; i++) recent += ready.get(i).pct();
            card.addView(Views.title(this, Math.round(recent / n) + "%", 30));
            card.addView(Views.text(this, "average of your last " + (n == 1 ? "mock" : n + " mocks") + "  ·  best "
                    + Math.round(best) + "%", 13, getColor(R.color.sub)));
            if (ready.size() >= 2) {
                List<Double> pts = new ArrayList<>();
                for (Gate.Mock m : ready) pts.add(m.pct());
                Collections.reverse(pts);
                View chart = new Views.Trend(this, pts);
                ((LinearLayout.LayoutParams) chart.getLayoutParams()).topMargin = Ui.dp(this, 14);
                card.addView(chart);
            }
            content.addView(card);
        }

        LinearLayout row = new LinearLayout(this);
        TextView pdf = Views.button(this, "Add mock PDF", true);
        pdf.setOnClickListener(v -> pickPdf());
        TextView manual = Views.button(this, "Add score only", false);
        manual.setOnClickListener(v -> manualDialog());
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        a.setMarginEnd(Ui.dp(this, 5));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        b.setMarginStart(Ui.dp(this, 5));
        row.addView(pdf, a);
        row.addView(manual, b);
        content.addView(row);

        List<Gate.Mock> all = GateDb.get(this).mocks();
        if (all.isEmpty()) {
            TextView t = Views.text(this, "Add the result PDF of a mock (the page that shows your answers and the "
                    + "answer key). Saathi reads every question, stores your marks, and explains each question in "
                    + "full when you tap it.", 14, getColor(R.color.sub));
            t.setPadding(0, Ui.dp(this, 24), 0, 0);
            content.addView(t);
            return;
        }
        content.addView(Views.label(this, "Your mocks"));
        SimpleDateFormat day = new SimpleDateFormat("d MMM yyyy", Locale.US);
        for (Gate.Mock m : all) {
            LinearLayout card = Views.card(this);
            card.addView(Views.title(this, m.title, 16));
            String line;
            int color = getColor(R.color.sub);
            if (m.ready() && m.max > 0) {
                line = GateWork.fmt(m.score) + " / " + GateWork.fmt(m.max) + "  ·  " + Math.round(m.pct()) + "%"
                        + ("manual".equals(m.source) ? "" : "  ·  " + m.correct + " right, " + m.wrong + " wrong, "
                        + (m.questions - m.attempted) + " skipped");
                color = getColor(R.color.text);
            } else if ("failed".equals(m.status)) {
                line = "Couldn't read this PDF: " + m.error;
                color = getColor(R.color.red);
            } else {
                line = m.progress.isEmpty() ? "Waiting to be read…" : m.progress + "…";
                color = getColor(R.color.amber);
            }
            card.addView(Views.text(this, line, 14, color));
            card.addView(Views.text(this, day.format(new Date(m.taken)), 12, getColor(R.color.grey)));
            if (m.ready() && m.max > 0) {
                LinearLayout bar = new LinearLayout(this);
                bar.setBackground(Ui.round(getColor(R.color.stroke), Ui.dp(this, 3)));
                View fill = new View(this);
                fill.setBackgroundResource(R.drawable.bg_btn);
                bar.addView(fill, new LinearLayout.LayoutParams(0, Ui.dp(this, 6), (float) Math.max(0.01, m.pct())));
                bar.addView(new View(this), new LinearLayout.LayoutParams(0, Ui.dp(this, 6),
                        (float) Math.max(0.01, 100 - m.pct())));
                LinearLayout.LayoutParams lp = Views.wide();
                lp.topMargin = Ui.dp(this, 10);
                card.addView(bar, lp);
            }
            final Gate.Mock fm = m;
            card.setOnClickListener(v -> {
                if ("manual".equals(fm.source)) return;
                startActivity(new Intent(this, MockActivity.class).putExtra("id", fm.id));
            });
            card.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setMessage("Delete \"" + fm.title + "\" and its questions?")
                        .setPositiveButton("Delete", (d, w) -> deleteMock(fm.id))
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            content.addView(card);
        }
        content.addView(Views.text(this, "Hold a mock to delete it.", 12, getColor(R.color.grey)));
    }

    private void deleteMock(long id) {
        File[] files = GateWork.dir(this, id).listFiles();
        if (files != null) for (File f : files) f.delete();
        GateWork.dir(this, id).delete();
        GateDb.get(this).deleteMock(id);
    }

    private void pickPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf");
        try {
            startActivityForResult(i, REQ_PDF);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n.replaceAll("(?i)\\.pdf$", "").replace('_', ' ').replace('-', ' ').trim();
            }
        } catch (Exception ignored) {
        }
        return "Mock test";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_PDF) {
            String name = displayName(uri);
            if (name.length() > 70) name = name.substring(0, 70);
            final long id = GateDb.get(this).addMock(name, "pdf", System.currentTimeMillis());
            ContentValues v = new ContentValues();
            v.put("status", "copying");
            v.put("progress", "Copying the PDF");
            GateDb.get(this).setMock(id, v);
            new Thread(() -> {
                boolean ok = false;
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(new File(GateWork.dir(this, id), "source.pdf"))) {
                    byte[] buf = new byte[65536];
                    int n;
                    long total = 0;
                    while (in != null && (n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                        if (total > 80L * 1024 * 1024) throw new Exception("too big");
                    }
                    ok = total > 0;
                } catch (Exception ignored) {
                }
                ContentValues done = new ContentValues();
                done.put("status", ok ? "queued" : "failed");
                done.put("progress", "");
                done.put("error", ok ? "" : "The PDF couldn't be opened (or is over 80 MB).");
                GateDb.get(this).setMock(id, done);
                if (ok) ProcessJob.kick(getApplicationContext(), ProcessJob.JOB_GATE);
            }).start();
            Toast.makeText(this, "Reading it now. A notification comes when it's ready.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_SAVE) {
            final String text = GateWork.report(this);
            new Thread(() -> {
                boolean ok;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
                final boolean fOk = ok;
                runOnUiThread(() -> Toast.makeText(this, fOk ? "Saved. Ask Claude to read \"Saathi GATE progress\" "
                        + "from your Drive." : "Couldn't save the file", Toast.LENGTH_LONG).show());
            }).start();
        }
    }

    private void manualDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        final EditText name = new EditText(this);
        name.setHint("Mock name (e.g. ACE full-length 3)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(name);
        final EditText score = new EditText(this);
        score.setHint("Your marks");
        score.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        box.addView(score);
        final EditText max = new EditText(this);
        max.setHint("Out of");
        max.setText("100");
        max.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(max);
        final Calendar when = Calendar.getInstance();
        final TextView date = Views.text(this, "Taken on " + new SimpleDateFormat("d MMM yyyy", Locale.US)
                .format(when.getTime()) + "  (change)", 15, getColor(R.color.accent));
        date.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        date.setOnClickListener(v -> new DatePickerDialog(this, (dv, y, m, d) -> {
            when.set(y, m, d);
            date.setText("Taken on " + new SimpleDateFormat("d MMM yyyy", Locale.US).format(when.getTime()));
        }, when.get(Calendar.YEAR), when.get(Calendar.MONTH), when.get(Calendar.DAY_OF_MONTH)).show());
        box.addView(date);
        new AlertDialog.Builder(this).setTitle("Add a mock score").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        double s = Double.parseDouble(score.getText().toString().trim());
                        double mx = Double.parseDouble(max.getText().toString().trim());
                        if (mx <= 0) throw new NumberFormatException();
                        String t = name.getText().toString().trim();
                        if (t.isEmpty()) t = "Mock test";
                        long id = GateDb.get(this).addMock(t, "manual", when.getTimeInMillis());
                        ContentValues v = new ContentValues();
                        v.put("score", s);
                        v.put("max", mx);
                        GateDb.get(this).setMock(id, v);
                        GateDb.get(this).log("mock", t + ": " + GateWork.fmt(s) + " / " + GateWork.fmt(mx) + " ("
                                + Math.round(100 * s / mx) + "%)", 0);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Enter the marks and the total as numbers", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- revise tab

    private void revise() {
        GateDb db = GateDb.get(this);
        Map<String, int[]> stats = new HashMap<>();
        for (Gate.Q q : db.allQs()) {
            if (q.max <= 0 || q.topic.isEmpty()) continue;
            String k = Gate.key(q.subject, q.topic);
            int[] s = stats.get(k);
            if (s == null) stats.put(k, s = new int[3]);
            s[0]++;
            if ("correct".equals(q.result)) s[1]++;
            else if (!"unattempted".equals(q.result)) s[2]++;
        }

        List<String> weak = new ArrayList<>();
        for (Map.Entry<String, int[]> e : stats.entrySet()) if (e.getValue()[1] < e.getValue()[0]) weak.add(e.getKey());
        weak.sort((x, y) -> {
            int[] a = stats.get(x), b = stats.get(y);
            int c = Double.compare((double) a[1] / a[0], (double) b[1] / b[0]);
            return c != 0 ? c : Integer.compare(b[0], a[0]);
        });
        if (!weak.isEmpty()) {
            content.addView(Views.label(this, "Focus next (from your mocks)"));
            LinearLayout card = Views.card(this);
            for (int i = 0; i < Math.min(5, weak.size()); i++) card.addView(topicRow(weak.get(i), stats.get(weak.get(i)), true));
            content.addView(card);
        }

        for (Map.Entry<String, String[]> e : Gate.SYLLABUS.entrySet()) {
            content.addView(Views.label(this, e.getKey()));
            LinearLayout card = Views.card(this);
            card.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
            for (String t : e.getValue()) {
                String k = Gate.key(e.getKey(), t);
                card.addView(topicRow(k, stats.get(k), false));
            }
            content.addView(card);
        }
    }

    private View topicRow(final String key, int[] s, boolean showSubject) {
        Gate.Topic t = GateDb.get(this).topic(key);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        View dot = new View(this);
        int[] conf = {R.color.stroke, R.color.red, R.color.amber, R.color.green};
        dot.setBackground(Ui.round(getColor(conf[Math.max(0, Math.min(3, t.confidence))]), Ui.dp(this, 5)));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dp.setMarginEnd(Ui.dp(this, 12));
        row.addView(dot, dp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Views.text(this, t.name, 15, getColor(R.color.text)));
        StringBuilder meta = new StringBuilder();
        if (showSubject) meta.append(t.subject);
        if (s != null) meta.append(meta.length() > 0 ? "  ·  " : "").append(s[1]).append("/").append(s[0]).append(" right in mocks");
        if (t.revised > 0) meta.append(meta.length() > 0 ? "  ·  " : "").append("revised ")
                .append(new SimpleDateFormat("d MMM", Locale.US).format(new Date(t.revised)));
        else if (!t.notes.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append("notes ready");
        if (meta.length() > 0) col.addView(Views.text(this, meta.toString(), 12, getColor(R.color.sub)));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> startActivity(new Intent(this, TopicActivity.class).putExtra("key", key)));
        return row;
    }

    // ---------------------------------------------------------------- log tab

    private void log() {
        LinearLayout row = new LinearLayout(this);
        TextView add = Views.button(this, "Log what I did", true);
        add.setOnClickListener(v -> logDialog());
        TextView share = Views.button(this, "Share with Claude", false);
        share.setOnClickListener(v -> shareDialog());
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        a.setMarginEnd(Ui.dp(this, 5));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        b.setMarginStart(Ui.dp(this, 5));
        row.addView(add, a);
        row.addView(share, b);
        content.addView(row);
        TextView hint = Views.text(this, "Share with Claude puts your mocks, topic accuracy, revision and this log "
                + "into one file. Save it to Google Drive and Claude can read it whenever you ask it to look.", 13,
                getColor(R.color.sub));
        hint.setPadding(0, Ui.dp(this, 10), 0, 0);
        content.addView(hint);

        List<Gate.LogEntry> logs = GateDb.get(this).logs(200);
        if (logs.isEmpty()) return;
        content.addView(Views.label(this, "History"));
        SimpleDateFormat day = new SimpleDateFormat("EEE d MMM, h:mm a", Locale.US);
        int minutes = 0;
        long weekAgo = System.currentTimeMillis() - 7L * 86400000L;
        for (Gate.LogEntry e : logs) if (e.at >= weekAgo) minutes += e.minutes;
        if (minutes > 0) {
            content.addView(Views.text(this, "Logged this week: " + (minutes / 60) + " h " + (minutes % 60) + " min",
                    14, getColor(R.color.text)));
        }
        for (final Gate.LogEntry e : logs) {
            LinearLayout card = Views.card(this);
            String kind = "mock".equals(e.kind) ? "MOCK" : "revise".equals(e.kind) ? "REVISED" : "STUDY";
            TextView k = Views.text(this, kind + "  ·  " + day.format(new Date(e.at))
                    + (e.minutes > 0 ? "  ·  " + e.minutes + " min" : ""), 11, getColor(R.color.accent));
            k.setLetterSpacing(0.06f);
            card.addView(k);
            TextView t = Views.text(this, e.text, 15, getColor(R.color.text));
            t.setPadding(0, Ui.dp(this, 4), 0, 0);
            card.addView(t);
            card.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setMessage("Delete this entry?")
                        .setPositiveButton("Delete", (d, w) -> GateDb.get(this).deleteLog(e.id))
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            content.addView(card);
        }
    }

    private void logDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        final EditText what = new EditText(this);
        what.setHint("What did you study, practise or plan?");
        what.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        what.setMinLines(3);
        what.setGravity(Gravity.TOP);
        box.addView(what);
        final EditText mins = new EditText(this);
        mins.setHint("Minutes (optional)");
        mins.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(mins);
        new AlertDialog.Builder(this).setTitle("Log study").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String t = what.getText().toString().trim();
                    if (t.isEmpty()) return;
                    int m = 0;
                    try {
                        m = Math.max(0, Math.min(1440, Integer.parseInt(mins.getText().toString().trim())));
                    } catch (Exception ignored) {
                    }
                    GateDb.get(this).log("study", t, m);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void shareDialog() {
        new AlertDialog.Builder(this).setTitle("Share with Claude")
                .setItems(new CharSequence[]{"Save to a file (pick Google Drive)", "Copy, to paste into a Claude chat",
                        "Send to another app"}, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("text/markdown").putExtra(Intent.EXTRA_TITLE, "Saathi GATE progress.md");
                        try {
                            startActivityForResult(i, REQ_SAVE);
                        } catch (Exception e) {
                            Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 1) {
                        ClipboardManager cm = getSystemService(ClipboardManager.class);
                        cm.setPrimaryClip(ClipData.newPlainText("GATE progress", GateWork.report(this)));
                        Toast.makeText(this, "Copied. Paste it into a Claude chat.", Toast.LENGTH_SHORT).show();
                    } else {
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "GATE progress")
                                .putExtra(Intent.EXTRA_TEXT, GateWork.report(this)), "Share"));
                    }
                }).show();
    }
}
