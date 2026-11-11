package com.arpit.saathi;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** To-do list with reminders. Tap to tick off, hold to delete. */
public class TasksActivity extends Activity {
    private final List<Task> tasks = new ArrayList<>();
    private final Runnable onChange = this::reload;
    private TaskAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_tasks);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAdd).setOnClickListener(v -> TaskDialog.show(this, "", 0, 0));
        ListView list = findViewById(R.id.list);
        adapter = new TaskAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Task t = tasks.get(pos);
            Db.get(this).setTaskDone(t.id, !t.done);
            Reminders.scheduleTask(this, Db.get(this).task(t.id));
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            final Task t = tasks.get(pos);
            new AlertDialog.Builder(this)
                    .setMessage("Delete this task?")
                    .setPositiveButton("Delete", (d, w) -> {
                        Reminders.cancelTask(this, t.id);
                        Db.get(this).deleteTask(t.id);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Db.addListener(onChange);
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Db.removeListener(onChange);
    }

    private void reload() {
        tasks.clear();
        tasks.addAll(Db.get(this).tasks());
        adapter.notifyDataSetChanged();
        findViewById(R.id.empty).setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class TaskAdapter extends BaseAdapter {
        @Override public int getCount() { return tasks.size(); }
        @Override public Object getItem(int i) { return tasks.get(i); }
        @Override public long getItemId(int i) { return tasks.get(i).id; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(TasksActivity.this).inflate(R.layout.item_task, parent, false);
            Task t = tasks.get(pos);
            TextView check = v.findViewById(R.id.check), title = v.findViewById(R.id.title),
                    due = v.findViewById(R.id.due);
            int accent = getColor(R.color.accent);
            check.setText(t.done ? "✓" : "");
            check.setTextColor(getColor(R.color.onAccent));
            android.graphics.drawable.GradientDrawable ring = Ui.round(t.done ? accent : 0, Ui.dp(TasksActivity.this, 13));
            ring.setStroke(Ui.dp(TasksActivity.this, 2), t.done ? accent : getColor(R.color.grey));
            check.setBackground(ring);

            title.setText(t.title);
            title.setPaintFlags(t.done ? title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                    : title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            title.setTextColor(getColor(t.done ? R.color.sub : R.color.text));

            boolean late = !t.done && t.due > 0 && t.due < System.currentTimeMillis();
            due.setVisibility(t.due > 0 ? View.VISIBLE : View.GONE);
            due.setText((late ? "Overdue · " : "") + Ui.when(t.due));
            due.setTextColor(getColor(late ? R.color.red : R.color.sub));
            v.setAlpha(t.done ? 0.55f : 1f);
            return v;
        }
    }
}
