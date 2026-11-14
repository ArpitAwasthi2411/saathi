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
    private static final String[] BRAINS = {"gemini", "custom", "groq", "openrouter"};
    private static final String[] BRAIN_LABELS = {"Gemini", "The other provider below", "Groq", "OpenRouter"};
    private static final String[][] PRESETS = {
            {"NVIDIA (free: Kimi, DeepSeek, GLM)", "https://integrate.api.nvidia.com/v1", "https://build.nvidia.com"},
            {"Moonshot (Kimi, paid)", "https://api.moonshot.ai/v1", "https://platform.moonshot.ai"},
            {"DeepSeek (paid)", "https://api.deepseek.com/v1", "https://platform.deepseek.com"},
            {"Z.ai (GLM, free Flash models)", "https://api.z.ai/api/paas/v4", "https://z.ai"},
            {"Cerebras", "https://api.cerebras.ai/v1", "https://cloud.cerebras.ai"},
    };

    private EditText key, keyGroq, keyOpenRouter, keyTavily, keyCustom, customBase, modelCustom, modelSort,
            modelResearch, modelGroq, modelOpenRouter, profile, digestHour;
    private TextView brainView;
    private String brain = "gemini";
    private CheckBox wifiOnly, lock;
    private TextView assistStatus, testResult;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        key = findViewById(R.id.key);
        keyGroq = findViewById(R.id.keyGroq);
        keyOpenRouter = findViewById(R.id.keyOpenRouter);
        keyTavily = findViewById(R.id.keyTavily);
        keyCustom = findViewById(R.id.keyCustom);
        customBase = findViewById(R.id.customBase);
        modelCustom = findViewById(R.id.modelCustom);
        brainView = findViewById(R.id.brain);
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
        keyTavily.setText(Prefs.tavilyKey(this));
        keyCustom.setText(Prefs.customKey(this));
        customBase.setText(Prefs.sp(this).getString("customBase", ""));
        modelCustom.setText(Prefs.modelCustom(this));
        brain = Prefs.brain(this);
        showBrain();
        brainView.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(BRAIN_LABELS, (d, which) -> {
                    brain = BRAINS[which];
                    showBrain();
                })
                .show());
        findViewById(R.id.btnGetSearch).setOnClickListener(v -> open("https://app.tavily.com"));
        findViewById(R.id.btnPreset).setOnClickListener(v -> {
            CharSequence[] names = new CharSequence[PRESETS.length];
            for (int i = 0; i < PRESETS.length; i++) names[i] = PRESETS[i][0];
            new AlertDialog.Builder(this)
                    .setTitle("Fill in the address for")
                    .setItems(names, (d, which) -> {
                        customBase.setText(PRESETS[which][1]);
                        show("Address filled in. Get a key at " + PRESETS[which][2] + ", paste it, then tap "
                                + "Test keys: it lists the model names you can use.", true);
                    })
                    .show();
        });
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

    private void showBrain() {
        for (int i = 0; i < BRAINS.length; i++) if (BRAINS[i].equals(brain)) brainView.setText(BRAIN_LABELS[i]);
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
        Prefs.setSecret(this, "tavily", s(keyTavily));
        Prefs.setSecret(this, "custom", s(keyCustom));
        Prefs.sp(this).edit()
                .putString("modelSort", s(modelSort))
                .putString("modelResearch", s(modelResearch))
                .putString("customBase", s(customBase))
                .putString("modelCustom", s(modelCustom))
                .putString("brain", brain)
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
        ProcessJob.kick(this, ProcessJob.JOB_TEAM);
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
        final String k = s(key), kg = s(keyGroq), ko = s(keyOpenRouter), kt = s(keyTavily), kc = s(keyCustom);
        final String sort = s(modelSort), think = s(modelResearch);
        final String mg = s(modelGroq), mo = s(modelOpenRouter), mc = s(modelCustom);
        String cb = s(customBase);
        while (cb.endsWith("/")) cb = cb.substring(0, cb.length() - 1);
        final String base = cb;
        if (k.isEmpty() && kg.isEmpty() && ko.isEmpty() && kt.isEmpty() && kc.isEmpty()) {
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
                    String a = firstUsable(models, sort), r = firstUsable(models, think);
                    if (!a.isEmpty() && !r.isEmpty()) {
                        sb.append("Gemini: works. Reading captures with ").append(a).append(", thinking with ")
                                .append(r).append(".\n");
                    } else {
                        ok = false;
                        sb.append("Gemini: key works, but none of the listed models exist for it. Flash models ")
                                .append("you can use: ").append(some(models, "flash")).append("\n");
                    }
                } catch (Exception e) {
                    ok = false;
                    sb.append("Gemini: ").append(e.getMessage()).append("\n");
                }
            }
            if (!kt.isEmpty()) {
                try {
                    Gemini.http("POST", "https://api.tavily.com/search", "Authorization", "Bearer " + kt,
                            "{\"query\":\"test\",\"max_results\":1}");
                    sb.append("Web search: works.\n");
                } catch (Exception e) {
                    ok = false;
                    sb.append("Web search: ").append(e.getMessage()).append("\n");
                }
            } else {
                sb.append("Web search: no key yet, so research and the team cannot look things up.\n");
            }
            if (!kc.isEmpty()) {
                if (!base.startsWith("https://")) {
                    ok = false;
                    sb.append("Other provider: the address must start with https://\n");
                } else {
                    ok &= check(sb, "Other provider", base, kc, mc);
                }
            }
            ok &= check(sb, "Groq", Llm.GROQ, kg, mg);
            ok &= check(sb, "OpenRouter", Llm.OPENROUTER, ko, mo);
            final String msg = sb.toString().trim();
            final boolean fOk = ok;
            runOnUiThread(() -> show(msg, fOk));
        }).start();
    }

    /** The first model in a comma-separated list that the key can actually call. */
    private static String firstUsable(List<String> available, String csv) {
        for (String m : csv.split(",")) {
            m = strip(m.trim());
            if (!m.isEmpty() && available.contains(m)) return m;
        }
        return "";
    }

    private static boolean check(StringBuilder sb, String name, String base, String key, String model) {
        if (key.isEmpty()) return true;
        try {
            List<String> models = Llm.models(base, key);
            if (models.contains(model)) {
                sb.append(name).append(": works.\n");
                return true;
            }
            if (model.isEmpty()) {
                sb.append(name).append(": key works. Now type one of these as the model name: ")
                        .append(some(models, "")).append("\n");
                return false;
            }
            sb.append(name).append(": key works, but there is no model called ").append(model)
                    .append(". Some you can use: ").append(some(models, "")).append("\n");
        } catch (Exception e) {
            sb.append(name).append(": ").append(e.getMessage()).append("\n");
        }
        return false;
    }

    /** A readable sample of model names: the strong, well-known families first. */
    private static String some(List<String> models, String containing) {
        String[] liked = {"kimi", "deepseek", "glm", "qwen", "gpt-oss", "llama"};
        List<String> picked = new java.util.ArrayList<>();
        for (String want : liked) {
            for (String m : models) {
                if (m.toLowerCase().contains(want) && m.contains(containing) && !picked.contains(m)
                        && picked.size() < 14) {
                    picked.add(m);
                }
            }
        }
        for (String m : models) {
            if (m.contains(containing) && !picked.contains(m) && picked.size() < 14) picked.add(m);
        }
        return android.text.TextUtils.join(", ", picked);
    }

    private static String strip(String m) {
        return m.startsWith("models/") ? m.substring(7) : m;
    }

    private void show(String msg, boolean ok) {
        testResult.setTextColor(getColor(ok ? R.color.green : R.color.amber));
        testResult.setText(msg);
    }
}
