package com.arpit.saathi.gate;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import com.arpit.saathi.App;
import com.arpit.saathi.Gemini;
import com.arpit.saathi.Llm;
import com.arpit.saathi.Notify;
import com.arpit.saathi.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Everything GATE that talks to a model: reading mock PDFs, solutions, notes, practice, the progress report. */
public final class GateWork {
    private static final Object LOCK = new Object();
    private static final int MAX_PAGES = 80, PAGES_PER_CALL = 3;

    private GateWork() {}

    public static File dir(Context c, long mockId) {
        File d = new File(c.getFilesDir(), "gate/" + mockId);
        d.mkdirs();
        return d;
    }

    public static File page(Context c, long mockId, int n) {
        return new File(dir(c, mockId), "p" + n + ".jpg");
    }

    static String extractJson(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("The model did not return JSON");
        return s.substring(a, b + 1);
    }

    private static byte[] read(File f) throws Exception {
        byte[] out = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return out;
    }

    private static JSONObject image(File f) throws Exception {
        return new JSONObject().put("inline_data", new JSONObject().put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(read(f), Base64.NO_WRAP)));
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    // ================================================================ 1. importing a mock PDF

    /** Background job entry: reads every queued mock. Returns true if something should be retried later. */
    public static boolean importPending(Context c) {
        synchronized (LOCK) {
            boolean retry = false;
            for (Gate.Mock m : GateDb.get(c).mocks()) {
                if (!"queued".equals(m.status) && !"reading".equals(m.status)) continue;
                if (Prefs.key(c).isEmpty()) {
                    progress(c, m.id, "queued", "Waiting for a Gemini key (Settings)");
                    continue;
                }
                try {
                    importOne(c, m);
                } catch (Exception e) {
                    String err = msg(e);
                    boolean transient_ = err.contains("HTTP 429") || err.contains("HTTP 5") || err.contains("timeout")
                            || err.contains("Unable to resolve");
                    ContentValues v = new ContentValues();
                    v.put("status", transient_ ? "reading" : "failed");
                    v.put("error", err);
                    GateDb.get(c).setMock(m.id, v);
                    retry |= transient_;
                }
            }
            return retry;
        }
    }

