package com.arpit.saathi;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Ask questions about what you saved, or ask to be reminded of something. */
public class AskActivity extends Activity implements Voice.Listener {
    private final List<String[]> history = new ArrayList<>();
    private LinearLayout msgs;
    private ScrollView scroll;
    private EditText input;
    private boolean busy, voiceOn;
    private Voice voice;
    private TextView toggle, partial;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_ask);
        msgs = findViewById(R.id.msgs);
        scroll = findViewById(R.id.scroll);
        input = findViewById(R.id.input);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSend).setOnClickListener(v -> send());
        toggle = findViewById(R.id.voiceToggle);
        voiceOn = Prefs.sp(this).getBoolean("askVoice", false);
        showToggle();
        toggle.setOnClickListener(v -> {
            voiceOn = !voiceOn;
            Prefs.sp(this).edit().putBoolean("askVoice", voiceOn).apply();
            if (!voiceOn && voice != null) voice.stopSpeaking();
            showToggle();
        });
        findViewById(R.id.btnMic).setOnClickListener(v -> {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 82);
                return;
            }
            if (!voiceOn) { // speaking to it means you want it to speak back
                voiceOn = true;
                Prefs.sp(this).edit().putBoolean("askVoice", true).apply();
                showToggle();
            }
            voice().listen();
        });
        bubble("Ask me about anything you've saved: \"which deadlines are closest?\", \"what was that "
                + "planning paper about?\". I can also add tasks: \"remind me to apply on Friday at 6 pm\".", false);
    }

    private Voice voice() {
        if (voice == null) voice = new Voice(this, this);
        return voice;
    }

    private void showToggle() {
        toggle.setText(voiceOn ? "Voice on" : "Voice off");
        toggle.setTextColor(getColor(voiceOn ? R.color.onAccent : R.color.sub));
        toggle.setBackgroundResource(voiceOn ? R.drawable.bg_btn : R.drawable.bg_btn_ghost);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 82 && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voice().listen();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (voice != null) voice.handleResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (voice != null) voice.stopListening();
    }

    @Override
    protected void onDestroy() {
        if (voice != null) voice.destroy();
        Recall.summarise(getApplicationContext(), "Ask chat", new ArrayList<>(history));
        super.onDestroy();
    }

    @Override public void onListening(boolean on) {
        findViewById(R.id.btnMic).setBackgroundResource(on ? R.drawable.bg_mic_live : R.drawable.bg_mic);
    }

    @Override public void onPartial(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (partial == null) partial = bubble(text, true);
        else partial.setText(text);
    }

    @Override public void onHeard(String text) {
        if (partial != null) {
            msgs.removeView(partial);
            partial = null;
        }
        input.setText(text);
        send();
    }

    @Override public void onSpeaking(boolean on) {}

    @Override public void onProblem(String message) {
        if (partial != null) {
            msgs.removeView(partial);
            partial = null;
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private TextView bubble(String text, boolean mine) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 12);
        t.setPadding(Ui.dp(this, 14), p, Ui.dp(this, 14), p);
        t.setTextColor(getColor(mine ? R.color.onAccent : R.color.text));
        t.setBackgroundResource(mine ? R.drawable.bg_bubble_me : R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = Ui.dp(this, 10);
        if (mine) lp.leftMargin = Ui.dp(this, 48);
        else lp.rightMargin = Ui.dp(this, 48);
        msgs.addView(t, lp);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        return t;
    }

    private void send() {
        final String q = input.getText().toString().trim();
        if (q.isEmpty() || busy) return;
        if (!Llm.any(this)) {
            bubble("Add an API key in Settings first.", false);
            return;
        }
        busy = true;
        input.setText("");
        bubble(q, true);
        final TextView answer = bubble("Thinking…", false);
        final List<String[]> past = new ArrayList<>(history);
        final boolean spoken = voiceOn;
        new Thread(() -> {
            String out;
            try {
                String prompt = Memory.prompt(getApplicationContext(), q, past)
                        + (spoken ? "\n" + com.arpit.saathi.gate.Tutor.SPOKEN + "\n" : "");
                out = Memory.applyTasks(getApplicationContext(), Llm.chat(getApplicationContext(), prompt));
            } catch (Exception e) {
                out = "That didn't work: " + (e.getMessage() == null ? "no connection?" : e.getMessage());
            }
            final String shown = out;
            runOnUiThread(() -> {
                busy = false;
                history.add(new String[]{q, shown});
                answer.setText(shown);
                scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
                if (voiceOn) voice().speak(shown);
            });
        }, "saathi-ask").start();
    }
}
