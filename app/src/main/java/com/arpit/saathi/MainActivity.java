package com.arpit.saathi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.arpit.saathi.assist.SaathiInteractionService;

import java.util.ArrayList;
import java.util.List;

/** The inbox: everything captured, as cards, soonest deadline first. */
public class MainActivity extends Activity {
    private static final String[] FILTERS = {null, "internship", "govt", "algorithm", "tool", "note", "other"};
    private static final String[] FILTER_LABELS = {"All", "Opportunities", "Govt & exams", "Algorithms", "Tools",
            "Notes", "Other"};

    private final List<Item> items = new ArrayList<>();
    private final Runnable onChange = this::reload;
    private CardAdapter adapter;
    private int filter = 0;
    private TextView subtitle, banner, empty;
    private LinearLayout chips;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        subtitle = findViewById(R.id.subtitle);
        banner = findViewById(R.id.banner);
        empty = findViewById(R.id.empty);
        chips = findViewById(R.id.chips);
        ListView list = findViewById(R.id.list);
        adapter = new CardAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) ->
                startActivity(new Intent(this, DetailActivity.class).putExtra("id", items.get(pos).id)));

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnAdd).setOnClickListener(v -> addDialog());
        findViewById(R.id.btnTasks).setOnClickListener(v -> startActivity(new Intent(this, TasksActivity.class)));
        findViewById(R.id.btnAsk).setOnClickListener(v -> startActivity(new Intent(this, AskActivity.class)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            final Item it = items.get(pos);
            new AlertDialog.Builder(this)
                    .setMessage("Delete \"" + (it.title.isEmpty() ? "this item" : it.title) + "\"?")
                    .setPositiveButton("Delete", (d, w) -> Capture.deleteItem(this, it))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        TextView brand = findViewById(R.id.brand);
        brand.getPaint().setShader(new android.graphics.LinearGradient(0, 0,
                brand.getPaint().measureText("Saathi"), 0, getColor(R.color.accent), getColor(R.color.accent2),
                android.graphics.Shader.TileMode.CLAMP));
        banner.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        buildChips();
        Reminders.scheduleDigest(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Db.addListener(onChange);
        reload();
        // pick up anything that was waiting for a key or a network
        ProcessJob.kick(this, ProcessJob.JOB_SORT);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Db.removeListener(onChange);
    }

    static boolean isAssistant(Activity a) {
        try {
            return VoiceInteractionService.isActiveService(a, new ComponentName(a, SaathiInteractionService.class));
        } catch (Throwable t) {
            return false;
        }
    }

    private void buildChips() {
        chips.removeAllViews();
        for (int i = 0; i < FILTERS.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(FILTER_LABELS[i]);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
            boolean on = i == filter;
            t.setTextColor(getColor(on ? R.color.onAccent : R.color.sub));
            t.setBackground(Ui.round(getColor(on ? R.color.accent : R.color.card), Ui.dp(this, 20)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 8));
            t.setOnClickListener(v -> {
                filter = idx;
                buildChips();
                reload();
            });
            chips.addView(t, lp);
        }
    }

    private void reload() {
        items.clear();
        items.addAll(Db.get(this).list(FILTERS[filter]));
        adapter.notifyDataSetChanged();

        int open = 0, dueSoon = 0;
        for (Item it : Db.get(this).all()) {
            if ("done".equals(it.status)) continue;
            open++;
            long left = Ui.daysLeft(it.deadline);
            if (left != Ui.NO_DATE && left >= 0 && left <= 7) dueSoon++;
        }
        subtitle.setText(open + " open" + (dueSoon > 0 ? "  ·  " + dueSoon + " due this week" : ""));
        int openTasks = 0;
        for (Task t : Db.get(this).tasks()) if (!t.done) openTasks++;
        ((TextView) findViewById(R.id.btnTasks)).setText(openTasks > 0 ? "Tasks · " + openTasks : "Tasks");

        String msg = null;
        if (!Llm.any(this)) {
            msg = "Add your free Gemini key in Settings so Saathi can sort and research what you save. Tap here.";
        } else if (!isAssistant(this)) {
            msg = "Make Saathi your digital assistant to capture any screen with a long-press. Tap here. "
                    + "(Sharing to Saathi already works.)";
        }
        banner.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) banner.setText(msg);

        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (items.isEmpty()) {
            empty.setText(filter == 0
                    ? "Nothing saved yet.\n\nOn a post you want to keep, call the assistant or use Share → Saathi."
                    + "\n\nHold a card to delete it."
                    : "Nothing in this group yet.");
        }
    }

    private void addDialog() {
        final EditText in = new EditText(this);
        in.setHint("Paste a link or write a note");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(3);
        in.setGravity(Gravity.TOP);
        FrameLayout wrap = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(in);
        new AlertDialog.Builder(this)
                .setTitle("Add to Saathi")
                .setView(wrap)
                .setPositiveButton("Save & research", (d, w) -> {
                    final String s = in.getText().toString().trim();
                    if (!s.isEmpty()) {
                        new Thread(() -> Capture.save(getApplicationContext(), s, null, "manual", null, false))
                                .start();
                    }
                })
                .setNeutralButton("Just remember it", (d, w) -> {
                    String s = in.getText().toString().trim();
                    if (!s.isEmpty()) Db.get(this).insertNote(s);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private class CardAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_card, parent, false);
            Item it = items.get(pos);
            TextView title = v.findViewById(R.id.title), summary = v.findViewById(R.id.summary),
                    due = v.findViewById(R.id.due), fit = v.findViewById(R.id.fit),
                    state = v.findViewById(R.id.state);
            Ui.pill((TextView) v.findViewById(R.id.pill), it.type);

            if (it.state == 0) {
                title.setText(!Llm.any(MainActivity.this) ? "Waiting for an API key" : "Sorting…");
                summary.setText(preview(it));
            } else if (it.state == -1) {
                title.setText("Couldn't sort this one");
                summary.setText("Tap to see why and try again.");
            } else {
                title.setText(it.title.isEmpty() ? "Untitled" : it.title);
                summary.setText(it.summary);
            }

            due.setText(Ui.dueLabel(it.deadline));
            due.setTextColor(Ui.dueColor(MainActivity.this, it.deadline));
            fit.setText(it.fit >= 0 ? "Fit " + it.fit + "/100" : "");

            String st;
            if ("done".equals(it.status)) st = "Done";
            else if ("acting".equals(it.status)) st = "Working on it";
            else if (it.state == 1) st = "Researching…";
            else if (it.state == -2) st = "Research failed";
            else st = "";
            state.setText(st);
            v.setAlpha("done".equals(it.status) ? 0.5f : 1f);
            return v;
        }

        private String preview(Item it) {
            if (!it.raw.isEmpty()) {
                String s = it.raw.replace('\n', ' ');
                return s.length() > 140 ? s.substring(0, 140) + "…" : s;
            }
            String from = Ui.appName(it.source);
            int n = it.images().size();
            return (n > 1 ? n + " screenshots" : "Screenshot") + (from.isEmpty() ? "" : " from " + from);
        }
    }
}
