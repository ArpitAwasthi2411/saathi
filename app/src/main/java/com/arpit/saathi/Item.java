package com.arpit.saathi;

import android.database.Cursor;

/** One captured thing. state: 0 captured, 1 sorted, 2 researched, -1 sorting failed, -2 research failed. */
public class Item {
    public long id;
    public long created;
    public String type = "pending";
    public String status = "new";
    public int state;
    public String title = "", summary = "", name = "", org = "", role = "", location = "", stipend = "",
            eligibility = "", deadline = "", fitWhy = "", action = "", links = "", raw = "", source = "",
            image = "", research = "", sources = "", error = "";
    public int fit = -1;
    public int attempts;
    public int rev;

    /** Screenshot files that still exist (a merged capture has several). */
    public java.util.List<String> images() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : image.split("\n")) {
            if (!p.trim().isEmpty() && new java.io.File(p.trim()).exists()) out.add(p.trim());
        }
        return out;
    }

    static Item from(Cursor c) {
        Item i = new Item();
        i.id = c.getLong(c.getColumnIndexOrThrow("id"));
        i.created = c.getLong(c.getColumnIndexOrThrow("created"));
        i.type = s(c, "type");
        i.status = s(c, "status");
        i.state = c.getInt(c.getColumnIndexOrThrow("state"));
        i.title = s(c, "title");
        i.summary = s(c, "summary");
        i.name = s(c, "name");
        i.org = s(c, "org");
        i.role = s(c, "role");
        i.location = s(c, "location");
        i.stipend = s(c, "stipend");
        i.eligibility = s(c, "eligibility");
        i.deadline = s(c, "deadline");
        i.fit = c.getInt(c.getColumnIndexOrThrow("fit"));
        i.fitWhy = s(c, "fit_why");
        i.action = s(c, "action");
        i.links = s(c, "links");
        i.raw = s(c, "raw");
        i.source = s(c, "source");
        i.image = s(c, "image");
        i.research = s(c, "research");
        i.sources = s(c, "sources");
        i.error = s(c, "error");
        i.attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
        i.rev = c.getInt(c.getColumnIndexOrThrow("rev"));
        return i;
    }

    private static String s(Cursor c, String col) {
        String v = c.getString(c.getColumnIndexOrThrow(col));
        return v == null ? "" : v;
    }
}
