package com.arpit.saathi;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Text-in, text-out across every provider the user has a key for. The provider chosen in Settings
 * answers first; the others take over when it fails or is over its limit.
 */
public final class Llm {
    static final String GROQ = "https://api.groq.com/openai/v1";
    static final String OPENROUTER = "https://openrouter.ai/api/v1";
    private static final String[] ORDER = {"gemini", "custom", "groq", "openrouter"};

    private Llm() {}

    private static boolean has(Context c, String provider) {
        switch (provider) {
            case "gemini": return !Prefs.key(c).isEmpty();
            case "groq": return !Prefs.groqKey(c).isEmpty();
            case "openrouter": return !Prefs.openRouterKey(c).isEmpty();
            default: return !Prefs.customKey(c).isEmpty() && !Prefs.customBase(c).isEmpty()
                    && !Prefs.modelCustom(c).isEmpty();
        }
    }

    public static boolean any(Context c) {
        return has(c, "gemini") || hasBackup(c);
    }

    public static boolean hasBackup(Context c) {
        return has(c, "custom") || has(c, "groq") || has(c, "openrouter");
    }

    private static List<String> order(Context c, boolean withGemini) {
        List<String> out = new ArrayList<>();
        String first = Prefs.brain(c);
        if (has(c, first) && (withGemini || !first.equals("gemini"))) out.add(first);
        for (String p : ORDER) {
            if (!out.contains(p) && has(c, p) && (withGemini || !p.equals("gemini"))) out.add(p);
        }
        return out;
    }

    /** Thinking work: the preferred provider, then every other one. */
    public static String chat(Context c, String prompt) throws Exception {
        return run(c, order(c, true), prompt);
    }

    /** Everything except Gemini, for when Gemini itself just failed. */
    public static String backup(Context c, String prompt) throws Exception {
        return run(c, order(c, false), prompt);
    }

    private static String run(Context c, List<String> providers, String prompt) throws Exception {
        Exception first = null;
        for (String p : providers) {
            try {
                switch (p) {
                    case "gemini":
                        JSONArray parts = new JSONArray().put(new JSONObject().put("text", prompt));
                        return Gemini.generate(Prefs.key(c), Prefs.modelResearch(c), parts, false, false).text;
                    case "groq":
                        return openAi(GROQ, Prefs.groqKey(c), Prefs.modelGroq(c), prompt);
                    case "openrouter":
                        return openAi(OPENROUTER, Prefs.openRouterKey(c), Prefs.modelOpenRouter(c), prompt);
                    default:
                        return openAi(Prefs.customBase(c), Prefs.customKey(c), Prefs.modelCustom(c), prompt);
                }
            } catch (Exception e) {
                if (first == null) first = e;
            }
        }
        throw first != null ? first : new IOException("Add an API key in Settings first.");
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