    private static void progress(Context c, long id, String status, String text) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("progress", text);
        GateDb.get(c).setMock(id, v);
    }

    /** PDF → one JPEG per page, so the PDF itself is not needed again. */
    private static int render(Context c, long mockId) throws Exception {
        File src = new File(dir(c, mockId), "source.pdf");
        if (!src.exists()) {
            int n = 0;
            while (page(c, mockId, n + 1).exists()) n++;
            if (n == 0) throw new Exception("The PDF is missing. Delete this mock and add it again.");
            return n;
        }
        int count;
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(fd)) {
            count = Math.min(r.getPageCount(), MAX_PAGES);
            for (int i = 0; i < count; i++) {
                progress(c, mockId, "reading", "Preparing page " + (i + 1) + " of " + count);
                try (PdfRenderer.Page p = r.openPage(i)) {
                    int w = 1600, h = Math.max(1, Math.round(w * (float) p.getHeight() / p.getWidth()));
                    if (h > 2600) { // very tall pages: cap the size, keep the shape
                        h = 2600;
                        w = Math.max(1, Math.round(h * (float) p.getWidth() / p.getHeight()));
                    }
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    new Canvas(b).drawColor(Color.WHITE);
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    try (FileOutputStream os = new FileOutputStream(page(c, mockId, i + 1))) {
                        b.compress(Bitmap.CompressFormat.JPEG, 82, os);
                    }
                    b.recycle();
                }
            }
        }
        src.delete();
        ContentValues v = new ContentValues();
        v.put("pages", count);
        GateDb.get(c).setMock(mockId, v);
        return count;
    }

    private static void importOne(Context c, Gate.Mock m) throws Exception {
        int pages = render(c, m.id);
        String doneKey = "gate_done_" + m.id;
        int done = Prefs.sp(c).getInt(doneKey, 0); // pages fully read so far (resumes after an interruption)
        boolean first = done == 0;
        for (int start = done + 1; start <= pages; start += PAGES_PER_CALL) {
            int end = Math.min(pages, start + PAGES_PER_CALL - 1);
            progress(c, m.id, "reading", "Reading pages " + start + "–" + end + " of " + pages);
            readBatch(c, m, start, end, pages, first);
            first = false;
            Prefs.sp(c).edit().putInt(doneKey, end).apply();
            Thread.sleep(1500);
        }
        GateDb db = GateDb.get(c);
        db.recount(m.id);
        Gate.Mock fin = db.mock(m.id);
        ContentValues v = new ContentValues();
        v.put("status", fin != null && fin.questions > 0 ? "ready" : "failed");
        v.put("progress", "");
        v.put("error", fin != null && fin.questions > 0 ? "" : "No questions could be read from this PDF.");
        db.setMock(m.id, v);
        Prefs.sp(c).edit().remove(doneKey).apply();
        if (fin != null && fin.questions > 0) {
            db.log("mock", fin.title + ": " + fmt(fin.score) + " / " + fmt(fin.max) + " (" + Math.round(fin.pct())
                    + "%), " + fin.correct + " right, " + fin.wrong + " wrong, " + (fin.questions - fin.attempted)
                    + " skipped", 0);
            Notify.post(c, (int) (6000 + m.id), App.CH_UPDATE, "Mock ready: " + fmt(fin.score) + " / " + fmt(fin.max),
                    fin.title + ". Tap any question for its full solution.",
                    Notify.screen(c, MockActivity.class).putExtra("id", m.id));
        }
    }

    public static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0);
    }

    private static void readBatch(Context c, Gate.Mock m, int start, int end, int pages, boolean first)
            throws Exception {
        int look = Math.min(pages, end + 1); // one extra page so a question cut at the bottom is read whole
        StringBuilder p = new StringBuilder();
        p.append("These are consecutive pages ").append(start).append(" to ").append(look).append(" of a GATE Data ")
                .append("Science and AI mock test result, exported as screenshots. Read them carefully, including all ")
                .append("mathematics.\n\nExtract every question whose \"Question N\" heading appears on pages ")
                .append(start).append(" to ").append(end).append(". ");
        if (look > end) {
            p.append("Page ").append(look).append(" is only there so you can finish a question that continues onto ")
                    .append("it; do not start new questions from page ").append(look).append(". ");
        }
        p.append("If a question's heading is on an earlier page than ").append(start).append(" but its options or ")
                .append("answers continue here, also return it with the same number so the missing parts can be ")
                .append("added.\n\nFor each question give:\n")
                .append("- n: the question number\n")
                .append("- type: \"mcq\" (one option), \"msq\" (several options can be right) or \"nat\" (typed number)\n")
                .append("- marks: marks the question carries\n")
                .append("- question: the full question text, exactly as shown. Write mathematics in LaTeX between $ ")
                .append("signs ($...$ inline, $$...$$ for display). Write matrices with \\\\begin{bmatrix}. Keep ")
                .append("line breaks between separate statements.\n")
                .append("- options: for mcq/msq, every option as {\"l\": \"A\", \"t\": \"option text with LaTeX\"}; ")
                .append("empty for nat\n")
                .append("- yours: the answer the student gave (option letters like \"B\" or \"A,C\", or the typed ")
                .append("value); empty if not attempted\n")
                .append("- correct: the right answer shown on the page (letters, a value, or a range like ")
                .append("\"0.155 - 0.161\"); empty if the page does not show it\n")
                .append("- result: \"correct\", \"wrong\", \"partial\" or \"unattempted\", as the page states\n")
                .append("- score and max: the score shown, e.g. 0.5 and 1\n")
                .append("- topic: the number of the syllabus topic below that this question tests\n")
                .append("- page: the page number (from ").append(start).append(" to ").append(look)
                .append(") where its heading is\n\n")
                .append("Ignore navigation bars, side menus and anything that is not a question. Do not solve ")
                .append("anything; only copy what the page shows. In JSON strings, escape every LaTeX backslash ")
                .append("as two backslashes.\n\nSYLLABUS:\n").append(Gate.numbered()).append("\n");
        if (first) {
            p.append("Also give \"title\": the test's name as shown, and \"date\": its date as YYYY-MM-DD if shown.\n");
        }
        p.append("Return ONLY JSON: {").append(first ? "\"title\": \"\", \"date\": \"\", " : "")
                .append("\"questions\": [{\"n\": 1, \"type\": \"mcq\", \"marks\": 1, \"question\": \"\", ")
                .append("\"options\": [], \"yours\": \"\", \"correct\": \"\", \"result\": \"\", \"score\": 0, ")
                .append("\"max\": 1, \"topic\": 0, \"page\": ").append(start).append("}]}\n");

        JSONArray parts = new JSONArray().put(new JSONObject().put("text", p.toString()));
        for (int i = start; i <= look; i++) parts.put(image(page(c, m.id, i)));
        Gemini.Reply r = Gemini.generate(Prefs.key(c), Prefs.modelResearch(c), parts, true, false);
        JSONObject o = new JSONObject(extractJson(r.text));

        GateDb db = GateDb.get(c);
        if (first) {
            ContentValues v = new ContentValues();
            String title = o.optString("title", "").trim();
            if (!title.isEmpty() && title.length() < 120) v.put("title", title);
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                f.setLenient(false);
                Date d = f.parse(o.optString("date", "").trim());
                if (d != null && d.getTime() < System.currentTimeMillis() + 86400000L) v.put("taken", d.getTime());
            } catch (Exception ignored) {
            }
            if (v.size() > 0) db.setMock(m.id, v);
        }

        Map<Integer, Gate.Q> have = new HashMap<>();
        for (Gate.Q q : db.qs(m.id)) have.put(q.n, q);
        List<String> keys = Gate.allKeys();
        JSONArray qa = o.optJSONArray("questions");
        for (int i = 0; qa != null && i < qa.length(); i++) {
            JSONObject j = qa.optJSONObject(i);
            if (j == null || j.optInt("n", 0) <= 0) continue;
            Gate.Q q = new Gate.Q();
            q.mockId = m.id;
            q.n = j.optInt("n");
            q.type = j.optString("type", "mcq").toLowerCase(Locale.US).trim();
            if (!q.type.matches("mcq|msq|nat")) q.type = "mcq";
            q.question = j.optString("question", "").trim();
            JSONArray opts = j.optJSONArray("options");
            q.options = opts == null || opts.length() == 0 ? "" : opts.toString();
            q.yours = clean(j.optString("yours", ""));
            q.correct = clean(j.optString("correct", ""));
            q.result = j.optString("result", "").toLowerCase(Locale.US).trim();
            if (!q.result.matches("correct|wrong|partial|unattempted")) {
                q.result = q.yours.isEmpty() ? "unattempted" : "wrong";
            }
            q.max = Math.max(0, j.optDouble("max", j.optDouble("marks", 1)));
            if (Double.isNaN(q.max)) q.max = 1;
            q.marks = Math.max(0, j.optDouble("marks", q.max));
            if (Double.isNaN(q.marks)) q.marks = q.max;
            q.score = j.optDouble("score", "correct".equals(q.result) ? q.max : 0);
            if (Double.isNaN(q.score)) q.score = 0;
            q.score = Math.min(q.score, q.max);
            int t = j.optInt("topic", 0);
            if (t >= 1 && t <= keys.size()) {
                String k = keys.get(t - 1);
                int slash = k.indexOf(" / ");
                q.subject = k.substring(0, slash);
                q.topic = k.substring(slash + 3);
            }
            int pg = j.optInt("page", start);
            q.page = pg >= start && pg <= look ? pg : start;
            q.pageTo = Math.min(look, q.page + 1);

            Gate.Q old = have.get(q.n);
            if (old == null) {
                q.id = db.addQ(q);
                have.put(q.n, q);
            } else { // the same question seen again across a page break: keep the most complete version
                if (q.question.length() > old.question.length()) old.question = q.question;
                if (old.options.isEmpty() || q.options.length() > old.options.length()) {
                    if (!q.options.isEmpty()) old.options = q.options;
                }
                if (old.correct.isEmpty()) old.correct = q.correct;
                if (old.yours.isEmpty()) old.yours = q.yours;
                if (old.topic.isEmpty()) {
                    old.subject = q.subject;
                    old.topic = q.topic;
                }
                old.page = Math.min(old.page, q.page);
                old.pageTo = Math.max(old.pageTo, q.pageTo);
                db.updateQ(old);
            }
        }
    }

    private static String clean(String s) {
        s = s == null ? "" : s.trim();
        String l = s.toLowerCase(Locale.US);
        return l.equals("none") || l.equals("null") || l.equals("no answer given") || l.equals("not attempted")
                || l.equals("-") ? "" : s;
    }

    // ================================================================ 2. solutions and follow-ups

    static String questionBlock(Gate.Q q) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION ").append(q.n).append(" (").append(Gate.typeLabel(q.type)).append(", ")
                .append(fmt(q.marks)).append(" marks)\n").append(q.question).append("\n");
        try {
            JSONArray opts = new JSONArray(q.options.isEmpty() ? "[]" : q.options);
            for (int i = 0; i < opts.length(); i++) {
                JSONObject o = opts.getJSONObject(i);
                sb.append("(").append(o.optString("l")).append(") ").append(o.optString("t")).append("\n");
            }
        } catch (Exception ignored) {
        }
        sb.append("Official answer key: ").append(q.correct.isEmpty() ? "not shown" : q.correct).append("\n")
                .append("Student's answer: ").append(q.yours.isEmpty() ? "not attempted" : q.yours)
                .append(" (marked ").append(q.result).append(")\n");
        if (!q.topic.isEmpty()) sb.append("Syllabus topic: ").append(q.subject).append(" / ").append(q.topic).append("\n");
        return sb.toString();
    }

    private static final String STYLE = "Write for a strong student preparing for GATE DA. Use LaTeX for all "
            + "mathematics: $...$ inline and $$...$$ on its own line for displayed steps. Use '## ' for section "
            + "headings, '- ' for bullets, **bold** for key terms. No tables.";

    /** Full worked solution with the concept behind it. Uses the original page image so figures are seen. */
    public static String solve(Context c, long qId) throws Exception {
        Gate.Q q = GateDb.get(c).q(qId);
        if (q == null) throw new Exception("Question not found");
        String prompt = "You are an expert GATE Data Science and AI tutor. Explain this question completely.\n\n"
                + questionBlock(q) + "\nWrite these sections:\n"
                + "## Answer\nThe final answer in one line. Solve it yourself first; if your answer differs from "
                + "the official key, say so plainly in a line starting with '> ' and explain which is right and why.\n"
                + "## The concept\nThe idea this question tests, taught from the ground up: intuition first, then the "
                + "precise definition or result, and why it works. Enough that a student who forgot the topic "
                + "understands it.\n"
                + "## Step-by-step solution\nEvery step, numbered, with the reasoning for each step. Show all "
                + "calculations.\n"
                + (q.options.isEmpty() ? "" : "## Why the other options are wrong\nOne line per option.\n")
                + ("correct".equals(q.result) ? "" : q.yours.isEmpty() ? "" : "## Where your answer went wrong\n"
                + "The most likely mistake that leads to the student's answer, in 2-3 lines.\n")
                + "## Shortcut\nA faster exam-time method, if one exists; otherwise skip this section.\n"
                + "## Remember\n2-4 bullets: the formula, trap or fact worth memorising from this question.\n\n"
                + STYLE + "\nThe question text was read from a screenshot; if the attached page shows something the "
                + "text missed (a figure, a table), use the page.\n";

        String gem = Prefs.key(c);
        String out;
        if (!gem.isEmpty()) {
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", prompt));
            for (int pg = q.page; pg >= 1 && pg <= q.pageTo && pg <= q.page + 1; pg++) {
                File f = page(c, q.mockId, pg);
                if (f.exists()) parts.put(image(f));
            }
            try {
                out = Gemini.generate(gem, Prefs.modelResearch(c), parts, false, false).text;
            } catch (Exception e) {
                if (!Llm.hasBackup(c)) throw e;
                out = Llm.backup(c, prompt);
            }
        } else {
            out = Llm.chat(c, prompt);
        }
        GateDb.get(c).setQ(qId, "solution", out.trim());
        return out;
    }

    /** A follow-up question about one mock question, answered with the solution in view. */
    public static void ask(Context c, long qId, String question) throws Exception {
        GateDb db = GateDb.get(c);
        Gate.Q q = db.q(qId);
        if (q == null) return;
        JSONArray chat = new JSONArray(q.chat.isEmpty() ? "[]" : q.chat);
        chat.put(new JSONObject().put("q", question).put("a", ""));
        db.setQ(qId, "chat", chat.toString());
        StringBuilder p = new StringBuilder("You are the student's GATE DA tutor. They are looking at this question "
                + "and its solution and have a follow-up.\n\n").append(questionBlock(q))
                .append("\nSOLUTION SHOWN TO THEM:\n").append(q.solution).append("\n\n");
        for (int i = 0; i < chat.length() - 1; i++) {
            JSONObject t = chat.getJSONObject(i);
            p.append("Student: ").append(t.optString("q")).append("\nTutor: ").append(t.optString("a")).append("\n");
        }
        p.append("Student: ").append(question).append("\n\nAnswer the follow-up directly and clearly, as long as it ")
                .append("needs and no longer. ").append(STYLE).append("\n");
        String a;
        try {
            a = Llm.chat(c, p.toString()).trim();
        } catch (Exception e) {
            a = "> That didn't work: " + msg(e);
        }
        chat.getJSONObject(chat.length() - 1).put("a", a);
        db.setQ(qId, "chat", chat.toString());
    }

    // ================================================================ 3. revision

    /** How this student has done on a topic in their mocks, for the notes and the topic page. */
    public static int[] topicStats(Context c, String key) {
        int total = 0, ok = 0, bad = 0, skip = 0;
        for (Gate.Q q : GateDb.get(c).allQs()) {
            if (q.max <= 0 || !key.equals(Gate.key(q.subject, q.topic))) continue;
            total++;
            if ("correct".equals(q.result)) ok++;
            else if ("unattempted".equals(q.result)) skip++;
            else bad++;
        }
        return new int[]{total, ok, bad, skip};
    }

    public static String notes(Context c, String key) throws Exception {
        Gate.Topic t = GateDb.get(c).topic(key);
        StringBuilder slips = new StringBuilder();
        int n = 0;
        for (Gate.Q q : GateDb.get(c).allQs()) {
            if (n >= 4 || "correct".equals(q.result) || !key.equals(Gate.key(q.subject, q.topic))) continue;
            n++;
            String text = q.question.length() > 400 ? q.question.substring(0, 400) + "…" : q.question;
            slips.append("- ").append(text.replace('\n', ' ')).append(" (key: ").append(q.correct)
                    .append(", student: ").append(q.yours.isEmpty() ? "skipped" : q.yours).append(")\n");
        }
        String prompt = "You are an expert GATE Data Science and AI teacher. Write complete revision notes on one "
                + "syllabus topic for a student who wants to master it for the exam.\n\nTOPIC: " + t.name
                + "\nSUBJECT: " + t.subject + "\n\n"
                + (slips.length() > 0 ? "Questions on this topic the student got wrong or skipped in mocks:\n" + slips
                + "\n" : "")
                + "Sections:\n"
                + "## The big idea\nWhat this topic is about and why it matters, in plain words, with intuition.\n"
                + "## Definitions\nEvery term GATE expects, stated precisely.\n"
                + "## Key results and formulas\nEach with its conditions and a one-line meaning. Display formulas.\n"
                + "## Why it works\nThe derivation or proof sketch of the most important result.\n"
                + "## Worked examples\nThree GATE-level problems of rising difficulty, each with a full solution.\n"
                + "## How GATE asks this\nThe question patterns that recur and how to recognise them.\n"
                + "## Traps\nThe mistakes students make, and how to avoid them.\n"
                + (slips.length() > 0 ? "## Where you slipped\nWhat the wrong or skipped questions above have in "
                + "common, and the exact idea to fix.\n" : "")
                + "## One-minute recap\n5-8 bullets to reread just before the exam.\n\n"
                + "Cover everything the GATE DA syllabus includes under this topic; be thorough but never padded. "
                + STYLE + "\n";
        String out = Llm.chat(c, prompt).trim();
        GateDb.get(c).setTopic(key, "notes", out);
        return out;
    }

    public static String practice(Context c, String key) throws Exception {
        Gate.Topic t = GateDb.get(c).topic(key);
        String prompt = "Write 5 GATE Data Science and AI exam-level practice questions on: " + t.subject + " / "
                + t.name + ". Mix difficulty (2 moderate, 3 hard), and include at least one numerical-answer "
                + "question. Make each answer certainly correct: solve each one yourself before writing it.\n"
                + "Use LaTeX between $ signs for mathematics, and escape every backslash as two backslashes in the "
                + "JSON.\nReturn ONLY JSON: {\"questions\": [{\"q\": \"question\", \"options\": [{\"l\": \"A\", "
                + "\"t\": \"...\"}] (empty for numerical), \"answer\": \"letter(s) or value\", \"why\": \"full "
                + "solution, a few lines, LaTeX allowed\"}]}";
        JSONObject o = new JSONObject(extractJson(Llm.chat(c, prompt)));
        JSONArray qs = o.optJSONArray("questions");
        String out = qs == null ? "[]" : qs.toString();
        GateDb.get(c).setTopic(key, "practice", out);
        return out;
    }

    // ================================================================ 4. the report to share with Claude

    public static String report(Context c) {
        GateDb db = GateDb.get(c);
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat day = new SimpleDateFormat("d MMM yyyy", Locale.US);
        sb.append("# GATE DA progress (from Saathi)\n\nExported ")
                .append(new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(new Date())).append("\n\n");

        List<Gate.Mock> mocks = new ArrayList<>();
        for (Gate.Mock m : db.mocks()) if (m.ready()) mocks.add(m);
        sb.append("## Mocks (").append(mocks.size()).append(")\n\n");
        if (mocks.isEmpty()) sb.append("None yet.\n\n");
        else {
            sb.append("| Date | Mock | Score | % | Right | Wrong | Partial | Skipped |\n|---|---|---|---|---|---|---|---|\n");
            for (Gate.Mock m : mocks) {
                sb.append("| ").append(day.format(new Date(m.taken))).append(" | ").append(m.title.replace("|", "/"))
                        .append(" | ").append(fmt(m.score)).append(" / ").append(fmt(m.max)).append(" | ")
                        .append(Math.round(m.pct())).append(" | ").append(m.correct).append(" | ").append(m.wrong)
                        .append(" | ").append(m.partial).append(" | ").append(m.questions - m.attempted)
                        .append(" |\n");
            }
            sb.append("\n");
        }

        Map<String, int[]> byTopic = new HashMap<>();
        for (Gate.Q q : db.allQs()) {
            if (q.max <= 0 || q.topic.isEmpty()) continue;
            int[] s = byTopic.get(Gate.key(q.subject, q.topic));
            if (s == null) byTopic.put(Gate.key(q.subject, q.topic), s = new int[4]);
            s[0]++;
            if ("correct".equals(q.result)) s[1]++;
            else if ("unattempted".equals(q.result)) s[3]++;
            else s[2]++;
        }
        sb.append("## Topic accuracy across all mocks\n\n");
        if (byTopic.isEmpty()) sb.append("No tagged questions yet.\n\n");
        else {
            List<Map.Entry<String, int[]>> rows = new ArrayList<>(byTopic.entrySet());
            rows.sort((a, b) -> Double.compare((double) a.getValue()[1] / a.getValue()[0],
                    (double) b.getValue()[1] / b.getValue()[0]));
            sb.append("| Topic | Questions | Right | Wrong | Skipped |\n|---|---|---|---|---|\n");
            for (Map.Entry<String, int[]> e : rows) {
                int[] s = e.getValue();
                sb.append("| ").append(e.getKey()).append(" | ").append(s[0]).append(" | ").append(s[1]).append(" | ")
                        .append(s[2]).append(" | ").append(s[3]).append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## Topics revised in Saathi\n\n");
        String[] conf = {"not rated", "shaky", "okay", "strong"};
        int revised = 0;
        for (String k : Gate.allKeys()) {
            Gate.Topic t = db.topic(k);
            if (t.revised <= 0) continue;
            revised++;
            sb.append("- ").append(k).append(": revised ").append(day.format(new Date(t.revised))).append(", feels ")
                    .append(conf[Math.max(0, Math.min(3, t.confidence))]).append("\n");
        }
        if (revised == 0) sb.append("None marked yet.\n");
        sb.append("\n## Study log (latest first)\n\n");
        List<Gate.LogEntry> logs = db.logs(60);
        if (logs.isEmpty()) sb.append("Nothing logged yet.\n");
        for (Gate.LogEntry e : logs) {
            sb.append("- ").append(day.format(new Date(e.at))).append(": ").append(e.text.replace('\n', ' '));
            if (e.minutes > 0) sb.append(" (").append(e.minutes).append(" min)");
            sb.append("\n");
        }
        return sb.toString();
    }
}
