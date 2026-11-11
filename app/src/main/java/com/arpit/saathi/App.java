package com.arpit.saathi;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;

public class App extends Application {
    public static final String CH_DEADLINE = "deadlines";
    public static final String CH_UPDATE = "updates";
    /** How long Saathi may sit in the background before the app lock asks again. */
    private static final long RELOCK_AFTER_MS = 30_000;

    static volatile boolean unlocked = false;
    private int started = 0;
    private long backgroundAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_DEADLINE, "Deadlines and tasks",
                NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_UPDATE, "Research and digest",
                NotificationManager.IMPORTANCE_DEFAULT));

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity a) {
                if (a instanceof ShareActivity) return;
                if (started == 0 && System.currentTimeMillis() - backgroundAt > RELOCK_AFTER_MS) unlocked = false;
                started++;
            }

            @Override
            public void onActivityStopped(Activity a) {
                if (a instanceof ShareActivity) return;
                started = Math.max(0, started - 1);
                if (started == 0) backgroundAt = System.currentTimeMillis();
            }

            @Override
            public void onActivityResumed(Activity a) {
                if (a instanceof LockActivity || a instanceof ShareActivity) return;
                if (Prefs.lock(a) && !unlocked) a.startActivity(new Intent(a, LockActivity.class));
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
