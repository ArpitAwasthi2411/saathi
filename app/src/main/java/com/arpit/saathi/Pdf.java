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
            body = paint(10.5f, false, 0xFF1F2937), small = paint(8.5f, false, 0xFF6B7280),
            bold = paint(11.5f, true, 0xFF111827), accent = paint(9.5f, true, 0xFF3B4CCA);
    private float indent;
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
        StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), p, (int) (W - 2 * M - indent))
                .setLineSpacing(2.5f, 1f).build();
        for (int i = 0; i < l.getLineCount(); i++) {
            float lineHeight = l.getLineBottom(i) - l.getLineTop(i);
            if (y + lineHeight > H - M) newPage();
            int start = l.getLineStart(i), end = l.getLineEnd(i);
            while (end > start && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
            cv.drawText(s, start, end, M + indent, y + (l.getLineBaseline(i) - l.getLineTop(i)), p);
            y += lineHeight;
        }
        y += gapAfter;
    }

    private void gap(float g) {
        y += g;
    }

    /** Text using the app's light markup: "## " headings and "- " bullets (indented ones stay indented). */
    private void rich(String s) {
        for (String line : Ui.plain(s).split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("#")) {
                gap(6);
                text(t.replaceFirst("^#+\\s*", ""), h, 4);
            } else if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) {
                boolean nested = line.startsWith("  ") || line.startsWith("\t");
                indent = nested ? 26 : 10;
                text((nested ? "–  " : "•  ") + t.substring(2).trim(), body, 2);
                indent = 0;
            } else {
                text(t, body, 4);
            }
        }
    }

    /** Numbered list of pages, shown by site name with the real address; opaque redirect links are skipped. */
    private void sources(String packed) {
        if (packed == null || packed.isEmpty()) return;
        int n = 0;
        for (String s : packed.split("\n")) {
            int bar = s.lastIndexOf('|');
            if (bar <= 0) continue;
            String url = s.substring(bar + 1), name = s.substring(0, bar).trim();
            boolean opaque = Ui.site(url).isEmpty();
            if (name.length() > 80) name = name.substring(0, 80) + "…";
            text(++n + ". " + name + (opaque ? "" : "  —  " + (url.length() > 110 ? url.substring(0, 110) + "…" : url)),
                    small, 2);
        }
    }

    private void rule() {
        if (y + 14 > H - M) newPage();
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFFD1D5DB);
        p.setStrokeWidth(0.8f);
        cv.drawLine(M, y + 4, W - M, y + 4, p);
        y += 14;
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

    /** A mission as a short brief: summary, ranked results with their source, next steps, then a compact log. */
    private void mission(Context c, Team.Mission m, boolean withLog) {
        List<Team.Step> steps = Db.get(c).steps(m.id);
        List<Team.Finding> found = Db.get(c).findings(m.id);
        int searches = 0;
        for (Team.Step s : steps) searches += s.searches;

        text(m.title.isEmpty() ? m.goal : m.title, title, 4);
        text(date(m.created) + ("report".equals(m.kind) ? "" : "  ·  " + searches + " web searches  ·  "
                + found.size() + " sourced results") + ("done".equals(m.status) ? "" : "  ·  " + m.status), small, 6);
        if (!"report".equals(m.kind) && !m.title.isEmpty()) text("Asked: " + m.goal, small, 8);

        // the report is "summary, then ## Next steps": results go between the two
        String summary = m.report, rest = "";
        int cut = m.report.indexOf("\n## ");
        if (cut >= 0) {
            summary = m.report.substring(0, cut);
            rest = m.report.substring(cut + 1);
        }
        if (!summary.trim().isEmpty()) {
            if (!"report".equals(m.kind)) text("Summary", h, 4);
            rich(summary);
        }
        if (!m.error.isEmpty()) text("Did not finish: " + m.error, body, 6);

        if (!found.isEmpty()) {
            gap(8);
            text("Results", h, 6);
            int n = 0;
            for (Team.Finding f : found) {
                String head = ++n + ".  " + f.title;
                text(head, bold, 2);
                indent = 16;
                StringBuilder tag = new StringBuilder();
                if (f.fit >= 0) tag.append("Fit ").append(f.fit).append("/100");
                if (!f.deadline.isEmpty()) {
                    tag.append(tag.length() > 0 ? "   ·   " : "").append("Deadline ").append(Ui.prettyDate(f.deadline));
                } else if (!f.status.isEmpty()) {
                    tag.append(tag.length() > 0 ? "   ·   " : "").append("Status: ").append(f.status);
                }
                if (tag.length() > 0) text(tag.toString(), accent, 2);
                text(f.what, body, 2);
                if (!f.facts.isEmpty()) text(f.facts.replace("|", ": ").replace("\n", "   ·   "), body, 2);
                if (!f.why.isEmpty()) text("Why it fits: " + f.why, body, 2);
                text("Source: " + (f.url.length() > 120 ? f.url.substring(0, 120) + "…" : f.url), small, 0);
                indent = 0;
                gap(9);
            }
        }
        if (!rest.trim().isEmpty()) rich(rest);

        // drafts and notes written by the non-research agents are part of the deliverable
        for (Team.Step s : steps) {
            Team.Agent a = Team.agent(s.agent);
            boolean research = a != null && a.search;
            if (!"done".equals(s.status) || s.output.trim().isEmpty()) continue;
            if (research && s.found > 0) continue;
            if (research && s.output.startsWith("Searched:")) continue;
            if ("analyst".equals(s.agent) && s.output.startsWith("Ranked ")) continue;
            gap(6);
            text("From " + Team.agentName(s.agent), h, 4);
            rich(s.output);
            if (research) sources(s.sources);
        }

        if (!withLog || steps.isEmpty()) return;
        gap(8);
        rule();
        text("How the team worked", h, 4);
        for (Team.Step s : steps) {
            Team.Agent a = Team.agent(s.agent);
            String task = s.task.length() > 120 ? s.task.substring(0, 120) + "…" : s.task;
            String count = "fail".equals(s.status) ? "failed"
                    : a != null && a.search ? s.searches + " searches, " + s.found + " results" : s.status;
            text(Team.agentName(s.agent) + " (" + count + "): " + task, small, 3);
        }
    }

    static Pdf ofMission(Context c, long missionId) {
        Pdf p = new Pdf();
        Team.Mission m = Db.get(c).mission(missionId);
        if (m != null) p.mission(c, m, true);
        return p;
    }

    /** Everything the team has done, newest first, with totals up front. Missions follow on from each other. */
    static Pdf ofAllMissions(Context c) {
        Pdf p = new Pdf();
        List<Team.Mission> all = Db.get(c).missions();
        List<Team.Step> steps = Db.get(c).steps(0);
        int searches = 0, results = 0;
        for (Team.Step s : steps) {
            searches += s.searches;
            results += s.found;
        }
        p.text("Saathi team log", p.title, 4);
        p.text(date(System.currentTimeMillis()) + "  ·  " + all.size() + " missions  ·  " + searches
                + " web searches  ·  " + results + " sourced results", p.small, 8);
        StringBuilder who = new StringBuilder();
        for (Team.Agent a : Team.AGENTS) {
            int n = 0;
            for (Team.Step st : steps) if (a.key.equals(st.agent) && "done".equals(st.status)) n++;
            who.append(who.length() > 0 ? "   ·   " : "").append(a.name).append(" ").append(n);
        }
        p.text("Steps completed:   " + who, p.body, 4);
        for (Team.Mission m : all) {
            p.gap(6);
            p.rule();
            if (p.y > H - 220) p.newPage(); // don't start a mission at the very bottom of a page
            p.mission(c, m, false);
        }
        return p;
    }

    static Pdf ofItem(Item it) {
        Pdf p = new Pdf();
        p.text(it.title.isEmpty() ? "Saved item" : it.title, p.title, 4);
        p.text(Ui.typeLabel(it.type) + "  ·  saved " + date(it.created), p.small, 8);
        p.text(Ui.plain(it.summary), p.body, 6);
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
