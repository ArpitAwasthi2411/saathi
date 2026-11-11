package com.arpit.saathi.assist;

import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.arpit.saathi.Capture;
import com.arpit.saathi.Ui;

/**
 * Runs when the user calls the assistant (long-press home or power). Android hands over the text on
 * screen and a screenshot; this saves both, shows a small confirmation and gets out of the way.
 */
public class CaptureSession extends VoiceInteractionSession {
    private static final int MAX_TEXT = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::finishCapture;
    private TextView status;

    private boolean waitingAssist, waitingShot, done;
    /** A capture that is decided but not written yet, so "keep separate" can still change it. */
    private boolean pending, merge;
    private String text = "", url = "", pkg = "";
    private Bitmap shot;

    public CaptureSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        Context c = getContext();
        FrameLayout root = new FrameLayout(c);
        root.setOnClickListener(v -> hide());

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Color.parseColor("#151925"), Ui.dp(c, 20)));
        int pad = Ui.dp(c, 18);
        card.setPadding(pad, pad, pad, pad);
        card.setElevation(Ui.dp(c, 12));
        card.setOnClickListener(v -> {
            if (pending && merge) {
                merge = false;
                say("Saved as a separate item ✓");
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(() -> hide(), 900);
            } else {
                hide();
            }
        });

        TextView name = new TextView(c);
        name.setText("SAATHI");
        name.setTextColor(Color.parseColor("#7AA2FF"));
        name.setTextSize(11);
        name.setLetterSpacing(0.12f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(name);

        status = new TextView(c);
        status.setText("Capturing this screen…");
        status.setTextColor(Color.parseColor("#ECEFF7"));
        status.setTextSize(16);
        status.setPadding(0, Ui.dp(c, 6), 0, 0);
        card.addView(status);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int m = Ui.dp(c, 16);
        lp.setMargins(m, m, m, Ui.dp(c, 40));
        root.addView(card, lp);
        return root;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        commit(); // called again before the last popup closed: don't lose that capture
        done = false;
        pending = false;
        merge = false;
        text = "";
        url = "";
        pkg = "";
        shot = null;
        waitingAssist = (showFlags & SHOW_WITH_ASSIST) != 0;
        waitingShot = (showFlags & SHOW_WITH_SCREENSHOT) != 0;
        say("Capturing this screen…");
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(timeout, 3000);
        if (!waitingAssist && !waitingShot) finishCapture();
    }

    @Override
    public void onHandleAssist(AssistState state) {
        if (state.getIndex() > 0) return; // split-screen: keep the first (focused) window only
        gotAssist(state.getAssistStructure(), state.getAssistContent());
    }

    /** Android 8 and 9 deliver screen content through this older callback. */
    @SuppressWarnings("deprecation")
    @Override
    public void onHandleAssist(Bundle data, AssistStructure structure, AssistContent content) {
        gotAssist(structure, content);
    }

    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        if (screenshot != null) {
            try {
                // copy now: the system may reuse or free its bitmap after this call
                shot = screenshot.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable t) {
                shot = null;
            }
        }
        waitingShot = false;
        if (!waitingAssist) finishCapture();
    }

    private void gotAssist(AssistStructure structure, AssistContent content) {
        try {
            if (content != null) {
                if (content.getWebUri() != null) {
                    url = content.getWebUri().toString();
                } else if (content.getIntent() != null && content.getIntent().getDataString() != null
                        && content.getIntent().getDataString().startsWith("http")) {
                    url = content.getIntent().getDataString();
                }
            }
            if (structure != null) {
                if (structure.getActivityComponent() != null) {
                    pkg = structure.getActivityComponent().getPackageName();
                }
                StringBuilder sb = new StringBuilder();
                String[] last = {""};
                for (int i = 0; i < structure.getWindowNodeCount(); i++) {
                    walk(structure.getWindowNodeAt(i).getRootViewNode(), sb, last, 0);
                }
                text = sb.toString().trim();
            }
        } catch (Throwable ignored) {
            // some apps hand over odd structures; a screenshot alone is still useful
        }
        waitingAssist = false;
        if (!waitingShot) finishCapture();
    }

    private void walk(AssistStructure.ViewNode n, StringBuilder sb, String[] last, int depth) {
        if (n == null || depth > 60 || sb.length() >= MAX_TEXT) return;
        if (n.getVisibility() != View.VISIBLE) return;
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t != null) {
            String s = t.toString().trim();
            if (s.length() > 1 && !s.equals(last[0])) {
                sb.append(s).append("\n");
                last[0] = s;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i), sb, last, depth + 1);
    }

    private void finishCapture() {
        if (done) return;
        done = true;
        handler.removeCallbacks(timeout);
        final Context app = getContext().getApplicationContext();
        if (app.getPackageName().equals(pkg)) {
            hide();
            return;
        }
        if (Capture.sensitive(pkg)) {
            say("Not saved. Saathi never captures banking, payment or password apps.");
            handler.postDelayed(() -> hide(), 2600);
            return;
        }
        if (text.isEmpty() && shot == null && url.isEmpty()) {
            say("Couldn't read this screen. In Saathi's settings, check that the assistant may use "
                    + "screen text and screenshots.");
            handler.postDelayed(() -> hide(), 3500);
            return;
        }
        pending = true;
        merge = Capture.canMerge(app, pkg);
        String from = Ui.appName(pkg);
        if (merge) {
            say("Added to your last capture ✓\nSame post, next part. Tap here if this is a different post.");
        } else {
            say("Saved" + (from.isEmpty() ? "" : " from " + from) + " ✓  Sorting it in the background.\n"
                    + "Long post? Scroll and capture again within 90 seconds to add to it.");
        }
        if (!merge) commit(); // a new item is written at once; a merge waits in case you tap "different post"
        handler.postDelayed(() -> hide(), merge ? 3000 : 2000);
    }

    /** Writes the capture. Runs once, when the popup goes away. */
    private void commit() {
        if (!pending) return;
        pending = false;
        final Context app = getContext().getApplicationContext();
        final String fText = text, fUrl = url, fPkg = pkg;
        final Bitmap fShot = shot;
        final boolean fMerge = merge;
        new Thread(() -> Capture.save(app, fText, fUrl, fPkg, fShot, fMerge), "saathi-save").start();
    }

    private void say(String s) {
        if (status != null) status.setText(s);
    }

    @Override
    public void onHide() {
        super.onHide();
        commit();
        handler.removeCallbacksAndMessages(null);
        shot = null;
    }
}
