package com.arpit.saathi.gate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.arpit.saathi.R;
import com.arpit.saathi.Ui;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Small view builders shared by the GATE screens, so they all look like the rest of Saathi. */
final class Views {
    private Views() {}

    static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(Ui.dp(c, 2), 1f);
        return t;
    }

    static TextView title(Context c, String s, float sp) {
        TextView t = text(c, s, sp, c.getColor(R.color.text));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.US), 12, c.getColor(R.color.sub));
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(c, 22), 0, Ui.dp(c, 8));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = Ui.dp(c, 16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = wide();
        lp.bottomMargin = Ui.dp(c, 10);
        card.setLayoutParams(lp);
        return card;
    }

    static TextView button(Context c, String s, boolean primary) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(c.getColor(primary ? R.color.onAccent : R.color.text));
        t.setBackgroundResource(primary ? R.drawable.bg_btn : R.drawable.bg_btn_ghost);
        t.setMinHeight(Ui.dp(c, 48));
        t.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        return t;
    }

    static TextView chip(Context c, String s, boolean on) {
        TextView t = text(c, s, 13, c.getColor(on ? R.color.onAccent : R.color.sub));
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 14), Ui.dp(c, 7));
        t.setBackground(Ui.round(c.getColor(on ? R.color.accent : R.color.card), Ui.dp(c, 20)));
        return t;
    }

    /** Back arrow, a title and optional icon buttons, matching the other screens. */
    static LinearLayout topBar(Activity a, String title, String sub) {
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        ImageButton back = icon(a, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> a.finish());
        bar.addView(back);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, 6), 0, 0, 0);
        TextView t = title(a, title, 19);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTag("title");
        col.addView(t);
        TextView s = text(a, sub, 12, a.getColor(R.color.sub));
        s.setTag("sub");
        s.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        col.addView(s);
        bar.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }

    static ImageButton icon(Activity a, int res, String desc) {
        ImageButton b = new ImageButton(a);
        b.setImageResource(res);
        b.setColorFilter(a.getColor(R.color.text));
        b.setBackgroundResource(android.R.color.transparent);
        b.setContentDescription(desc);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        return b;
    }

    static int resultColor(Context c, String result) {
        switch (result) {
            case "correct": return c.getColor(R.color.green);
            case "wrong": return c.getColor(R.color.red);
            case "partial": return c.getColor(R.color.amber);
            default: return c.getColor(R.color.grey);
        }
    }

    static int faint(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // ---------------------------------------------------------------- the study page (maths rendering)

    interface Links {
        void open(String saathiUrl);
    }

    /** A WebView that renders text and maths with the bundled KaTeX. It can reach no network and no files. */
    static final class Study {
        final WebView web;
        private boolean ready;
        private String pending;

        Study(Activity a, Links links) {
            web = new WebView(a);
            web.setBackgroundColor(a.getColor(R.color.bg));
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setBlockNetworkLoads(true);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setDomStorageEnabled(false);
            s.setGeolocationEnabled(false);
            s.setSupportZoom(false);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    String url = req.getUrl().toString();
                    if (url.startsWith("saathi://")) {
                        if (links != null) links.open(url);
                    } else if (url.startsWith("https://") || url.startsWith("http://")) {
                        try {
                            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    ready = true;
                    if (pending != null) {
                        web.evaluateJavascript("render(" + pending + ")", null);
                        pending = null;
                    }
                }
            });
            web.loadUrl("file:///android_asset/study.html");
        }

        void render(JSONObject data) {
            String js = data.toString();
            if (ready) web.evaluateJavascript("render(" + js + ")", null);
            else pending = js;
        }
    }

    // ---------------------------------------------------------------- score trend

    /** Mock percentages over time, oldest on the left. */
    static final class Trend extends View {
        private final List<Double> pts;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG);

        Trend(Context c, List<Double> oldestFirst) {
            super(c);
            pts = oldestFirst;
            line.setColor(c.getColor(R.color.accent));
            line.setStrokeWidth(Ui.dp(c, 2.5f));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            grid.setColor(c.getColor(R.color.stroke));
            grid.setStrokeWidth(Ui.dp(c, 1));
            dot.setColor(c.getColor(R.color.accent2));
            label.setColor(c.getColor(R.color.sub));
            label.setTextSize(Ui.dp(c, 10));
            setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 120)));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float left = Ui.dp(getContext(), 30), right = getWidth() - Ui.dp(getContext(), 8);
            float top = Ui.dp(getContext(), 8), bottom = getHeight() - Ui.dp(getContext(), 8);
            for (int g = 0; g <= 100; g += 25) {
                float y = bottom - (bottom - top) * g / 100f;
                cv.drawLine(left, y, right, y, grid);
                cv.drawText(g + "%", 0, y + Ui.dp(getContext(), 3), label);
            }
            if (pts.isEmpty()) return;
            Path path = new Path();
            for (int i = 0; i < pts.size(); i++) {
                float x = pts.size() == 1 ? (left + right) / 2 : left + (right - left) * i / (pts.size() - 1f);
                float y = bottom - (bottom - top) * (float) Math.max(0, Math.min(100, pts.get(i))) / 100f;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            cv.drawPath(path, line);
            for (int i = 0; i < pts.size(); i++) {
                float x = pts.size() == 1 ? (left + right) / 2 : left + (right - left) * i / (pts.size() - 1f);
                float y = bottom - (bottom - top) * (float) Math.max(0, Math.min(100, pts.get(i))) / 100f;
                cv.drawCircle(x, y, Ui.dp(getContext(), 3.5f), dot);
            }
        }
    }
}
