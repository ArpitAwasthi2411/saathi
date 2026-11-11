package com.arpit.saathi;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Prefs {
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";
    public static final String DEFAULT_GROQ = "openai/gpt-oss-120b";
    public static final String DEFAULT_OPENROUTER = "openrouter/free";

    private static final Map<String, String> cache = new ConcurrentHashMap<>();

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("saathi", Context.MODE_PRIVATE);
    }

    // ---- API keys, stored encrypted ("key" is the Gemini key)

    public static String key(Context c) { return secret(c, "key"); }

    public static String groqKey(Context c) { return secret(c, "groq"); }

    public static String openRouterKey(Context c) { return secret(c, "openrouter"); }

    static String secret(Context c, String name) {
        String hit = cache.get(name);
        if (hit != null) return hit;
        SharedPreferences sp = sp(c);
        String value;
        String stored = sp.getString("enc_" + name, null);
        if (stored != null) {
            String d = Secure.dec(stored);
            value = d == null ? "" : d.trim();
        } else {
            // a key saved by an older version in plain text: encrypt it now
            value = sp.getString(name, "").trim();
            if (!value.isEmpty()) {
                String e = Secure.enc(value);
                if (e != null) sp.edit().putString("enc_" + name, e).remove(name).apply();
            }
        }
        cache.put(name, value);
        return value;
    }

    static void setSecret(Context c, String name, String value) {
        value = value == null ? "" : value.trim();
        SharedPreferences.Editor ed = sp(c).edit();
        if (value.isEmpty()) {
            ed.remove("enc_" + name).remove(name);
        } else {
            String e = Secure.enc(value);
            if (e != null) ed.putString("enc_" + name, e).remove(name);
            else ed.putString(name, value).remove("enc_" + name);
        }
        ed.apply();
        cache.put(name, value);
    }

    // ---- models

    public static String modelSort(Context c) { return model(c, "modelSort", DEFAULT_MODEL); }

    public static String modelResearch(Context c) { return model(c, "modelResearch", DEFAULT_MODEL); }

    public static String modelGroq(Context c) { return model(c, "modelGroq", DEFAULT_GROQ); }

    public static String modelOpenRouter(Context c) { return model(c, "modelOpenRouter", DEFAULT_OPENROUTER); }

    private static String model(Context c, String pref, String def) {
        String m = sp(c).getString(pref, "").trim();
        if (m.startsWith("models/")) m = m.substring(7);
        return m.isEmpty() ? def : m;
    }

    // ---- the rest

    public static String profile(Context c) {
        String p = sp(c).getString("profile", "").trim();
        return p.isEmpty() ? "(The user has not described themselves yet. Give a fit of about 50 and say the "
                + "profile is missing.)" : p;
    }

    public static int digestHour(Context c) { return sp(c).getInt("digestHour", 20); }

    public static boolean wifiOnly(Context c) { return sp(c).getBoolean("wifiOnly", false); }

    public static boolean lock(Context c) { return sp(c).getBoolean("lock", false); }
}
