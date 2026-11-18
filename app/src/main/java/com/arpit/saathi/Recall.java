package com.arpit.saathi;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Saathi's long-term memory of you: lasting facts you tell it, and short notes on past conversations. */
public final class Recall {
    private Recall() {}

    /** Added to every assistant and tutor prompt. */
    public static String block(Context c) {
        List<String[]> facts = Db.get(c).memories("fact", 40);
        List<String[]> sums = Db.get(c).memories("summary", 6);
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat day = new SimpleDateFormat("d MMM", Locale.US);
        if (!facts.isEmpty()) {
            sb.append("WHAT YOU REMEMBER ABOUT THE USER (they told you this; use it, don't recite it):\n");
            for (String[] f : facts) sb.append("- ").append(f[2]).append("\n");
        }
        if (!sums.isEmpty()) {
            sb.append("RECENT CONVERSATIONS (newest first), so you can pick up where you left off:\n");
            for (String[] s : sums) {
                sb.append("- ").append(day.format(new Date(Long.parseLong(s[1])))).append(": ").append(s[2]).append("\n");
            }
        }
        sb.append("Memory actions (add as lines at the very end; they are acted on, not shown or spoken):\n")
                .append("REMEMBER: <one lasting fact or preference>   only when the user states something lasting ")
                .append("about themselves or their preferences, or asks you to remember it\n")
                .append("FORGET: <a few words identifying the fact>   when they ask you to forget something\n");
        return sb.toString();
    }

    /** After a conversation: a one-to-two line note so the next one can continue from it. Runs off the main thread. */
    public static void summarise(final Context app, final String where, final List<String[]> turns) {
        int said = 0;
        for (String[] t : turns) if (!t[0].isEmpty()) said++;
        if (said < 2) return;
        final StringBuilder convo = new StringBuilder();
        for (String[] t : turns) {
            if (!t[0].isEmpty()) convo.append("User: ").append(t[0]).append("\n");
            String r = t[1].length() > 500 ? t[1].substring(0, 500) + "…" : t[1];
            convo.append("Assistant: ").append(r).append("\n");
        }
        new Thread(() -> {
            try {
                String s = Llm.chat(app, "Write a memory note about this conversation (" + where + ") for the "
                        + "assistant to read next time: in at most 2 short lines, what was covered, what the user "
                        + "found hard or asked about, and anything left to follow up. Plain text only.\n\n"
                        + (convo.length() > 6000 ? convo.substring(convo.length() - 6000) : convo));
                s = Ui.plain(s).replace('\n', ' ').trim();
                if (!s.isEmpty()) Db.get(app).remember("summary", "[" + where + "] " + s);
            } catch (Exception ignored) {
                // memory is best-effort
            }
        }, "saathi-recall").start();
    }
}
