package com.arpit.saathi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if ("deadline".equals(action)) {
            Item it = Db.get(c).item(intent.getLongExtra("id", -1));
            if (it == null || "done".equals(it.status)) return;
            long left = Ui.daysLeft(it.deadline);
            if (left == Ui.NO_DATE || left < 0) return;
            String title = it.title.isEmpty() ? "Saved item" : it.title;
            Notify.post(c, (int) (1000 + it.id), App.CH_DEADLINE, Ui.dueLabel(it.deadline) + ": " + title,
                    it.action.isEmpty() ? "Tap to open it." : it.action, Notify.item(c, it.id));
        } else if ("task".equals(action)) {
            Task t = Db.get(c).task(intent.getLongExtra("id", -1));
            if (t == null || t.done) return;
            Notify.post(c, (int) (3000 + t.id), App.CH_DEADLINE, "Task: " + t.title,
                    "Due now. Tap to open your tasks.", Notify.screen(c, TasksActivity.class));
        } else if ("digest".equals(action)) {
            digest(c);
            Reminders.scheduleDigest(c);
        }
    }

    private void digest(Context c) {
        long now = System.currentTimeMillis(), dayAgo = now - 86400000L;
        int fresh = 0, due = 0, waiting = 0, tasks = 0;
        String next = "";
        long nextLeft = Long.MAX_VALUE;
        for (Item it : Db.get(c).all()) {
            if ("done".equals(it.status) || "note".equals(it.type)) continue;
            if (it.created >= dayAgo) fresh++;
            if ("new".equals(it.status)) waiting++;
            long left = Ui.daysLeft(it.deadline);
            if (left != Ui.NO_DATE && left >= 0 && left <= 7) {
                due++;
                if (left < nextLeft) {
                    nextLeft = left;
                    next = it.title;
                }
            }
        }
        for (Task t : Db.get(c).tasks()) {
            if (!t.done && t.due > 0 && t.due < now + 86400000L) tasks++;
        }
        if (fresh == 0 && due == 0 && waiting == 0 && tasks == 0) return;
        StringBuilder sb = new StringBuilder();
        if (fresh > 0) sb.append(fresh).append(" saved today. ");
        if (due > 0) {
            sb.append(due).append(due == 1 ? " deadline" : " deadlines").append(" this week");
            if (!next.isEmpty()) sb.append(" (next: ").append(next).append(")");
            sb.append(". ");
        }
        if (tasks > 0) sb.append(tasks).append(tasks == 1 ? " task" : " tasks").append(" due by tomorrow. ");
        if (waiting > 0) sb.append(waiting).append(" not looked at yet.");
        Notify.post(c, 500, App.CH_UPDATE, "Saathi digest", sb.toString().trim(),
                Notify.screen(c, MainActivity.class));
    }
}
