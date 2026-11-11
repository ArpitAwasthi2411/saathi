package com.arpit.saathi;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stores a capture and queues it for sorting. Safe to call from any thread. */
public final class Capture {
    /** Captures from the same app this close together are treated as parts of one long post. */
    static final long MERGE_WINDOW_MS = 90_000;
    static final int MAX_IMAGES = 4;
    private static final int MAX_TEXT = 14000;
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>)\\]]+");
    private static final String[] SENSITIVE = {"bank", "paytm", "phonepe", "paisa", "bhim", "upi", "wallet",
            "mobikwik", "dreamplug", "authenticator", "password", "keepass", "bitwarden", "lastpass",
            "digilocker", "aadhaar", "umang", "netbanking"};

    private Capture() {}

    /** Banking, payment and password apps are never captured. */
    public static boolean sensitive(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.US);
        for (String s : SENSITIVE) if (p.contains(s)) return true;
        return false;
    }

    public static boolean canMerge(Context c, String source) {
        if (source == null || source.isEmpty() || "manual".equals(source)) return false;
        SharedPreferences sp = Prefs.sp(c);
        if (!source.equals(sp.getString("lastCapSrc", ""))) return false;
        if (System.currentTimeMillis() - sp.getLong("lastCapAt", 0) > MERGE_WINDOW_MS) return false;
        Item it = Db.get(c).item(sp.getLong("lastCapId", -1));
        return it != null && !"done".equals(it.status) && !"note".equals(it.type);
    }

    public static synchronized long save(Context ctx, String text, String extraUrl, String source, Bitmap shot,
                                         boolean allowMerge) {
        Context c = ctx.getApplicationContext();
        text = text == null ? "" : text.trim();
        if (text.length() > 8000) text = text.substring(0, 8000);
        source = source == null ? "" : source;

        Set<String> urls = new LinkedHashSet<>();
        if (extraUrl != null && extraUrl.startsWith("http")) urls.add(extraUrl);
        Matcher m = URL.matcher(text);
        while (m.find() && urls.size() < 6) urls.add(m.group());

        Db db = Db.get(c);
        long id;
        Item prev = allowMerge && canMerge(c, source) ? db.item(Prefs.sp(c).getLong("lastCapId", -1)) : null;
        if (prev != null) {
            for (String u : prev.links.split("\n")) if (!u.trim().isEmpty()) urls.add(u.trim());
            String image = prev.image;
            int count = image.trim().isEmpty() ? 0 : image.split("\n").length;
            if (shot != null && count < MAX_IMAGES) {
                String path = writeJpeg(c, shot);
                if (!path.isEmpty()) image = image.trim().isEmpty() ? path : image + "\n" + path;
            }
            ContentValues v = new ContentValues();
            v.put("raw", mergeText(prev.raw, text));
            v.put("links", join(urls));
            v.put("image", image);
            v.put("state", 0);
            v.put("attempts", 0);
            v.put("error", "");
            v.put("rev", prev.rev + 1);
            db.set(prev.id, v);
            id = prev.id;
        } else {
            id = db.insertCapture(text, join(urls), source, shot == null ? "" : writeJpeg(c, shot));
        }
        Prefs.sp(c).edit().putLong("lastCapId", id).putLong("lastCapAt", System.currentTimeMillis())
                .putString("lastCapSrc", source).apply();
        ProcessJob.kick(c, ProcessJob.JOB_SORT);
        return id;
    }

    private static String join(Set<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (String u : urls) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(u);
        }
        return sb.toString();
    }

    /** Appends only the lines the earlier capture did not already have (scrolled screens overlap). */
    private static String mergeText(String a, String b) {
        Set<String> seen = new HashSet<>();
        for (String line : a.split("\n")) seen.add(line.trim());
        StringBuilder sb = new StringBuilder(a);
        for (String line : b.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || !seen.add(t)) continue;
            if (sb.length() + t.length() > MAX_TEXT) break;
            if (sb.length() > 0) sb.append("\n");
            sb.append(t);
        }
        return sb.toString();
    }

    private static String writeJpeg(Context c, Bitmap src) {
        try {
            Bitmap b = src;
            if (b.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
                if (copy != null) b = copy;
            }
            int w = b.getWidth(), h = b.getHeight(), longSide = Math.max(w, h);
            if (longSide > 1600) {
                float s = 1600f / longSide;
                b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
            }
            File dir = new File(c.getFilesDir(), "shots");
            dir.mkdirs();
            File f = new File(dir, System.currentTimeMillis() + "_" + (System.nanoTime() % 100000) + ".jpg");
            try (FileOutputStream os = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.JPEG, 82, os);
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Removes an item together with its screenshots and reminders. */
    public static void deleteItem(Context c, Item it) {
        Reminders.cancel(c, it.id);
        for (String p : it.image.split("\n")) if (!p.trim().isEmpty()) new File(p.trim()).delete();
        Db.get(c).delete(it.id);
    }
}
