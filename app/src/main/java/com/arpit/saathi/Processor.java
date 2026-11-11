package com.arpit.saathi;

import android.content.ContentValues;
import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The two background steps: sort a capture (fast), then research it (slower, uses web search). */
public final class Processor {
    private static final int MAX_ATTEMPTS = 4;
    private static final Object LOCK = new Object();

    private Processor() {}

    /** Returns true when something failed and is worth trying again later. */
    public static boolean sortPending(Context c) {
        synchronized (LOCK) {
            String key = Prefs.key(c);
            if (key.isEmpty()) return false;
            boolean retry = false;
            for (Item stale : Db.get(c).byState(0)) {
                Item it = Db.get(c).item(stale.id);
                if (it == null || it.state != 0) continue;
                try {
                    sortOne(c, key, it);
                } catch (Exception e) {
                    retry |= failed(c, it, e, -1);
                }
                pause();
            }
            return retry;
        }
    }

    public static boolean researchPending(Context c) {
        synchronized (LOCK) {
            String key = Prefs.key(c);
            if (key.isEmpty()) return false;
            boolean retry = false;
            for (Item stale : Db.get(c).byState(1)) {
                Item it = Db.get(c).item(stale.id);
                if (it == null || it.state != 1) continue;
                if ("other".equals(it.type)) {
                    ContentValues v = new ContentValues();
                    v.put("state", 2);
                    Db.get(c).set(it.id, v);
                    continue;
                }
                try {
                    researchOne(c, key, it);
                } catch (Exception e) {
                    retry |= failed(c, it, e, -2);
                }
                pause();
            }
            return retry;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }
    }

    /** Counts the attempt; after too many, parks the item in a failed state the user can retry. */
    private static boolean failed(Context c, Item it, Exception e, int failState) {
        ContentValues v = new ContentValues();
        int attempts = it.attempts + 1;
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        v.put("attempts", attempts);
        v.put("error", msg);
        boolean giveUp = attempts >= MAX_ATTEMPTS || msg.startsWith("HTTP 400") || msg.startsWith("HTTP 403")
                || msg.startsWith("HTTP 404");
        if (giveUp) v.put("state", failState);
        Db.get(c).set(it.id, v);
        return !giveUp;
    }

    // ---------------------------------------------------------------- step 1: sort

    private static void sortOne(Context c, String key, Item it) throws Exception {
        boolean hasImage = !it.image.isEmpty() && new File(it.image).exists();
        StringBuilder p = new StringBuilder();
        p.append("You are Saathi, a personal capture assistant. The user saved something they saw on their ")
                .append("phone (usually a LinkedIn or Instagram post) so they can act on it later. Work out ")
                .append("what it is and extract the facts.\n\n");
        p.append("Today's date: ")
                .append(new SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.US).format(new Date()))
                .append(". Resolve relative or year-less dates to the next future occurrence.\n\n");
        p.append("About the user:\n").append(Prefs.profile(c)).append("\n\n");
        if (!it.source.isEmpty()) p.append("Captured from: ").append(Ui.appName(it.source)).append("\n");
        if (!it.links.isEmpty()) p.append("Links found:\n").append(it.links).append("\n");
        if (!it.raw.isEmpty()) {
            p.append("Captured screen text (may include app noise such as button labels, other posts and ")
                    .append("comments; focus on the main post):\n\"\"\"\n").append(it.raw).append("\n\"\"\"\n");
        }
        if (!hasImage && !it.links.isEmpty()) {
            String page = fetchPage(it.links.split("\n")[0]);
            if (!page.isEmpty()) p.append("Text of the linked page:\n\"\"\"\n").append(page).append("\n\"\"\"\n");
        }
        if (hasImage) {
            p.append("A screenshot is attached. Read it carefully; it is the most reliable source.\n");
        }
        p.append("\nReturn ONLY a JSON object with these keys:\n")
                .append("\"type\": \"internship\" (an internship, job, fellowship, scholarship, competition, ")
                .append("hackathon or any call to apply), \"algorithm\" (a paper, algorithm, model, technique or ")
                .append("research result), \"tool\" (a software tool, library, product, dataset, course or ")
                .append("resource) or \"other\"\n")
                .append("\"title\": short specific title, at most 70 characters\n")
                .append("\"summary\": one or two plain sentences saying what this is\n")
                .append("\"name\": exact name of the main thing (company and role, paper or algorithm name, ")
                .append("tool name), suitable as a web search query\n")
                .append("\"org\", \"role\", \"location\", \"stipend\", \"eligibility\": strings, \"\" when not ")
                .append("stated; never guess\n")
                .append("\"deadline\": last date to apply or register as YYYY-MM-DD, \"\" when not stated\n")
                .append("\"links\": array of URLs that actually appear in the capture\n")
                .append("\"fit\": integer 0-100, how worthwhile this is for this user\n")
                .append("\"fit_why\": one sentence explaining the score\n")
                .append("\"next_action\": one concrete next step for the user, at most 100 characters\n");

