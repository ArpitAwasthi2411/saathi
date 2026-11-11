package com.arpit.saathi;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class App extends Application {
    public static final String CH_DEADLINE = "deadlines";
    public static final String CH_UPDATE = "updates";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_DEADLINE, "Deadlines",
                NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_UPDATE, "Research and digest",
                NotificationManager.IMPORTANCE_DEFAULT));
    }
}
