package com.arpit.saathi;

import android.content.Context;

/** The roster: one master agent ("Chief") and the specialists it hands work to. */
final class Team {
    static final class Agent {
        final String key, name, role;
        final boolean search;
        final int color;

        Agent(String key, String name, boolean search, int color, String role) {
            this.key = key;
            this.name = name;
            this.search = search;
            this.color = color;
            this.role = role;
        }
    }

    static final Agent[] AGENTS = {
            new Agent("scout", "Scout", true, R.color.teal,
                    "You search the web for opportunities, programmes, people, organisations and official "
                            + "pages, and report exactly what you found with links and dates."),
            new Agent("scholar", "Scholar", true, R.color.violet,
                    "You study papers, algorithms, tools and technical topics on the web and explain them "
                            + "clearly, with the key ideas, results and where to read more."),
            new Agent("analyst", "Analyst", false, R.color.amber,
                    "You compare options, check them against the founder's profile and goals, point out "
                            + "risks and gaps, and recommend what to pick and why."),
            new Agent("writer", "Writer", false, R.color.slate,
                    "You write the finished text the founder needs: emails, cover notes, statements of "
                            + "purpose, summaries, checklists. Ready to send, in the founder's voice."),
            new Agent("planner", "Planner", false, R.color.orange,
                    "You turn results into a concrete plan: ordered steps with dates, what to do first, "
                            + "and what to prepare."),
    };

    static final class Mission {
        long id, created, finished;
        String kind = "mission", goal = "", title = "", status = "queued", report = "", error = "";
    }

    static final class Step {
        long id, missionId;
        int idx, searches;
        String agent = "", task = "", status = "wait", output = "", sources = "";
    }

    private Team() {}

    static Agent agent(String key) {
        for (Agent a : AGENTS) if (a.key.equals(key)) return a;
        return null;
    }

    static String agentName(String key) {
        Agent a = agent(key);
        return a == null ? key : a.name;
    }

    static int agentColor(Context c, String key) {
        Agent a = agent(key);
        return c.getColor(a == null ? R.color.grey : a.color);
    }
}
