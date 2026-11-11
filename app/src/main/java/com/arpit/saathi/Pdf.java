package com.arpit.saathi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/** Lays text out on A4 pages. Used for mission reports, the full team log and single saved items. */
final class Pdf {
    private static final int W = 595, H = 842, M = 50;

    private final PdfDocument doc = new PdfDocument();
    private final TextPaint title = paint(20, true, 0xFF111827), h = paint(13, true, 0xFF3B4CCA),
            body = paint(10.5f, false, 0xFF1F2937), small = paint(8.5f, false, 0xFF6B7280);
    private PdfDocument.Page page;
    private Canvas cv;
    private float y;
    private int pageNo;

    private Pdf() {
        newPage();
    }

    private static TextPaint paint(float size, boolean bold, int color) {
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    private void newPage() {
        endPage();
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
        cv = page.getCanvas();
        y = M;
    }

    private void endPage() {
        if (page == null) return;
        cv.drawText("Saathi  ·  page " + pageNo, M, H - 26, small);
        doc.finishPage(page);
        page = null;
    }

    private void text(String s, TextPaint p, float gapAfter) {
        if (s == null || s.trim().isEmpty()) return;
        StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), p, W - 2 * M)
                .setLineSpacing(2.5f, 1f).build();
        for (int i = 0; i < l.getLineCount(); i++) {
            float lineHeight = l.getLineBottom(i) - l.getLineTop(i);
            if (y + lineHeight > H - M) newPage();
            int start = l.getLineStart(i), end = l.getLineEnd(i);
            while (end > start && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
            cv.drawText(s, start, end, M, y + (l.getLineBaseline(i) - l.getLineTop(i)), p);
            y += lineHeight;
        }
        y += gapAfter;
    }

    private void gap(float g) {
        y += g;
    }

    /** Text using the app's light markup: "## " headings and "- " bullets. */
    private void rich(String s) {
        for (String line : s.replace("**", "").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("#")) {
                gap(6);
                text(t.replaceFirst("^#+\\s*", ""), h, 4);
            } else if (t.startsWith("- ") || t.startsWith("* ")) {
                text("•  " + t.substring(2), body, 2);
            } else {
                text(t, body, 4);
            }
        }
    }

    private void sources(String packed) {
        if (packed == null || packed.isEmpty()) return;
        for (String s : packed.split("\n")) {
            int bar = s.lastIndexOf('|');
            if (bar > 0) text(s.substring(0, bar) + "  —  " + s.substring(bar + 1), small, 2);
        }
    }

    private void write(OutputStream os) throws Exception {
        endPage();
        doc.writeTo(os);
        doc.close();
    }

    private static String date(long ms) {
        return new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(new Date(ms));
    }

    // ---------------------------------------------------------------- documents

    private void mission(Context c, Team.Mission m, boolean appendix) {
        List<Team.Step> steps = Db.get(c).steps(m.id);
        int searches = 0;
        for (Team.Step s : steps) searches += s.searches;
        text(m.title.isEmpty() ? m.goal : m.title, title, 4);
        text(date(m.created) + "  ·  " + m.status + "  ·  " + steps.size() + " agent tasks  ·  " + searches
                + " web searches", small, 8);
        if (!"report".equals(m.kind)) {
            text("Mission", h, 3);
            text(m.goal, body, 6);
        }
        if (!m.report.isEmpty()) rich(m.report);
        if (!m.error.isEmpty()) text("Problem: " + m.error, body, 6);
        if (!appendix || steps.isEmpty()) return;
        gap(10);
        text("Each agent's work in full", title, 6);
        for (Team.Step s : steps) {
            text(Team.agentName(s.agent) + ": " + s.task, h, 3);
            rich(s.output);
            sources(s.sources);
            gap(8);
        }
    }

    static Pdf ofMission(Context c, long missionId) {
        Pdf p = new Pdf();
        Team.Mission m = Db.get(c).mission(missionId);
        if (m != null) p.mission(c, m, true);
        return p;
    }

    /** Everything the team has done, newest first, with totals up front. */
    static Pdf ofAllMissions(Context c) {
        Pdf p = new Pdf();
        List<Team.Mission> all = Db.get(c).missions();
        List<Team.Step> steps = Db.get(c).steps(0);
        int searches = 0;
        for (Team.Step s : steps) searches += s.searches;
        p.text("Saathi team log", p.title, 4);
        p.text("Made " + date(System.currentTimeMillis()) + "  ·  " + all.size() + " missions  ·  " + steps.size()
                + " agent tasks  ·  " + searches + " web searches", p.small, 10);
        for (Team.Agent a : Team.AGENTS) {
            int n = 0, s = 0;
            for (Team.Step st : steps) {
                if (!a.key.equals(st.agent)) continue;
                n++;
                s += st.searches;
            }
            p.text("•  " + a.name + ": " + n + " tasks" + (a.search ? ", " + s + " web searches" : ""), p.body, 2);
        }
        for (Team.Mission m : all) {
            p.newPage();
            p.mission(c, m, true);
        }
        return p;
    }

    static Pdf ofItem(Item it) {
        Pdf p = new Pdf();
        p.text(it.title.isEmpty() ? "Saved item" : it.title, p.title, 4);
        p.text(Ui.typeLabel(it.type) + "  ·  saved " + date(it.created), p.small, 8);
        p.text(it.summary, p.body, 6);
        String[][] facts = {{"Organisation", it.org}, {"Role", it.role}, {"Location", it.location},
                {"Stipend", it.stipend}, {"Eligibility", it.eligibility},
                {"Deadline", Ui.prettyDate(it.deadline)}, {"Next step", it.action}};
        for (String[] f : facts) if (!f[1].isEmpty()) p.text(f[0] + ": " + f[1], p.body, 2);
        if (!it.research.isEmpty()) {
            p.gap(8);
            p.rich(it.research);
        }
        if (!it.links.isEmpty()) {
            p.gap(6);
            p.text("Links", p.h, 3);
            for (String u : it.links.split("\n")) p.text(u, p.small, 2);
        }
        p.sources(it.sources);
        return p;
    }

    // ---------------------------------------------------------------- saving

    /** Opens the phone's "save file" screen; the activity gets the chosen place back in onActivityResult. */
    static void askWhere(Activity a, String name, int requestCode) {
        String safe = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (safe.length() > 50) safe = safe.substring(0, 50).trim();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, "Saathi - " + (safe.isEmpty() ? "report" : safe) + ".pdf");
        try {
            a.startActivityForResult(i, requestCode);
        } catch (Exception e) {
            Toast.makeText(a, "No file manager found to save the PDF", Toast.LENGTH_LONG).show();
        }
    }

    static void save(final Activity a, final Uri where, final Callable<Pdf> build) {
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            boolean ok;
            try (OutputStream os = app.getContentResolver().openOutputStream(where)) {
                build.call().write(os);
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
            final boolean fOk = ok;
            a.runOnUiThread(() -> {
                Toast.makeText(app, fOk ? "PDF saved" : "Couldn't write the PDF", Toast.LENGTH_SHORT).show();
                if (!fOk || a.isFinishing()) return;
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(where, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (Exception noViewer) {
                    // saved, just nothing installed to open it
                }
            });
        }, "saathi-pdf").start();
    }
}
