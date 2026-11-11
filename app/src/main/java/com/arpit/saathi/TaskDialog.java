package com.arpit.saathi;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

/** "Add a task" popup: a title and an optional reminder time. */
final class TaskDialog {
    private TaskDialog() {}

    static void show(final Activity a, String title, long due, final long itemId) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);

        final EditText name = new EditText(a);
        name.setHint("What needs doing?");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        name.setText(title);
        box.addView(name);

        final long[] when = {due};
        final TextView pick = new TextView(a);
        pick.setTextSize(15);
        pick.setTextColor(a.getColor(R.color.accent));
        pick.setPadding(Ui.dp(a, 4), Ui.dp(a, 14), 0, Ui.dp(a, 10));
        pick.setText(due > 0 ? "Remind me: " + Ui.when(due) : "Set a reminder time");
        pick.setOnClickListener(v -> {
            final Calendar cal = Calendar.getInstance();
            if (when[0] > 0) {
                cal.setTimeInMillis(when[0]);
            } else {
                cal.add(Calendar.HOUR_OF_DAY, 1);
                cal.set(Calendar.MINUTE, 0);
            }
            new DatePickerDialog(a, (dv, y, m, d) ->
                    new TimePickerDialog(a, (tv, h, min) -> {
                        cal.set(y, m, d, h, min, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        when[0] = cal.getTimeInMillis();
                        pick.setText("Remind me: " + Ui.when(when[0]));
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show(),
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });
        box.addView(pick);

        new AlertDialog.Builder(a)
                .setTitle("New task")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String t = name.getText().toString().trim();
                    if (t.isEmpty()) return;
                    long id = Db.get(a).addTask(t, when[0], itemId);
                    Reminders.scheduleTask(a, Db.get(a).task(id));
                    Toast.makeText(a, when[0] > 0 ? "Task added. Reminder: " + Ui.when(when[0]) : "Task added",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