        JSONArray parts = new JSONArray().put(new JSONObject().put("text", p.toString()));
        if (hasImage) {
            byte[] bytes = readBytes(it.image);
            parts.put(new JSONObject().put("inline_data", new JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))));
        }

        Gemini.Reply r = Gemini.generate(key, Prefs.modelSort(c), parts, true, false);
        JSONObject o = new JSONObject(extractJson(r.text));

        String type = o.optString("type", "other").toLowerCase(Locale.US).trim();
        if (!type.equals("internship") && !type.equals("algorithm") && !type.equals("tool")) type = "other";
        String deadline = o.optString("deadline", "").trim();
        if (Ui.parseDay(deadline) == null) deadline = "";

        StringBuilder links = new StringBuilder(it.links);
        JSONArray la = o.optJSONArray("links");
        if (la != null) {
            for (int i = 0; i < la.length(); i++) {
                String u = la.optString(i, "").trim();
                if (u.startsWith("http") && links.indexOf(u) < 0) {
                    if (links.length() > 0) links.append("\n");
                    links.append(u);
                }
            }
        }

        ContentValues v = new ContentValues();
        v.put("type", type);
        v.put("title", clean(o, "title"));
        v.put("summary", clean(o, "summary"));
        v.put("name", clean(o, "name"));
        v.put("org", clean(o, "org"));
        v.put("role", clean(o, "role"));
        v.put("location", clean(o, "location"));
        v.put("stipend", clean(o, "stipend"));
        v.put("eligibility", clean(o, "eligibility"));
        v.put("deadline", deadline);
        v.put("fit", Math.max(0, Math.min(100, o.optInt("fit", 50))));
        v.put("fit_why", clean(o, "fit_why"));
        v.put("action", clean(o, "next_action"));
        v.put("links", links.toString());
        v.put("state", 1);
        v.put("attempts", 0);
        v.put("error", "");
        Db.get(c).set(it.id, v);

        Item fresh = Db.get(c).item(it.id);
        if (fresh != null && !deadline.isEmpty()) {
            Reminders.schedule(c, fresh);
            long left = Ui.daysLeft(deadline);
            if (left >= 0 && left <= 2) {
                Notify.post(c, (int) (1000 + it.id), App.CH_DEADLINE,
                        Ui.dueLabel(deadline) + ": " + fresh.title, fresh.action, it.id);
            }
        }
    }

    private static byte[] readBytes(String path) throws Exception {
        File f = new File(path);
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

    private static String clean(JSONObject o, String k) {
        String s = o.isNull(k) ? "" : o.optString(k, "").trim();
        if (s.equalsIgnoreCase("null") || s.equalsIgnoreCase("n/a") || s.equalsIgnoreCase("not stated")
                || s.equalsIgnoreCase("unknown")) {
            return "";
        }
        return s;
    }

    /** Models sometimes wrap JSON in a code fence or add a sentence; keep only the object. */
    private static String extractJson(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalStateException("Model did not return JSON");
        return s.substring(a, b + 1);
    }

    /** Best-effort plain text of a public page. Login-walled pages simply return little or nothing. */
    private static String fetchPage(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36");
            if (c.getResponseCode() >= 400) return "";
            String type = c.getContentType();
            if (type != null && !type.contains("html") && !type.contains("text")) return "";
            String html = Gemini.readAll(c.getInputStream(), 400 * 1024);
            String text = html.replaceAll("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?s)<[^>]+>", " ")
                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                    .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                    .replaceAll("\\s+", " ").trim();
            return text.length() > 5000 ? text.substring(0, 5000) : text;
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- step 2: research

    private static void researchOne(Context c, String key, Item it) throws Exception {
        StringBuilder p = new StringBuilder();
        p.append("Today's date: ").append(Ui.today()).append("\n\nAbout the user:\n")
                .append(Prefs.profile(c)).append("\n\n");
        p.append("The user saved this from their phone:\n");
        p.append("Title: ").append(it.title).append("\n");
        if (!it.name.isEmpty()) p.append("Name: ").append(it.name).append("\n");
        p.append("Summary: ").append(it.summary).append("\n");
        if (!it.org.isEmpty()) p.append("Organisation: ").append(it.org).append("\n");
        if (!it.role.isEmpty()) p.append("Role: ").append(it.role).append("\n");
        if (!it.deadline.isEmpty()) p.append("Deadline seen in the post: ").append(it.deadline).append("\n");
        if (!it.links.isEmpty()) p.append("Links:\n").append(it.links).append("\n");
        if (!it.raw.isEmpty()) {
            String raw = it.raw.length() > 2500 ? it.raw.substring(0, 2500) : it.raw;
            p.append("Captured text:\n\"\"\"\n").append(raw).append("\n\"\"\"\n");
        }
        p.append("\n");

        boolean internship = "internship".equals(it.type);
        if (internship) {
            p.append("Research this opportunity on the web. Find the official apply or registration page, the ")
                    .append("confirmed deadline, stipend, duration, location and eligibility, and what the ")
                    .append("organisation does. Then say honestly how well it suits this user and what they ")
                    .append("should highlight when applying. Warn clearly if it looks like a scam or a ")
                    .append("pay-to-apply scheme.\n")
                    .append("Use these sections: ## The opportunity, ## Key facts, ## Fit for you, ## How to apply.\n");
        } else if ("algorithm".equals(it.type)) {
            p.append("Research this on the web and explain it to the user at their level. Find the paper and ")
                    .append("any code.\n")
                    .append("Use these sections: ## What it is, ## Key idea, ## Results that matter, ")
                    .append("## How it relates to your work (be specific about their robot and stack, or say ")
                    .append("plainly if it does not apply), ## Try it (paper link, code link, a first step).\n");
        } else {
            p.append("Research this tool or resource on the web.\n")
                    .append("Use these sections: ## What it does, ## Cost and limits (free tier, student ")
                    .append("offers), ## How you could use it (specific to this user's projects), ")
                    .append("## Alternatives, ## Getting started.\n");
        }
        p.append("Format: plain text. Each section heading on its own line starting with '## '. Bullets ")
                .append("start with '- '. No bold, no tables. Under 350 words. Say so when something could ")
                .append("not be confirmed; never invent facts, dates or links.\n");
        if (internship) {
            p.append("Finish with exactly these two lines:\n")
                    .append("DEADLINE: YYYY-MM-DD or unknown\n")
                    .append("APPLY_LINK: full URL or unknown\n");
        }

        JSONArray parts = new JSONArray().put(new JSONObject().put("text", p.toString()));
        String model = Prefs.modelResearch(c);
        Gemini.Reply r;
        String note = "";
        try {
            r = Gemini.generate(key, model, parts, false, true);
        } catch (Exception searchFailed) {
            // e.g. this model has no free web search: fall back to what the model already knows
            r = Gemini.generate(key, model, parts, false, false);
            note = "Live web search was not available for this one, so this is from the model's own "
                    + "knowledge. Check dates and links before relying on them.\n\n";
        }

        String deadline = "", apply = "";
        StringBuilder body = new StringBuilder();
        for (String line : r.text.split("\n")) {
            String t = line.trim();
            if (t.toUpperCase(Locale.US).startsWith("DEADLINE:")) {
                deadline = t.substring(9).trim();
            } else if (t.toUpperCase(Locale.US).startsWith("APPLY_LINK:")) {
                apply = t.substring(11).trim();
            } else {
                body.append(line).append("\n");
            }
        }

        StringBuilder src = new StringBuilder();
        for (String[] s : r.sources) {
            src.append(s[0].replace("|", " ").replace("\n", " ")).append("|").append(s[1]).append("\n");
        }

        ContentValues v = new ContentValues();
        v.put("research", note + body.toString().trim());
        v.put("sources", src.toString().trim());
        v.put("state", 2);
        v.put("attempts", 0);
        v.put("error", "");
        if (apply.startsWith("http") && !it.links.contains(apply)) {
            v.put("links", it.links.isEmpty() ? apply : apply + "\n" + it.links);
        }
        boolean newDeadline = it.deadline.isEmpty() && Ui.parseDay(deadline) != null;
        if (newDeadline) v.put("deadline", deadline);
        Db.get(c).set(it.id, v);

        Item fresh = Db.get(c).item(it.id);
        if (fresh == null) return;
        if (newDeadline) Reminders.schedule(c, fresh);
        String due = Ui.dueLabel(fresh.deadline);
        Notify.post(c, (int) (2000 + it.id), App.CH_UPDATE, "Research ready: " + fresh.title,
                (due.isEmpty() ? "" : due + ". ") + fresh.action, it.id);
    }
}
