package com.arpit.saathi;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

public final class Notify {
    private Notify() {}

    /** itemId <= 0 opens the inbox instead of one item. */
    public static void post(Context c, int notifId, String channel, String title, String text, long itemId) {
        Intent open;
        if (itemId > 0) {
            open = new Intent(c, DetailActivity.class).putExtra("id", itemId);
        } else {
            open = new Intent(c, MainActivity.class);
        }
        open.setAction("open-" + notifId);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(notifId, n);
        } catch (Exception ignored) {
            // notifications switched off: nothing to do
        }
    }
}
