package com.arpit.saathi;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class Ui {
    public static final long NO_DATE = Long.MIN_VALUE;

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static int typeColor(Context c, String type) {
        switch (type == null ? "" : type) {
            case "internship": return c.getColor(R.color.teal);
            case "algorithm": return c.getColor(R.color.violet);
            case "tool": return c.getColor(R.color.amber);
            default: return c.getColor(R.color.grey);
        }
    }

    public static String typeLabel(String type) {
        switch (type == null ? "" : type) {
            case "internship": return "OPPORTUNITY";
            case "algorithm": return "ALGORITHM";
            case "tool": return "TOOL";
            case "pending": return "NEW";
            default: return "OTHER";
        }
    }

    /** Tinted pill: coloured text on a faint wash of the same colour. */
    public static void pill(TextView tv, String type) {
        Context c = tv.getContext();
        int col = typeColor(c, type);
        tv.setText(typeLabel(type));
        tv.setTextColor(col);
        tv.setBackground(round(Color.argb(40, Color.red(col), Color.green(col), Color.blue(col)), dp(c, 20)));
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Local midnight of a yyyy-MM-dd date, or null when it does not parse. */
    public static Calendar parseDay(String ymd) {
        if (ymd == null || !ymd.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            Calendar cal = Calendar.getInstance();
            cal.setTime(f.parse(ymd));
            return cal;
        } catch (Exception e) {
            return null;
        }
    }

    /** Whole days from today to the date (negative when passed), or NO_DATE. */
    public static long daysLeft(String ymd) {
        Calendar d = parseDay(ymd);
        if (d == null) return NO_DATE;
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, 0);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        return Math.round((d.getTimeInMillis() - t.getTimeInMillis()) / 86400000.0);
    }

    public static String dueLabel(String ymd) {
        long n = daysLeft(ymd);
        if (n == NO_DATE) return "";
        if (n < 0) return "Closed";
        if (n == 0) return "Due today";
        if (n == 1) return "Due tomorrow";
        return n + " days left";
    }

    public static int dueColor(Context c, String ymd) {
        long n = daysLeft(ymd);
        if (n == NO_DATE || n < 0) return c.getColor(R.color.grey);
        if (n <= 2) return c.getColor(R.color.red);
        if (n <= 7) return c.getColor(R.color.amber);
        return c.getColor(R.color.green);
    }

    public static String prettyDate(String ymd) {
        Calendar d = parseDay(ymd);
        if (d == null) return "";
        return new SimpleDateFormat("EEE, d MMM yyyy", Locale.US).format(d.getTime());
    }

    public static String appName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        switch (pkg) {
            case "com.linkedin.android": return "LinkedIn";
            case "com.instagram.android": return "Instagram";
            case "com.android.chrome": return "Chrome";
            case "com.google.android.youtube": return "YouTube";
            case "com.twitter.android": return "X";
            case "com.whatsapp": return "WhatsApp";
            case "share": return "Share menu";
            case "manual": return "Added by hand";
            default:
                int i = pkg.lastIndexOf('.');
                return i >= 0 && i < pkg.length() - 1 ? pkg.substring(i + 1) : pkg;
        }
    }
}
