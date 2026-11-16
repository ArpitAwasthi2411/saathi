package com.arpit.saathi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.arpit.saathi.gate.GateDb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * One file with everything you saved: inbox, tasks, team missions, GATE mocks, notes and screenshots.
 * API keys are not included (they are locked to this phone); type them in again after a restore.
 */
final class Backup {
    static final int REQ_SAVE = 61, REQ_OPEN = 62;
    private static final String[] DBS = {"saathi.db", "gate.db"};
    private static final String[] DIRS = {"shots", "gate"};

    private Backup() {}

    static void askSave(Activity a) {
        String name = "Saathi backup " + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".zip";
        try {
            a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), REQ_SAVE);
        } catch (Exception e) {
            Toast.makeText(a, "No file picker found", Toast.LENGTH_SHORT).show();
        }
    }

    static void askRestore(Activity a) {
        new AlertDialog.Builder(a).setTitle("Restore a backup?")
                .setMessage("Everything currently in Saathi is replaced by the backup. Your keys and settings stay.")
                .setPositiveButton("Choose file", (d, w) -> {
                    try {
                        a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_OPEN);
                    } catch (Exception e) {
                        Toast.makeText(a, "No file picker found", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- save

    static void save(final Activity a, final Uri where) {
        final Context c = a.getApplicationContext();
        new Thread(() -> {
            boolean ok;
            try (OutputStream os = c.getContentResolver().openOutputStream(where);
                 ZipOutputStream zip = new ZipOutputStream(os)) {
                // flush the write-ahead log into the main file so one copy is complete
                checkpoint(Db.get(c).getWritableDatabase());
                checkpoint(GateDb.get(c).getWritableDatabase());
                for (String name : DBS) {
                    File f = c.getDatabasePath(name);
                    if (f.exists()) add(zip, "databases/" + name, f);
                }
                for (String dir : DIRS) addDir(zip, "files/" + dir, new File(c.getFilesDir(), dir));
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean fOk = ok;
            a.runOnUiThread(() -> Toast.makeText(c, fOk ? "Backup saved" : "Couldn't write the backup",
                    Toast.LENGTH_LONG).show());
        }, "saathi-backup").start();
    }

    private static void checkpoint(android.database.sqlite.SQLiteDatabase db) {
        try (android.database.Cursor cur = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
            cur.moveToFirst(); // the pragma only runs when the cursor is read
        } catch (Exception ignored) {
            // not in WAL mode: nothing to flush
        }
    }

    private static void addDir(ZipOutputStream zip, String prefix, File dir) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) addDir(zip, prefix + "/" + f.getName(), f);
            else add(zip, prefix + "/" + f.getName(), f);
        }
    }

    private static void add(ZipOutputStream zip, String name, File f) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    // ---------------------------------------------------------------- restore

    /** Only these paths may come out of a backup, so a crafted file cannot write anywhere else. */
    private static File target(Context c, String name) {
        if (name.contains("..") || name.startsWith("/") || name.contains("\\")) return null;
        for (String db : DBS) if (name.equals("databases/" + db)) return c.getDatabasePath(db);
        for (String dir : DIRS) {
            String p = "files/" + dir + "/";
            if (name.startsWith(p) && name.length() > p.length()) return new File(c.getFilesDir(), name.substring(6));
        }
        return null;
    }

    static void restore(final Activity a, final Uri from) {
        final Context c = a.getApplicationContext();
        new Thread(() -> {
            File stage = new File(c.getCacheDir(), "restore");
            deleteTree(stage);
            boolean ok = false;
            String err = "";
            try (InputStream in = c.getContentResolver().openInputStream(from);
                 ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry e;
                int dbs = 0;
                long total = 0;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.isDirectory() || target(c, e.getName()) == null) continue;
                    File out = new File(stage, e.getName());
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = zip.read(buf)) > 0) {
                            total += n;
                            if (total > 1024L * 1024 * 1024) throw new Exception("Backup is too large");
                            os.write(buf, 0, n);
                        }
                    }
                    if (e.getName().startsWith("databases/")) dbs++;
                }
                if (dbs == 0) throw new Exception("This isn't a Saathi backup");

                Db.closeAll();
                GateDb.closeAll();
                for (String db : DBS) {
                    for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
                        new File(c.getDatabasePath(db).getPath() + suffix).delete();
                    }
                }
                for (String dir : DIRS) deleteTree(new File(c.getFilesDir(), dir));
                move(c, stage, "");
                ok = true;
            } catch (Exception ex) {
                err = ex.getMessage() == null ? "" : ex.getMessage();
            }
            deleteTree(stage);
            final boolean fOk = ok;
            final String fErr = err;
            a.runOnUiThread(() -> {
                if (!fOk) {
                    Toast.makeText(c, "Couldn't restore: " + fErr, Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(a).setCancelable(false).setMessage("Restored. Saathi will close now; "
                        + "open it again to see everything.")
                        .setPositiveButton("OK", (d, w) -> {
                            a.finishAffinity();
                            android.os.Process.killProcess(android.os.Process.myPid());
                        }).show();
            });
        }, "saathi-restore").start();
    }

    private static void move(Context c, File dir, String rel) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) {
                move(c, f, name);
                continue;
            }
            File to = target(c, name);
            if (to == null) continue;
            to.getParentFile().mkdirs();
            if (!f.renameTo(to)) {
                try (InputStream in = new FileInputStream(f); OutputStream os = new FileOutputStream(to)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
