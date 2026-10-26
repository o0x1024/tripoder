package dev.tripoder.fixture;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Test-only exported provider with an in-memory items table plus a readable file. */
public final class FixtureProvider extends ContentProvider {
    private static final List<String[]> ROWS = new ArrayList<>();
    static { ROWS.add(new String[]{"1", "alpha", "10"}); ROWS.add(new String[]{"2", "beta", "20"}); }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "name", "value"});
        synchronized (ROWS) { for (String[] row : ROWS) if (selection == null || args == null || args.length == 0 || row[1].equals(args[0])) cursor.addRow(row); }
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.tripoder.item"; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        synchronized (ROWS) { String id = Integer.toString(ROWS.size() + 1); ROWS.add(new String[]{id, values.getAsString("name"), String.valueOf(values.getAsInteger("value"))}); return uri.buildUpon().appendPath(id).build(); }
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        synchronized (ROWS) { int before = ROWS.size(); ROWS.removeIf(row -> args != null && args.length > 0 && row[1].equals(args[0])); return before - ROWS.size(); }
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        int changed = 0;
        synchronized (ROWS) { for (String[] row : ROWS) if (args != null && args.length > 0 && row[1].equals(args[0])) { row[2] = String.valueOf(values.getAsInteger("value")); changed++; } }
        return changed;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle(); out.putString("echo", method + ":" + arg); out.putInt("rows", ROWS.size()); return out;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File file = new File(getContext().getFilesDir(), "provider-note.txt");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write("provider-file-contents".getBytes("UTF-8")); } catch (java.io.IOException failure) { throw new java.io.FileNotFoundException(failure.toString()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
