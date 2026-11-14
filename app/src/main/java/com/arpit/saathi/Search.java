package com.arpit.saathi;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Web search done by the app itself (Tavily), not left to a model's discretion. The model only ever
 * reads the pages this returns, so every fact in a report can be traced to a real address.
 */
final class Search {
    static final class Source {
        int n;
        String title = "", url = "", text = "";
    }

    private Search() {}

    static boolean available(Context c) {
        return !Prefs.tavilyKey(c).isEmpty();
    }

    /** Runs every query and returns the distinct pages found, numbered from 1. */
    static List<Source> run(Context c, List<String> queries, int perQuery, int maxSources, boolean deep)
            throws Exception {
        String key = Prefs.tavilyKey(c);
        List<Source> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Exception firstError = null;
        int ok = 0;
        for (String q : queries) {
            if (q == null || q.trim().isEmpty()) continue;
            try {
                JSONObject body = new JSONObject()
                        .put("query", q.trim())
                        .put("search_depth", deep ? "advanced" : "basic")
                        .put("max_results", perQuery)
                        .put("include_raw_content", "text");
                String res = Gemini.http("POST", "https://api.tavily.com/search", "Authorization", "Bearer " + key,
                        body.toString());
                ok++;
                JSONArray results = new JSONObject(res).optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    String url = r.optString("url", "").trim();
                    if (!url.startsWith("http") || !seen.add(url) || out.size() >= maxSources) continue;
                    String snippet = r.isNull("content") ? "" : r.optString("content", "");
                    String raw = r.isNull("raw_content") ? "" : r.optString("raw_content", "");
                    Source s = new Source();
                    s.n = out.size() + 1;
                    s.url = url;
                    s.title = r.optString("title", url).replace('\n', ' ').trim();
                    // the matching snippet first, then the start of the page for dates and details
                    String text = snippet + (raw.isEmpty() ? "" : "\n" + raw);
                    text = text.replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").trim();
                    s.text = text.length() > 2200 ? text.substring(0, 2200) : text;
                    out.add(s);
                }
            } catch (Exception e) {
                if (firstError == null) firstError = e;
            }
        }
        if (ok == 0 && firstError != null) throw firstError;
        return out;
    }

    static String pack(List<Source> sources) {
        StringBuilder sb = new StringBuilder();
        for (Source s : sources) {
            sb.append("[S").append(s.n).append("] ").append(s.title).append("\nAddress: ").append(s.url)
                    .append("\n").append(s.text).append("\n\n");
        }
        return sb.toString();
    }

    /** "title|url" lines, the form sources are stored in. */
    static String store(List<Source> sources, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size() && i < max; i++) {
            Source s = sources.get(i);
            if (sb.length() > 0) sb.append("\n");
            sb.append(s.title.replace("|", " ")).append("|").append(s.url);
        }
        return sb.toString();
    }
}
