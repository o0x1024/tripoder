package dev.tripoder.fixture;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Writes/reads state across three storage kinds so a checkpoint restore can be checked end to end. */
public final class CheckpointFixture {
    private static SQLiteDatabase db() {
        SQLiteDatabase db = FixtureApplication.instance.openOrCreateDatabase("checkpoint.db", 0, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY, body TEXT NOT NULL)");
        return db;
    }
    private static SharedPreferences prefs() { return FixtureApplication.instance.getSharedPreferences("checkpoint", 0); }
    private static File file() { return new File(FixtureApplication.instance.getFilesDir(), "checkpoint.txt"); }

    /** Appends one row, bumps a pref counter and rewrites a file with the given tag. */
    public static String write(String tag) throws Exception {
        try (SQLiteDatabase db = db()) { ContentValues v = new ContentValues(); v.put("body", tag); db.insertOrThrow("notes", null, v); }
        int counter = prefs().getInt("counter", 0) + 1;
        if (!prefs().edit().putInt("counter", counter).putString("last", tag).commit()) throw new IllegalStateException("PREFS_COMMIT");
        Files.write(file().toPath(), tag.getBytes(StandardCharsets.UTF_8));
        return state();
    }

    public static String state() throws Exception {
        StringBuilder rows = new StringBuilder();
        try (SQLiteDatabase db = db(); Cursor c = db.rawQuery("SELECT body FROM notes ORDER BY id", null)) {
            while (c.moveToNext()) rows.append(rows.length() == 0 ? "" : ",").append(c.getString(0));
        }
        String text = file().exists() ? new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8) : "-";
        return "rows=[" + rows + "] counter=" + prefs().getInt("counter", 0) + " last=" + prefs().getString("last", "-") + " file=" + text;
    }
}
