package com.arpit.saathi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** You are the founder: hand the team a mission, watch it move, read the reports. */
public class TeamActivity extends Activity {
    private static final int REQ_PDF = 21;

    private final List<Team.Mission> missions = new ArrayList<>();
    private List<Team.Step> steps = new ArrayList<>();
    private final Runnable onChange = this::reload;
    private MissionAdapter adapter;
    private EditText input;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_team);
        input = findViewById(R.id.input);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSend).setOnClickListener(v -> assign());
        findViewById(R.id.btnReport).setOnClickListener(v -> start("report", "Activity report"));
        findViewById(R.id.btnPdfAll).setOnClickListener(v -> {
            if (missions.isEmpty()) Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show();
            else Pdf.askWhere(this, "team log", REQ_PDF);
        });
        ListView list = findViewById(R.id.list);
        adapter = new MissionAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) ->
                startActivity(new Intent(this, MissionActivity.class).putExtra("id", missions.get(pos).id)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            final Team.Mission m = missions.get(pos);
            new AlertDialog.Builder(this)
                    .setMessage("Delete this mission and its report?")
                    .setPositiveButton("Delete", (d, w) -> Db.get(this).deleteMission(m.id))
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
        ProcessJob.kick(this, ProcessJob.JOB_TEAM);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Db.removeListener(onChange);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PDF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Pdf.save(this, data.getData(), () -> Pdf.ofAllMissions(getApplicationContext()));
        }
    }

    private void assign() {
        String goal = input.getText().toString().trim();
        if (goal.isEmpty()) return;
        input.setText("");
        start("mission", goal);
    }

    private void start(String kind, String goal) {
        if (!Llm.any(this)) {
            Toast.makeText(this, "Add an API key in Settings first", Toast.LENGTH_LONG).show();
            return;
        }
        Db.get(this).addMission(kind, goal);
        ProcessJob.kick(this, ProcessJob.JOB_TEAM);
        Toast.makeText(this, Search.available(this) || "report".equals(kind)
                ? "Assigned. You'll get a notification when the report is ready."
                : "Assigned, but there is no web search key yet (Settings, section 3), so research may fail.",
                Toast.LENGTH_LONG).show();
    }

    private void reload() {
        missions.clear();
        missions.addAll(Db.get(this).missions());
        steps = Db.get(this).steps(0);
        adapter.notifyDataSetChanged();
        findViewById(R.id.empty).setVisibility(missions.isEmpty() ? View.VISIBLE : View.GONE);

        int searches = 0, results = 0;
        for (Team.Step s : steps) {
            searches += s.searches;
            results += s.found;
        }
        ((TextView) findViewById(R.id.stats)).setText(missions.size() + " missions  ·  " + searches
                + " web searches  ·  " + results + " sourced results");

        LinearLayout roster = findViewById(R.id.roster);
        roster.removeAllViews();
        addChip(roster, "Chief", "leads", getColor(R.color.accent));
        for (Team.Agent a : Team.AGENTS) {
            int n = 0;
            boolean busy = false;
            for (Team.Step s : steps) {
                if (!a.key.equals(s.agent)) continue;
                if ("done".equals(s.status)) n++;
                if ("run".equals(s.status)) busy = true;
            }
            addChip(roster, a.name, busy ? "working…" : n + " done", getColor(a.color));
        }
    }

    private void addChip(LinearLayout parent, String name, String sub, int color) {
        TextView t = new TextView(this);
        t.setText(name + "\n" + sub);
        t.setTextSize(12);
        t.setTextColor(color);
        t.setLineSpacing(Ui.dp(this, 1), 1f);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        t.setBackground(Ui.round(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color)),
                Ui.dp(this, 12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        parent.addView(t, lp);
    }

    static String statusLabel(String status) {
        switch (status) {
            case "queued": return "QUEUED";
            case "running": return "WORKING";
            case "done": return "DONE";
            default: return "FAILED";
        }
    }

    static int statusColor(android.content.Context c, String status) {
        switch (status) {
            case "queued": return c.getColor(R.color.grey);
            case "running": return c.getColor(R.color.amber);
            case "done": return c.getColor(R.color.green);
            default: return c.getColor(R.color.red);
        }
    }

    static void statusPill(TextView tv, String status) {
        int col = statusColor(tv.getContext(), status);
        tv.setText(statusLabel(status));
        tv.setTextColor(col);
        tv.setBackground(Ui.round(Color.argb(40, Color.red(col), Color.green(col), Color.blue(col)),
                Ui.dp(tv.getContext(), 20)));
    }

    private class MissionAdapter extends BaseAdapter {
        @Override public int getCount() { return missions.size(); }
        @Override public Object getItem(int i) { return missions.get(i); }
        @Override public long getItemId(int i) { return missions.get(i).id; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(TeamActivity.this).inflate(R.layout.item_card, parent, false);
            Team.Mission m = missions.get(pos);
            statusPill((TextView) v.findViewById(R.id.pill), m.status);
            ((TextView) v.findViewById(R.id.due)).setText("");
            ((TextView) v.findViewById(R.id.title)).setText(m.title.isEmpty() ? m.goal : m.title);

            int total = 0, done = 0, searches = 0, results = 0;
            String now = "";
            for (Team.Step s : steps) {
                if (s.missionId != m.id) continue;
                total++;
                searches += s.searches;
                results += s.found;
                if ("done".equals(s.status)) done++;
                if ("run".equals(s.status)) now = Team.agentName(s.agent) + " is working";
            }
            String line;
            if ("failed".equals(m.status)) line = m.error;
            else if ("done".equals(m.status)) line = m.report.split("\n")[0];
            else if (!now.isEmpty()) line = now + "…";
            else if (total == 0) line = "running".equals(m.status) ? "Chief is planning…" : "Waiting to start";
            else line = "Chief is writing the report…";
            ((TextView) v.findViewById(R.id.summary)).setText(line);
            ((TextView) v.findViewById(R.id.fit)).setText("report".equals(m.kind) ? "Chief"
                    : "running".equals(m.status) ? done + "/" + total + " steps done"
                    : results + " results  ·  " + searches + " searches");
            ((TextView) v.findViewById(R.id.state)).setText(
                    new SimpleDateFormat("d MMM, h:mm a", Locale.US).format(new Date(m.created)));
            return v;
        }
    }
}
