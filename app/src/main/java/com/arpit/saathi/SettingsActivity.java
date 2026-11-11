package com.arpit.saathi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

public class SettingsActivity extends Activity {
    private EditText key, keyGroq, keyOpenRouter, modelSort, modelResearch, modelGroq, modelOpenRouter, profile,
            digestHour;
    private CheckBox wifiOnly, lock;
    private TextView assistStatus, testResult;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        key = findViewById(R.id.key);
        keyGroq = findViewById(R.id.keyGroq);
        keyOpenRouter = findViewById(R.id.keyOpenRouter);
        modelSort = findViewById(R.id.modelSort);
        modelResearch = findViewById(R.id.modelResearch);
        modelGroq = findViewById(R.id.modelGroq);
        modelOpenRouter = findViewById(R.id.modelOpenRouter);
        profile = findViewById(R.id.profile);
        digestHour = findViewById(R.id.digestHour);
        wifiOnly = findViewById(R.id.wifiOnly);
        lock = findViewById(R.id.lock);
        assistStatus = findViewById(R.id.assistStatus);
        testResult = findViewById(R.id.testResult);

        key.setText(Prefs.key(this));
        keyGroq.setText(Prefs.groqKey(this));
        keyOpenRouter.setText(Prefs.openRouterKey(this));
        modelSort.setText(Prefs.modelSort(this));
        modelResearch.setText(Prefs.modelResearch(this));
        modelGroq.setText(Prefs.modelGroq(this));
        modelOpenRouter.setText(Prefs.modelOpenRouter(this));
        profile.setText(Prefs.sp(this).getString("profile", ""));
        digestHour.setText(String.valueOf(Prefs.digestHour(this)));
        wifiOnly.setChecked(Prefs.wifiOnly(this));
        lock.setChecked(Prefs.lock(this));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAssist).setOnClickListener(v -> openAssistSettings());
        findViewById(R.id.btnGetKey).setOnClickListener(v -> open("https://aistudio.google.com/apikey"));
        findViewById(R.id.btnTest).setOnClickListener(v -> test());
        findViewById(R.id.btnErase).setOnClickListener(v -> confirmErase());
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

    private static String s(EditText e) {
        return e.getText().toString().trim();
    }

    private void save() {
        int hour = 20;
        try {
            hour = Math.max(0, Math.min(23, Integer.parseInt(s(digestHour))));
        } catch (Exception ignored) {
        }
        Prefs.setSecret(this, "key", s(key));
        Prefs.setSecret(this, "groq", s(keyGroq));
        Prefs.setSecret(this, "openrouter", s(keyOpenRouter));
        Prefs.sp(this).edit()
                .putString("modelSort", s(modelSort))
                .putString("modelResearch", s(modelResearch))
                .putString("modelGroq", s(modelGroq))
                .putString("modelOpenRouter", s(modelOpenRouter))
                .putString("profile", s(profile))
                .putInt("digestHour", hour)
                .putBoolean("wifiOnly", wifiOnly.isChecked())
                .putBoolean("lock", lock.isChecked())
                .apply();
        if (lock.isChecked()) App.unlocked = true; // you are already in; lock from the next time
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

    private void confirmErase() {
        new AlertDialog.Builder(this)
                .setTitle("Erase everything?")
                .setMessage("All saved items, screenshots, notes and tasks are deleted from this phone. "
                        + "Your keys and settings stay. This can't be undone.")
                .setPositiveButton("Erase", (d, w) -> {
                    Reminders.cancelAll(this);
                    Db.get(this).eraseAll();
                    File[] shots = new File(getFilesDir(), "shots").listFiles();
                    if (shots != null) for (File f : shots) f.delete();
                    Prefs.sp(this).edit().remove("lastCapId").remove("lastCapAt").remove("lastCapSrc").apply();
                    Toast.makeText(this, "Erased", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void test() {
        final String k = s(key), kg = s(keyGroq), ko = s(keyOpenRouter);
        final String sort = strip(s(modelSort)), research = strip(s(modelResearch));
        final String mg = s(modelGroq), mo = s(modelOpenRouter);
        if (k.isEmpty() && kg.isEmpty() && ko.isEmpty()) {
            show("Paste a key first.", false);
            return;
        }
        show("Checking…", true);
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            if (!k.isEmpty()) {
                try {
                    List<String> models = Gemini.listModels(k);
                    boolean a = sort.isEmpty() || models.contains(sort);
                    boolean r = research.isEmpty() || models.contains(research);
                    if (a && r) {
                        sb.append("Gemini: works.\n");
                    } else {
                        ok = false;
                        sb.append("Gemini: key works, but it can't use ").append(a ? "" : sort + " ")
                                .append(r ? "" : research).append(". Flash models you can use: ")
                                .append(some(models, "flash")).append("\n");
                    }
                } catch (Exception e) {
                    ok = false;
                    sb.append("Gemini: ").append(e.getMessage()).append("\n");
                }
            }
            ok &= check(sb, "Groq", Llm.GROQ, kg, mg);
            ok &= check(sb, "OpenRouter", Llm.OPENROUTER, ko, mo);
            final String msg = sb.toString().trim();
            final boolean fOk = ok;
            runOnUiThread(() -> show(msg, fOk));
        }).start();
    }

    private static boolean check(StringBuilder sb, String name, String base, String key, String model) {
        if (key.isEmpty()) return true;
        try {
            List<String> models = Llm.models(base, key);
            if (model.isEmpty() || models.contains(model)) {
                sb.append(name).append(": works.\n");
                return true;
            }
            sb.append(name).append(": key works, but there is no model called ").append(model)
                    .append(". Some you can use: ").append(some(models, "")).append("\n");
        } catch (Exception e) {
            sb.append(name).append(": ").append(e.getMessage()).append("\n");
        }
        return false;
    }

    private static String some(List<String> models, String containing) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String m : models) {
            if (m.contains(containing) && n++ < 8) sb.append(n > 1 ? ", " : "").append(m);
        }
        return sb.toString();
    }

    private static String strip(String m) {
        return m.startsWith("models/") ? m.substring(7) : m;
    }

    private void show(String msg, boolean ok) {
        testResult.setTextColor(getColor(ok ? R.color.green : R.color.amber));
        testResult.setText(msg);
    }
}
