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
        super(c, "saathi.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER, "
                + "type TEXT, status TEXT, state INTEGER DEFAULT 0, title TEXT, summary TEXT, name TEXT, "
                + "org TEXT, role TEXT, location TEXT, stipend TEXT, eligibility TEXT, deadline TEXT, "
                + "fit INTEGER DEFAULT -1, fit_why TEXT, action TEXT, links TEXT, raw TEXT, source TEXT, "
                + "image TEXT, research TEXT, sources TEXT, error TEXT, attempts INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public static void addListener(Runnable r) { listeners.add(r); }

    public static void removeListener(Runnable r) { listeners.remove(r); }

    private void changed() {
        for (Runnable r : listeners) main.post(r);
    }

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

    public void set(long id, ContentValues v) {
        getWritableDatabase().update("items", v, "id=?", new String[]{String.valueOf(id)});
        changed();
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
        return query("SELECT * FROM items", new String[0]);
    }

    private List<Item> query(String sql, String[] args) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(Item.from(c));
        }
        return out;
    }
}
