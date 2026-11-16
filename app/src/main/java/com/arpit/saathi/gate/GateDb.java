package com.arpit.saathi.gate;

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

/** GATE data lives in its own database file, apart from the inbox. */
public class GateDb extends SQLiteOpenHelper {
    private static GateDb inst;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized GateDb get(Context c) {
        if (inst == null) inst = new GateDb(c.getApplicationContext());
        return inst;
    }

    /** Used before a restore replaces the file. */
    public static synchronized void closeAll() {
        if (inst != null) inst.close();
        inst = null;
    }

    private GateDb(Context c) {
        super(c, "gate.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE mocks (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER, taken INTEGER, "
                + "title TEXT, source TEXT, status TEXT, progress TEXT, error TEXT, pages INTEGER DEFAULT 0, "
                + "questions INTEGER DEFAULT 0, attempted INTEGER DEFAULT 0, correct INTEGER DEFAULT 0, "
                + "wrong INTEGER DEFAULT 0, partial INTEGER DEFAULT 0, score REAL DEFAULT 0, max REAL DEFAULT 0)");
        db.execSQL("CREATE TABLE qs (id INTEGER PRIMARY KEY AUTOINCREMENT, mock_id INTEGER, n INTEGER, "
                + "page INTEGER DEFAULT 0, page_to INTEGER DEFAULT 0, marks REAL DEFAULT 0, score REAL DEFAULT 0, "
                + "max REAL DEFAULT 0, type TEXT, question TEXT, options TEXT, yours TEXT, correct TEXT, "
                + "result TEXT, subject TEXT, topic TEXT, solution TEXT, chat TEXT)");
        db.execSQL("CREATE TABLE topics (key TEXT PRIMARY KEY, subject TEXT, name TEXT, notes TEXT, practice TEXT, "
                + "revised INTEGER DEFAULT 0, confidence INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE log (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, kind TEXT, text TEXT, "
                + "minutes INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public static void addListener(Runnable r) { listeners.add(r); }

    public static void removeListener(Runnable r) { listeners.remove(r); }

    void changed() {
        for (Runnable r : listeners) main.post(r);
    }

    private static String str(Cursor c, String col) {
        String v = c.getString(c.getColumnIndexOrThrow(col));
        return v == null ? "" : v;
    }

    // ---------------------------------------------------------------- mocks

    public long addMock(String title, String source, long taken) {
        ContentValues v = new ContentValues();
        v.put("created", System.currentTimeMillis());
        v.put("taken", taken);
        v.put("title", title);
        v.put("source", source);
        v.put("status", "pdf".equals(source) ? "queued" : "ready");
        v.put("progress", "");
        v.put("error", "");
        long id = getWritableDatabase().insert("mocks", null, v);
        changed();
        return id;
    }

    public void setMock(long id, ContentValues v) {
        getWritableDatabase().update("mocks", v, "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public void deleteMock(long id) {
        getWritableDatabase().delete("qs", "mock_id=?", new String[]{String.valueOf(id)});
        getWritableDatabase().delete("mocks", "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public Gate.Mock mock(long id) {
        List<Gate.Mock> l = mocks("SELECT * FROM mocks WHERE id=" + id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Most recent first. */
    public List<Gate.Mock> mocks() {
        return mocks("SELECT * FROM mocks ORDER BY taken DESC, id DESC");
    }

    private List<Gate.Mock> mocks(String sql) {
        List<Gate.Mock> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[0])) {
            while (c.moveToNext()) {
                Gate.Mock m = new Gate.Mock();
                m.id = c.getLong(c.getColumnIndexOrThrow("id"));
                m.created = c.getLong(c.getColumnIndexOrThrow("created"));
                m.taken = c.getLong(c.getColumnIndexOrThrow("taken"));
                m.title = str(c, "title");
                m.source = str(c, "source");
                m.status = str(c, "status");
                m.progress = str(c, "progress");
                m.error = str(c, "error");
                m.pages = c.getInt(c.getColumnIndexOrThrow("pages"));
                m.questions = c.getInt(c.getColumnIndexOrThrow("questions"));
                m.attempted = c.getInt(c.getColumnIndexOrThrow("attempted"));
                m.correct = c.getInt(c.getColumnIndexOrThrow("correct"));
                m.wrong = c.getInt(c.getColumnIndexOrThrow("wrong"));
                m.partial = c.getInt(c.getColumnIndexOrThrow("partial"));
                m.score = c.getDouble(c.getColumnIndexOrThrow("score"));
                m.max = c.getDouble(c.getColumnIndexOrThrow("max"));
                out.add(m);
            }
        }
        return out;
    }

    /** Recounts a mock's totals from its questions (questions worth 0 marks are left out). */
    public void recount(long mockId) {
        int qn = 0, att = 0, ok = 0, bad = 0, part = 0;
        double score = 0, max = 0;
        for (Gate.Q q : qs(mockId)) {
            if (q.max <= 0) continue;
            qn++;
            score += q.score;
            max += q.max;
            if (!"unattempted".equals(q.result)) att++;
            if ("correct".equals(q.result)) ok++;
            else if ("wrong".equals(q.result)) bad++;
            else if ("partial".equals(q.result)) part++;
        }
        ContentValues v = new ContentValues();
        v.put("questions", qn);
        v.put("attempted", att);
        v.put("correct", ok);
        v.put("wrong", bad);
        v.put("partial", part);
        v.put("score", Math.round(score * 100) / 100.0);
        v.put("max", Math.round(max * 100) / 100.0);
        setMock(mockId, v);
    }

    // ---------------------------------------------------------------- questions

    public long addQ(Gate.Q q) {
        ContentValues v = qValues(q);
        v.put("mock_id", q.mockId);
        v.put("solution", "");
        v.put("chat", "");
        long id = getWritableDatabase().insert("qs", null, v);
        changed();
        return id;
    }

    public void updateQ(Gate.Q q) {
        getWritableDatabase().update("qs", qValues(q), "id=?", new String[]{String.valueOf(q.id)});
        changed();
    }

    private static ContentValues qValues(Gate.Q q) {
        ContentValues v = new ContentValues();
        v.put("n", q.n);
        v.put("page", q.page);
        v.put("page_to", q.pageTo);
        v.put("marks", q.marks);
        v.put("score", q.score);
        v.put("max", q.max);
        v.put("type", q.type);
        v.put("question", q.question);
        v.put("options", q.options);
        v.put("yours", q.yours);
        v.put("correct", q.correct);
        v.put("result", q.result);
        v.put("subject", q.subject);
        v.put("topic", q.topic);
        return v;
    }

    public void setQ(long id, String col, String value) {
        ContentValues v = new ContentValues();
        v.put(col, value);
        getWritableDatabase().update("qs", v, "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public Gate.Q q(long id) {
        List<Gate.Q> l = qs("SELECT * FROM qs WHERE id=" + id, true);
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Gate.Q> qs(long mockId) {
        return qs("SELECT * FROM qs WHERE mock_id=" + mockId + " ORDER BY n", true);
    }

    /** Every question ever imported (for topic statistics). */
    public List<Gate.Q> allQs() {
        return qs("SELECT * FROM qs ORDER BY mock_id, n", false);
    }

    private List<Gate.Q> qs(String sql, boolean full) {
        List<Gate.Q> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[0])) {
            while (c.moveToNext()) {
                Gate.Q q = new Gate.Q();
                q.id = c.getLong(c.getColumnIndexOrThrow("id"));
                q.mockId = c.getLong(c.getColumnIndexOrThrow("mock_id"));
                q.n = c.getInt(c.getColumnIndexOrThrow("n"));
                q.page = c.getInt(c.getColumnIndexOrThrow("page"));
                q.pageTo = c.getInt(c.getColumnIndexOrThrow("page_to"));
                q.marks = c.getDouble(c.getColumnIndexOrThrow("marks"));
                q.score = c.getDouble(c.getColumnIndexOrThrow("score"));
                q.max = c.getDouble(c.getColumnIndexOrThrow("max"));
                q.type = str(c, "type");
                q.result = str(c, "result");
                q.subject = str(c, "subject");
                q.topic = str(c, "topic");
                q.yours = str(c, "yours");
                q.correct = str(c, "correct");
                q.question = str(c, "question");
                if (full) {
                    q.options = str(c, "options");
                    q.solution = str(c, "solution");
                    q.chat = str(c, "chat");
                }
                out.add(q);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- topics

    public Gate.Topic topic(String key) {
        Gate.Topic t = new Gate.Topic();
        t.key = key;
        int slash = key.indexOf(" / ");
        t.subject = slash > 0 ? key.substring(0, slash) : "";
        t.name = slash > 0 ? key.substring(slash + 3) : key;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM topics WHERE key=?", new String[]{key})) {
            if (c.moveToFirst()) {
                t.notes = str(c, "notes");
                t.practice = str(c, "practice");
                t.revised = c.getLong(c.getColumnIndexOrThrow("revised"));
                t.confidence = c.getInt(c.getColumnIndexOrThrow("confidence"));
            }
        }
        return t;
    }

    public void setTopic(String key, String col, Object value) {
        Gate.Topic t = topic(key);
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("subject", t.subject);
        v.put("name", t.name);
        if (value instanceof Long) v.put(col, (Long) value);
        else if (value instanceof Integer) v.put(col, (Integer) value);
        else v.put(col, String.valueOf(value));
        SQLiteDatabase db = getWritableDatabase();
        if (db.update("topics", v, "key=?", new String[]{key}) == 0) db.insert("topics", null, v);
        changed();
    }

    // ---------------------------------------------------------------- study log

    public void log(String kind, String text, int minutes) {
        ContentValues v = new ContentValues();
        v.put("at", System.currentTimeMillis());
        v.put("kind", kind);
        v.put("text", text);
        v.put("minutes", minutes);
        getWritableDatabase().insert("log", null, v);
        changed();
    }

    public void deleteLog(long id) {
        getWritableDatabase().delete("log", "id=?", new String[]{String.valueOf(id)});
        changed();
    }

    public List<Gate.LogEntry> logs(int limit) {
        List<Gate.LogEntry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM log ORDER BY at DESC LIMIT " + limit,
                new String[0])) {
            while (c.moveToNext()) {
                Gate.LogEntry e = new Gate.LogEntry();
                e.id = c.getLong(c.getColumnIndexOrThrow("id"));
                e.at = c.getLong(c.getColumnIndexOrThrow("at"));
                e.kind = str(c, "kind");
                e.text = str(c, "text");
                e.minutes = c.getInt(c.getColumnIndexOrThrow("minutes"));
                out.add(e);
            }
        }
        return out;
    }
}
