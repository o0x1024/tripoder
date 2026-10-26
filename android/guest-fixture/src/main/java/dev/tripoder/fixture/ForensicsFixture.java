package dev.tripoder.fixture;

import android.content.Context;
import net.sqlcipher.database.SQLiteDatabase;
import com.tencent.mmkv.MMKV;
import java.io.File;

/** Real SQLCipher + MMKV encrypted storage, both statically linked into this guest app, for forensics-key-probe verification. */
public final class ForensicsFixture {
    public static final String SQLCIPHER_PASSWORD = "tripoder-forensics-demo-key-7f3a";
    public static final String MMKV_CRYPT_KEY = "tripoder-mmkv-demo-key-91c2";

    public static String openSqlCipherDb(Context context) {
        SQLiteDatabase.loadLibs(context);
        File dbFile = new File(context.getFilesDir(), "secrets.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, SQLCIPHER_PASSWORD, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS secret (id INTEGER PRIMARY KEY, value TEXT)");
        db.execSQL("INSERT INTO secret (value) VALUES ('classified-payload')");
        db.close();
        return dbFile.getAbsolutePath();
    }

    public static String openMmkv(Context context) {
        MMKV.initialize(context);
        MMKV kv = MMKV.mmkvWithID("forensics-demo", MMKV.SINGLE_PROCESS_MODE, MMKV_CRYPT_KEY);
        kv.encode("classified-key", "classified-value");
        return kv.mmapID();
    }

    private static android.database.sqlite.SQLiteDatabase walDb;
    /** Plain (unencrypted) WAL-mode database, kept open (never closed/checkpointed) so a deleted row remains carvable from the raw -wal file. */
    public static String openWalWithDeletedRow(Context context) {
        File dir = new File(context.getFilesDir(), "databases");
        dir.mkdirs();
        File dbFile = new File(dir, "wal_demo.db");
        walDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        walDb.enableWriteAheadLogging();
        walDb.execSQL("CREATE TABLE IF NOT EXISTS secret (id INTEGER PRIMARY KEY, value TEXT)");
        walDb.execSQL("DELETE FROM secret");
        walDb.execSQL("INSERT INTO secret (id, value) VALUES (1, 'kept-row')");
        walDb.execSQL("INSERT INTO secret (id, value) VALUES (2, 'deleted-classified-row')");
        walDb.execSQL("DELETE FROM secret WHERE id = 2");
        return dbFile.getAbsolutePath();
    }
}
