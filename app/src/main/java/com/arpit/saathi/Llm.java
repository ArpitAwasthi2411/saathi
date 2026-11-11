package com.arpit.saathi;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Text-in, text-out across every provider the user has a key for. Gemini first; Groq and OpenRouter
 * (open-source models, OpenAI-style API) take over when it fails or is over its limit.
 */
public final class Llm {
    static final String GROQ = "https://api.groq.com/openai/v1";
    static final String OPENROUTER = "https://openrouter.ai/api/v1";

    private Llm() {}

    public static boolean any(Context c) {
        return !Prefs.key(c).isEmpty() || hasBackup(c);
    }

    public static boolean hasBackup(Context c) {
        return !Prefs.groqKey(c).isEmpty() || !Prefs.openRouterKey(c).isEmpty();
    }

    public static String chat(Context c, String prompt) throws Exception {
        String gem = Prefs.key(c);
        Exception first = null;
        if (!gem.isEmpty()) {
            try {
                JSONArray parts = new JSONArray().put(new JSONObject().put("text", prompt));
                return Gemini.generate(gem, Prefs.modelSort(c), parts, false, false).text;
            } catch (Exception e) {
                first = e;
            }
        }
        try {
            return backup(c, prompt);
        } catch (Exception e) {
            throw first != null ? first : e;
        }
    }

    /** Groq, then OpenRouter. */
    public static String backup(Context c, String prompt) throws Exception {
        Exception last = null;
        String groq = Prefs.groqKey(c);
        if (!groq.isEmpty()) {
            try {
                return openAi(GROQ, groq, Prefs.modelGroq(c), prompt);
            } catch (Exception e) {
                last = e;
            }
        }
        String or = Prefs.openRouterKey(c);
        if (!or.isEmpty()) {
            try {
                return openAi(OPENROUTER, or, Prefs.modelOpenRouter(c), prompt);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Add an API key in Settings first.");
    }

    private static String openAi(String base, String key, String model, String prompt) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
        String res = Gemini.http("POST", base + "/chat/completions", "Authorization", "Bearer " + key,
                body.toString());
        JSONArray choices = new JSONObject(res).optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("Model returned no answer");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        String text = msg == null || msg.isNull("content") ? "" : msg.optString("content", "").trim();
        if (text.isEmpty()) throw new IOException("Model returned an empty answer");
        return text;
    }

    static List<String> models(String base, String key) throws Exception {
        String res = Gemini.http("GET", base + "/models", "Authorization", "Bearer " + key, null);
        List<String> out = new ArrayList<>();
        JSONArray data = new JSONObject(res).optJSONArray("data");
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            String id = data.getJSONObject(i).optString("id", "");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }
}
