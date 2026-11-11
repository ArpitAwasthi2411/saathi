package com.arpit.saathi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** Deadline alerts (7 days, 2 days, on the day at 9 am), task reminders at their set time, daily digest. */
public final class Reminders {
    static final int[] OFFSETS = {7, 2, 0};
    private static final int TASK_BASE = 1_000_000;

    private Reminders() {}

    private static PendingIntent pi(Context c, String action, long id, int days, int code) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action).putExtra("id", id).putExtra("days", days);
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context c, Item it) {
        cancel(c, it.id);
        Calendar day = Ui.parseDay(it.deadline);
        if (day == null || "done".equals(it.status)) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (int k = 0; k < OFFSETS.length; k++) {
            Calendar t = (Calendar) day.clone();
            t.add(Calendar.DAY_OF_YEAR, -OFFSETS[k]);
            t.set(Calendar.HOUR_OF_DAY, 9);
            if (t.getTimeInMillis() <= System.currentTimeMillis()) continue;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(),
                    pi(c, "deadline", it.id, OFFSETS[k], (int) (it.id * 10 + k)));
        }
    }

    public static void cancel(Context c, long id) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (int k = 0; k < OFFSETS.length; k++) {
            am.cancel(pi(c, "deadline", id, OFFSETS[k], (int) (id * 10 + k)));
        }
    }

    public static void scheduleTask(Context c, Task t) {
        if (t == null) return;
        cancelTask(c, t.id);
        if (t.done || t.due <= System.currentTimeMillis()) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent p = pi(c, "task", t.id, 0, (int) (TASK_BASE + t.id));
        try {
            // on-the-minute when the phone allows it, otherwise within a few minutes
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.due, p);
                return;
            }
        } catch (SecurityException ignored) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.due, p);
    }

    public static void cancelTask(Context c, long id) {
        c.getSystemService(AlarmManager.class).cancel(pi(c, "task", id, 0, (int) (TASK_BASE + id)));
    }

    public static void scheduleDigest(Context c) {
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, Prefs.digestHour(c));
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis() + 60000) t.add(Calendar.DAY_OF_YEAR, 1);
        c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                t.getTimeInMillis(), pi(c, "digest", 0, 0, 5));
    }

    public static void rescheduleAll(Context c) {
        for (Item it : Db.get(c).all()) schedule(c, it);
        for (Task t : Db.get(c).tasks()) scheduleTask(c, t);
        scheduleDigest(c);
    }

    public static void cancelAll(Context c) {
        for (Item it : Db.get(c).all()) cancel(c, it.id);
        for (Task t : Db.get(c).tasks()) cancelTask(c, t.id);
    }
}
