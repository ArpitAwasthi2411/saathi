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
                    "You find opportunities, programmes, people, organisations and official pages on the "
                            + "web."),
            new Agent("scholar", "Scholar", true, R.color.violet,
                    "You find papers, algorithms, tools, products and technical facts on the web."),
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
        int idx, searches, found;
        String agent = "", task = "", status = "wait", output = "", sources = "", queries = "";
    }

    /** One thing a research agent found, always tied to the web page it came from. */
    static final class Finding {
        long id, missionId, stepId;
        int fit = -1, rank;
        String title = "", what = "", facts = "", deadline = "", status = "", url = "", site = "", why = "";
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
