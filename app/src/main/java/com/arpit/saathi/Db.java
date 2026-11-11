package com.arpit.saathi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Db extends SQLiteOpenHelper {
    private static Db inst;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized Db get(Context c) {
        if (inst == null) inst = new Db(c.getApplicationContext());
        return inst;
    }

    private Db(Context c) {
        super(c, "saathi.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER, "
                + "type TEXT, status TEXT, state INTEGER DEFAULT 0, title TEXT, summary TEXT, name TEXT, "
                + "org TEXT, role TEXT, location TEXT, stipend TEXT, eligibility TEXT, deadline TEXT, "
                + "fit INTEGER DEFAULT -1, fit_why TEXT, action TEXT, links TEXT, raw TEXT, source TEXT, "
                + "image TEXT, research TEXT, sources TEXT, error TEXT, attempts INTEGER DEFAULT 0, "
                + "rev INTEGER DEFAULT 0)");
        createTasks(db);
        createTeam(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE items ADD COLUMN rev INTEGER DEFAULT 0");
            createTasks(db);
        }
        if (oldVersion < 3) createTeam(db);
    }

    private void createTeam(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS missions (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER, "
                + "finished INTEGER DEFAULT 0, kind TEXT, goal TEXT, title TEXT, status TEXT, report TEXT, "
                + "error TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS steps (id INTEGER PRIMARY KEY AUTOINCREMENT, mission_id INTEGER, "
                + "idx INTEGER, agent TEXT, task TEXT, status TEXT, output TEXT, sources TEXT, "
                + "searches INTEGER DEFAULT 0)");
    }

    private void createTasks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, "
                + "due INTEGER DEFAULT 0, done INTEGER DEFAULT 0, item_id INTEGER DEFAULT 0, created INTEGER)");
    }

    public static void addListener(Runnable r) { listeners.add(r); }

    public static void removeListener(Runnable r) { listeners.remove(r); }

    private void changed() {
        for (Runnable r : listeners) main.post(r);
    }

    // ---------------------------------------------------------------- items

    public long insertCapture(String raw, String links, String source, String image) {
        ContentValues v = new ContentValues();
        v.put("created", System.currentTimeMillis());
        v.put("type", "pending");
        v.put("status", "new");
        v.put("state", 0);
        v.put("deadline", "");
        v.put("raw", raw);
        v.put("links", links);
        v.put("source", source);
        v.put("image", image);
        long id = getWritableDatabase().insert("items", null, v);
        changed();
        return id;
    }

    /** A note is kept exactly as written: no sorting, no research. */
    public long insertNote(String text) {
        String first = text.split("\n")[0].trim();
        ContentValues v = new ContentValues();
        v.put("created", System.currentTimeMillis());
        v.put("type", "note");
        v.put("status", "new");
        v.put("state", 2);
        v.put("deadline", "");
        v.put("title", first.length() > 70 ? first.substring(0, 70) + "…" : first);
        v.put("summary", text);
        v.put("raw", text);
        v.put("source", "manual");
        long id = getWritableDatabase().insert("items", null, v);
        changed();
        return id;
    }

    public void set(long id, ContentValues v) {
        getWritableDatabase().update("items", v, "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    /**
     * Writes a background result only if the item is still as it was when the work started. If a new
     * screenshot was merged in meanwhile, the stale result is dropped and the item gets processed again.
     */
    public boolean setIfUnchanged(Item snap, ContentValues v) {
        int n = getWritableDatabase().update("items", v, "id=? AND state=? AND rev=?",
                new String[]{String.valueOf(snap.id), String.valueOf(snap.state), String.valueOf(snap.rev)});
        if (n > 0) changed();
        return n > 0;
    }

    public void delete(long id) {
        getWritableDatabase().delete("items", "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public Item item(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM items WHERE id=?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? Item.from(c) : null;
        }
    }

    /** Open items first; among those, upcoming deadlines soonest-first; then newest. */
    public List<Item> list(String type) {
        String today = Ui.today();
        String where = type == null ? "" : " WHERE type=? ";
        String sql = "SELECT * FROM items" + where
                + " ORDER BY (status='done'),"
                + " (CASE WHEN deadline!='' AND deadline>='" + today + "' THEN 0 ELSE 1 END),"
                + " (CASE WHEN deadline!='' AND deadline>='" + today + "' THEN deadline ELSE '' END),"
                + " created DESC";
        return query(sql, type == null ? new String[0] : new String[]{type});
    }

    public List<Item> byState(int state) {
        return query("SELECT * FROM items WHERE state=? ORDER BY created",
                new String[]{String.valueOf(state)});
    }

    public List<Item> all() {
        return query("SELECT * FROM items ORDER BY created DESC", new String[0]);
    }

    private List<Item> query(String sql, String[] args) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(Item.from(c));
        }
        return out;
    }

    // ---------------------------------------------------------------- tasks

    public long addTask(String title, long due, long itemId) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("due", due);
        v.put("item_id", itemId);
        v.put("created", System.currentTimeMillis());
        long id = getWritableDatabase().insert("tasks", null, v);
        changed();
        return id;
    }

    public void setTaskDone(long id, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        getWritableDatabase().update("tasks", v, "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public void deleteTask(long id) {
        getWritableDatabase().delete("tasks", "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public Task task(long id) {
        List<Task> l = tasks("SELECT * FROM tasks WHERE id=" + id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Open tasks first, timed ones soonest-first. */
    public List<Task> tasks() {
        return tasks("SELECT * FROM tasks ORDER BY done, (due=0), due, created DESC");
    }

    private List<Task> tasks(String sql) {
        List<Task> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[0])) {
            while (c.moveToNext()) {
                Task t = new Task();
                t.id = c.getLong(c.getColumnIndexOrThrow("id"));
                t.title = c.getString(c.getColumnIndexOrThrow("title"));
                if (t.title == null) t.title = "";
                t.due = c.getLong(c.getColumnIndexOrThrow("due"));
                t.done = c.getInt(c.getColumnIndexOrThrow("done")) != 0;
                t.itemId = c.getLong(c.getColumnIndexOrThrow("item_id"));
                t.created = c.getLong(c.getColumnIndexOrThrow("created"));
                out.add(t);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- team missions

    public long addMission(String kind, String goal) {
        ContentValues v = new ContentValues();
        v.put("created", System.currentTimeMillis());
        v.put("kind", kind);
        v.put("goal", goal);
        v.put("title", "");
        v.put("status", "queued");
        v.put("report", "");
        v.put("error", "");
        long id = getWritableDatabase().insert("missions", null, v);
        changed();
        return id;
    }

    public void setMission(long id, ContentValues v) {
        getWritableDatabase().update("missions", v, "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public void deleteMission(long id) {
        getWritableDatabase().delete("steps", "mission_id=?", new String[]{String.valueOf(id)});
        getWritableDatabase().delete("missions", "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public Team.Mission mission(long id) {
        List<Team.Mission> l = missions("SELECT * FROM missions WHERE id=" + id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Newest first. */
    public List<Team.Mission> missions() {
        return missions("SELECT * FROM missions ORDER BY created DESC");
    }

    private List<Team.Mission> missions(String sql) {
        List<Team.Mission> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[0])) {
            while (c.moveToNext()) {
                Team.Mission m = new Team.Mission();
                m.id = c.getLong(c.getColumnIndexOrThrow("id"));
                m.created = c.getLong(c.getColumnIndexOrThrow("created"));
                m.finished = c.getLong(c.getColumnIndexOrThrow("finished"));
                m.kind = str(c, "kind");
                m.goal = str(c, "goal");
                m.title = str(c, "title");
                m.status = str(c, "status");
                m.report = str(c, "report");
                m.error = str(c, "error");
                out.add(m);
            }
        }
        return out;
    }

    public long addStep(long missionId, int idx, String agent, String task) {
        ContentValues v = new ContentValues();
        v.put("mission_id", missionId);
        v.put("idx", idx);
        v.put("agent", agent);
        v.put("task", task);
        v.put("status", "wait");
        v.put("output", "");
        v.put("sources", "");
        long id = getWritableDatabase().insert("steps", null, v);
        changed();
        return id;
    }

    public void setStep(long id, ContentValues v) {
        getWritableDatabase().update("steps", v, "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    /** One mission's steps in order, or every step when missionId is 0. */
    public List<Team.Step> steps(long missionId) {
        List<Team.Step> out = new ArrayList<>();
        String sql = "SELECT * FROM steps" + (missionId > 0 ? " WHERE mission_id=" + missionId : "")
                + " ORDER BY mission_id, idx";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[0])) {
            while (c.moveToNext()) {
                Team.Step s = new Team.Step();
                s.id = c.getLong(c.getColumnIndexOrThrow("id"));
                s.missionId = c.getLong(c.getColumnIndexOrThrow("mission_id"));
                s.idx = c.getInt(c.getColumnIndexOrThrow("idx"));
                s.searches = c.getInt(c.getColumnIndexOrThrow("searches"));
                s.agent = str(c, "agent");
                s.task = str(c, "task");
                s.status = str(c, "status");
                s.output = str(c, "output");
                s.sources = str(c, "sources");
                out.add(s);
            }
        }
        return out;
    }

    private static String str(Cursor c, String col) {
        String v = c.getString(c.getColumnIndexOrThrow(col));
        return v == null ? "" : v;
    }

    public void eraseAll() {
        getWritableDatabase().delete("items", null, null);
        getWritableDatabase().delete("tasks", null, null);
        getWritableDatabase().delete("steps", null, null);
        getWritableDatabase().delete("missions", null, null);
        changed();
    }
}
