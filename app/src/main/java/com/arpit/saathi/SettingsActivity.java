package com.arpit.saathi;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class SettingsActivity extends Activity {
    private EditText key, modelSort, modelResearch, profile, digestHour;
    private CheckBox wifiOnly;
    private TextView assistStatus, testResult;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        key = findViewById(R.id.key);
        modelSort = findViewById(R.id.modelSort);
        modelResearch = findViewById(R.id.modelResearch);
        profile = findViewById(R.id.profile);
        digestHour = findViewById(R.id.digestHour);
        wifiOnly = findViewById(R.id.wifiOnly);
        assistStatus = findViewById(R.id.assistStatus);
        testResult = findViewById(R.id.testResult);

        key.setText(Prefs.key(this));
        modelSort.setText(Prefs.modelSort(this));
        modelResearch.setText(Prefs.modelResearch(this));
        profile.setText(Prefs.profile(this));
        digestHour.setText(String.valueOf(Prefs.digestHour(this)));
        wifiOnly.setChecked(Prefs.wifiOnly(this));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAssist).setOnClickListener(v -> openAssistSettings());
        findViewById(R.id.btnGetKey).setOnClickListener(v -> open("https://aistudio.google.com/apikey"));
        findViewById(R.id.btnTest).setOnClickListener(v -> test());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = MainActivity.isAssistant(this);
        assistStatus.setTextColor(getColor(on ? R.color.green : R.color.sub));
        assistStatus.setText(on
                ? "Saathi is your digital assistant. Long-press home or power (or swipe up from a bottom "
                + "corner) on any screen to save it. Keep \"Use text from screen\" and \"Use screenshot\" on."
                : "Choose Saathi as the digital assistant app, then turn on \"Use text from screen\" and "
                + "\"Use screenshot\". After that, long-press home or power on any screen to save it.");
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        int hour = 20;
        try {
            hour = Math.max(0, Math.min(23, Integer.parseInt(digestHour.getText().toString().trim())));
        } catch (Exception ignored) {
        }
        Prefs.sp(this).edit()
                .putString("key", key.getText().toString().trim())
                .putString("modelSort", modelSort.getText().toString().trim())
                .putString("modelResearch", modelResearch.getText().toString().trim())
                .putString("profile", profile.getText().toString().trim())
                .putInt("digestHour", hour)
                .putBoolean("wifiOnly", wifiOnly.isChecked())
                .apply();
        Reminders.scheduleDigest(this);
        ProcessJob.kick(this, ProcessJob.JOB_SORT);
    }

    private void openAssistSettings() {
        String[] actions = {Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a));
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, "Open Settings → Apps → Default apps → Digital assistant app", Toast.LENGTH_LONG).show();
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private void test() {
        final String k = key.getText().toString().trim();
        final String sort = modelSort.getText().toString().trim();
        final String research = modelResearch.getText().toString().trim();
        if (k.isEmpty()) {
            show("Paste a key first.", false);
            return;
        }
        show("Checking…", true);
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                List<String> models = Gemini.listModels(k);
                StringBuilder sb = new StringBuilder("Key works. ");
                boolean a = sort.isEmpty() || models.contains(strip(sort));
                boolean r = research.isEmpty() || models.contains(strip(research));
                if (a && r) {
                    sb.append("Both models are available.");
                    ok = true;
                } else {
                    sb.append("But this key can't use: ").append(a ? "" : sort + " ").append(r ? "" : research)
                            .append("\nFlash models you can use: ");
                    int n = 0;
                    for (String m : models) {
                        if (m.contains("flash") && n++ < 10) sb.append(m).append(", ");
                    }
                }
                msg = sb.toString();
            } catch (Exception e) {
                msg = "That didn't work: " + e.getMessage();
            }
            final String fMsg = msg;
            final boolean fOk = ok;
            runOnUiThread(() -> show(fMsg, fOk));
        }).start();
    }

    private static String strip(String m) {
        return m.startsWith("models/") ? m.substring(7) : m;
    }

    private void show(String msg, boolean ok) {
        testResult.setTextColor(getColor(ok ? R.color.green : R.color.amber));
        testResult.setText(msg);
    }
}
