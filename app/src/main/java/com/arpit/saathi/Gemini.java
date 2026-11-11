package com.arpit.saathi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal client for the Gemini REST API. The key never leaves the phone except to Google. */
public final class Gemini {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";

    public static class Reply {
        public String text = "";
        /** {title, url} pairs from Google Search grounding. */
        public List<String[]> sources = new ArrayList<>();
    }

    private Gemini() {}

    public static Reply generate(String key, String model, JSONArray parts, boolean json, boolean search)
            throws Exception {
        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
        JSONObject cfg = new JSONObject().put("temperature", 0.2);
        if (json) cfg.put("responseMimeType", "application/json");
        body.put("generationConfig", cfg);
        if (search) {
            body.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
        }
        String res = http("POST", BASE + "models/" + model + ":generateContent", key, body.toString());

        Reply r = new Reply();
        JSONArray cands = new JSONObject(res).optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new IOException("Model returned no answer");
        JSONObject cand = cands.getJSONObject(0);
        JSONObject content = cand.optJSONObject("content");
        JSONArray ps = content == null ? null : content.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.getJSONObject(i);
                if (p.optBoolean("thought", false)) continue;
                sb.append(p.optString("text", ""));
            }
        }
        r.text = sb.toString().trim();
        if (r.text.isEmpty()) {
            throw new IOException("Empty answer (" + cand.optString("finishReason", "no reason given") + ")");
        }
        JSONObject gm = cand.optJSONObject("groundingMetadata");
        JSONArray chunks = gm == null ? null : gm.optJSONArray("groundingChunks");
        if (chunks != null) {
            for (int i = 0; i < chunks.length() && r.sources.size() < 8; i++) {
                JSONObject web = chunks.getJSONObject(i).optJSONObject("web");
                if (web == null) continue;
                String uri = web.optString("uri", "");
                if (!uri.isEmpty()) r.sources.add(new String[]{web.optString("title", uri), uri});
            }
        }
        return r;
    }

    /** Names of models this key can call with generateContent. */
    public static List<String> listModels(String key) throws Exception {
        String res = http("GET", BASE + "models?pageSize=200", key, null);
        List<String> out = new ArrayList<>();
        JSONArray models = new JSONObject(res).optJSONArray("models");
        if (models == null) return out;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            JSONArray methods = m.optJSONArray("supportedGenerationMethods");
            boolean ok = false;
            if (methods != null) {
                for (int j = 0; j < methods.length(); j++) {
                    if ("generateContent".equals(methods.optString(j))) ok = true;
                }
            }
            String name = m.optString("name", "");
            if (name.startsWith("models/")) name = name.substring(7);
            if (ok && !name.isEmpty()) out.add(name);
        }
        return out;
    }

    private static String http(String method, String url, String key, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(150000);
            c.setRequestProperty("x-goog-api-key", key);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String res = in == null ? "" : readAll(in, 4 * 1024 * 1024);
            if (code >= 400) {
                String msg = res;
                try {
                    msg = new JSONObject(res).getJSONObject("error").optString("message", res);
                } catch (Exception ignored) {
                }
                if (msg.length() > 300) msg = msg.substring(0, 300);
                throw new IOException("HTTP " + code + ": " + msg);
            }
            return res;
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in, int max) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
