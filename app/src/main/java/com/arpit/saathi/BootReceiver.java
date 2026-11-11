package com.arpit.saathi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms are wiped on reboot and app update, so put them back. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Reminders.rescheduleAll(c);
        ProcessJob.kick(c, ProcessJob.JOB_SORT);
    }
}
