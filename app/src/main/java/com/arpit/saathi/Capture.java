package com.arpit.saathi;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stores a capture and queues it for sorting. Safe to call from any thread. */
public final class Capture {
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>)\\]]+");

    private Capture() {}

    public static long save(Context ctx, String text, String extraUrl, String source, Bitmap shot) {
        Context c = ctx.getApplicationContext();
        text = text == null ? "" : text.trim();
        if (text.length() > 8000) text = text.substring(0, 8000);

        Set<String> urls = new LinkedHashSet<>();
        if (extraUrl != null && extraUrl.startsWith("http")) urls.add(extraUrl);
        Matcher m = URL.matcher(text);
        while (m.find() && urls.size() < 6) urls.add(m.group());
        StringBuilder links = new StringBuilder();
        for (String u : urls) links.append(u).append("\n");

        String path = "";
        if (shot != null) {
            try {
                path = writeJpeg(c, shot);
            } catch (Exception ignored) {
            }
        }
        long id = Db.get(c).insertCapture(text, links.toString().trim(), source == null ? "" : source, path);
        ProcessJob.kick(c, ProcessJob.JOB_SORT);
        return id;
    }

    private static String writeJpeg(Context c, Bitmap src) throws Exception {
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
        File f = new File(dir, System.currentTimeMillis() + ".jpg");
        try (FileOutputStream os = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 82, os);
        }
        return f.getAbsolutePath();
    }
}
