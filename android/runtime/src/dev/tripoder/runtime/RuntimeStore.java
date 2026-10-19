package dev.tripoder.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** The broker is the only owner of this connection. Guest processes never open it. */
public final class RuntimeStore extends SQLiteOpenHelper implements ExecutionContract.Store {
    private RandomAccessFile authorityFile;
    private FileLock authorityLock;
    private boolean closed;
    private static final java.util.Set<String> LOCAL_AUTHORITIES=new java.util.HashSet<>();
    private String authorityKey;
    public final String authorityGeneration=java.util.UUID.randomUUID().toString();
    private final java.util.Set<String> openGenerations=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> closingGenerations=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> deadGenerations=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public RuntimeStore(Context context) {
        // P0's experimental database is retained as historical data, never implicitly promoted.
        super(context, "tripoder-runtime-v2.db", null, 33);
        setWriteAheadLoggingEnabled(true);
        synchronized(RuntimeStore.class){try {
            File directory=new File(context.getFilesDir(),"runtime");
            if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("AUTHORITY_DIRECTORY_FAILED");
            String key=new File(directory,"authority.lock").getCanonicalPath();
            // POSIX record locks can be released by closing ANY fd for this inode in the process.
            // Reject a duplicate owner before opening another descriptor, not after tryLock fails.
            if(LOCAL_AUTHORITIES.contains(key))throw new IllegalStateException("AUTHORITY_ALREADY_OWNED");
            authorityFile=new RandomAccessFile(key,"rw");
            authorityLock=authorityFile.getChannel().tryLock();
            if(authorityLock==null)throw new IllegalStateException("AUTHORITY_ALREADY_OWNED");
            authorityKey=key;LOCAL_AUTHORITIES.add(key);
        }catch(Exception error){try{if(authorityFile!=null)authorityFile.close();}catch(Exception close){error.addSuppressed(close);}throw new IllegalStateException("AUTHORITY_ACQUIRE_FAILED",error);}}
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE operations(id TEXT PRIMARY KEY, caller TEXT NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL, result TEXT, detail TEXT, created INTEGER NOT NULL, updated INTEGER NOT NULL, evidence TEXT)");
        db.execSQL("CREATE TABLE spaces(id TEXT PRIMARY KEY, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE installs(space TEXT NOT NULL REFERENCES spaces(id), package TEXT NOT NULL, generation TEXT UNIQUE NOT NULL, apk TEXT NOT NULL, sha256 TEXT NOT NULL, descriptorSha TEXT NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL, version INTEGER NOT NULL, signer TEXT NOT NULL, dataId TEXT NOT NULL, operation TEXT NOT NULL, PRIMARY KEY(space,package))");
        InstallProviderIndex.create(db);
        db.execSQL("CREATE TABLE sessions(generation TEXT PRIMARY KEY, space TEXT NOT NULL, package TEXT NOT NULL, installGeneration TEXT NOT NULL, pid INTEGER NOT NULL, state TEXT NOT NULL, processName TEXT, activityStub TEXT, authorityGeneration TEXT, hookOperation TEXT, closeRequested INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE project_refs(id TEXT PRIMARY KEY, space TEXT NOT NULL, package TEXT NOT NULL, generation TEXT NOT NULL, sourceSha TEXT NOT NULL, snapshot TEXT NOT NULL, state TEXT NOT NULL, operation TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'MIGRATED')");
        ActivityLedger.create(db);ActivityComponentLedger.create(db);ActivityAffinityLedger.create(db);ActivityEntryLedger.create(db);ActivityLaunchLedger.create(db);ServiceLedger.create(db);ProviderLedger.create(db);BroadcastLedger.create(db);ApplicationStartLedger.create(db);InstallHistory.create(db);JobLedger.create(db);JobAttemptLedger.create(db);PendingIntentLedger.create(db);
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.enableWriteAheadLogging();db.execSQL("PRAGMA synchronous=NORMAL");db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<1||oldVersion>32||newVersion!=33)throw new IllegalStateException("SCHEMA_VERSION_UNSUPPORTED");
        // Explicit, transactional schema migration. Historical sessions never regain authority.
        if(oldVersion==1){db.execSQL("ALTER TABLE sessions ADD COLUMN processName TEXT");
        db.execSQL("ALTER TABLE sessions ADD COLUMN activityStub TEXT");
        db.execSQL("ALTER TABLE sessions ADD COLUMN authorityGeneration TEXT");}
        if(oldVersion<3)ActivityLedger.create(db);if(oldVersion<4)ServiceLedger.create(db);if(oldVersion<5)ProviderLedger.create(db);if(oldVersion<6)BroadcastLedger.create(db);
        if(oldVersion>=5&&oldVersion<7)db.execSQL("ALTER TABLE provider_calls ADD COLUMN delivery TEXT NOT NULL DEFAULT 'UNKNOWN'");
        if(oldVersion>=3&&oldVersion<8){db.execSQL("ALTER TABLE activity_tickets ADD COLUMN processStub TEXT");db.execSQL("UPDATE activity_tickets SET processStub=stub");}
        if(oldVersion<8)ActivityComponentLedger.create(db);
        if(oldVersion<9)ActivityLaunchLedger.create(db);
        if(oldVersion>=3&&oldVersion<10){db.execSQL("ALTER TABLE activity_tickets ADD COLUMN destroyedConfirmed INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE activity_tickets ADD COLUMN componentLease TEXT");}
        if(oldVersion>=8&&oldVersion<10){
            db.execSQL("ALTER TABLE activity_components ADD COLUMN lease TEXT");
            db.execSQL("UPDATE activity_components SET lease=lower(hex(randomblob(16)))");
            db.execSQL("CREATE UNIQUE INDEX activity_component_lease ON activity_components(lease)");
            db.execSQL("UPDATE activity_tickets SET componentLease=(SELECT ac.lease FROM activity_components ac WHERE ac.stub=activity_tickets.stub AND ac.space=activity_tickets.space AND ac.package=activity_tickets.package AND ac.installGeneration=activity_tickets.installGeneration AND ac.component=activity_tickets.component)");
        }
        if(oldVersion>=3&&oldVersion<10)db.execSQL("UPDATE activity_tickets SET state='UNKNOWN' WHERE state='REVOKED'");
        if(oldVersion>=3&&oldVersion<11)db.execSQL("ALTER TABLE activity_tickets ADD COLUMN resourceProof TEXT");
        if(oldVersion>=9&&oldVersion<12)ActivityLaunchLedger.migrateSource(db);
        if(oldVersion<13){
            if(oldVersion>=8){db.execSQL("ALTER TABLE activity_components ADD COLUMN affinityLease TEXT");db.execSQL("ALTER TABLE activity_components ADD COLUMN profile TEXT");}
            ActivityAffinityLedger.create(db);ActivityEntryLedger.create(db);
        }
        // Before schema 14, declarations allowing reparenting were rejected before
        // leasing a component. Existing non-null profiles therefore mean false.
        if(oldVersion<14)db.execSQL("UPDATE activity_components SET profile=profile||':false' WHERE profile IS NOT NULL");
        if(oldVersion>=9&&oldVersion<15)db.execSQL("ALTER TABLE activity_launches ADD COLUMN startResult INTEGER");
        if(oldVersion==15)ActivityLaunchLedger.correctStartClassification(db);
        if(oldVersion<17)db.execSQL("ALTER TABLE project_refs ADD COLUMN kind TEXT NOT NULL DEFAULT 'MIGRATED'");
        if(oldVersion<19)db.execSQL("ALTER TABLE sessions ADD COLUMN hookOperation TEXT");
        if(oldVersion<20)db.execSQL("ALTER TABLE sessions ADD COLUMN closeRequested INTEGER NOT NULL DEFAULT 0");
        if(oldVersion>=6&&oldVersion<21){db.execSQL("ALTER TABLE broadcast_deliveries ADD COLUMN resourceRetired INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE broadcast_deliveries ADD COLUMN resourceProof TEXT");}
        if(oldVersion<22)ApplicationStartLedger.create(db);
        if(oldVersion>=6&&oldVersion<23)db.execSQL("ALTER TABLE broadcast_registrations ADD COLUMN volumeRoots TEXT NOT NULL DEFAULT '[]'");
        if(oldVersion<24)InstallHistory.create(db);
        if(oldVersion>=6&&oldVersion<24)db.execSQL("ALTER TABLE broadcast_registrations ADD COLUMN permission TEXT NOT NULL DEFAULT ''");
        if(oldVersion>=3&&oldVersion<25)db.execSQL("ALTER TABLE activity_tickets ADD COLUMN entryFenceOperation TEXT");
        if(oldVersion>=9&&oldVersion<26)db.execSQL("ALTER TABLE activity_launches ADD COLUMN requestFlags INTEGER");
        if(oldVersion<27)JobLedger.create(db);
        if(oldVersion<28)JobAttemptLedger.create(db);
        if(oldVersion<29){PendingIntentLedger.create(db);if(oldVersion>=6)db.execSQL("ALTER TABLE broadcast_deliveries ADD COLUMN externalOrigin INTEGER NOT NULL DEFAULT 0");}
        // Preserve the actual route identity of tokens created by schema 29;
        // cancellation queries must address that exact existing system token.
        if(oldVersion==29)db.execSQL("ALTER TABLE pending_intents ADD COLUMN routeAction TEXT NOT NULL DEFAULT 'dev.tripoder.runtime.PENDING_BROADCAST'");
        // Retire the experimental direct-to-slot transport. Its real system
        // identity and UNKNOWN outcomes stay recorded; no old entry may execute
        // a guest after the slot is reused. New tokens use one authority gateway.
        if(oldVersion<31)db.execSQL("UPDATE pending_intents SET fenced=1,currentVersion=0 WHERE processStub!=''");
        if(oldVersion<32)InstallProviderIndex.migrate(db);
        // The controller launch ingress table (schema 18-32) is retired; activity_launches records which
        // operation created each controller root (callerKind CONTROLLER).
        if(oldVersion<33)db.execSQL("DROP TABLE IF EXISTS controller_launches");
    }
    private void requireAuthority(){if(closed||authorityLock==null||!authorityLock.isValid())throw new IllegalStateException("AUTHORITY_CLOSED");}
    @Override public SQLiteDatabase getReadableDatabase(){requireAuthority();return super.getReadableDatabase();}
    @Override public SQLiteDatabase getWritableDatabase(){requireAuthority();return super.getWritableDatabase();}
    @Override public ExecutionContract.Operation read(String id) {
        try (Cursor c=getReadableDatabase().rawQuery("SELECT * FROM operations WHERE id=?",new String[]{id})) { return c.moveToFirst()?decode(c):null; }
    }
    @Override public void write(ExecutionContract.Operation op) {
        ContentValues v=new ContentValues();v.put("id",op.id);v.put("caller",op.callerIdentity);v.put("digest",op.payloadDigest);v.put("state",op.state.name());
        v.put("result",op.result);v.put("detail",op.detail);v.put("created",op.createdAtMs);v.put("updated",op.updatedAtMs);v.put("evidence",op.evidence);
        if(getWritableDatabase().insertWithOnConflict("operations",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("OPERATION_PERSIST_FAILED");
    }
    @Override public List<ExecutionContract.Operation> incomplete() {
        List<ExecutionContract.Operation> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM operations WHERE state IN ('ACCEPTED','RUNNING')",null)) {while(c.moveToNext())out.add(decode(c));}return out;
    }
    private static ExecutionContract.Operation decode(Cursor c) {
        return new ExecutionContract.Operation(c.getString(0),c.getString(1),c.getString(2),ExecutionContract.OperationState.valueOf(c.getString(3)),c.getString(4),c.getString(5),c.getLong(6),c.getLong(7),c.getString(8));
    }
    void reserve(ExecutionContract.Session session,String stub){
        ContentValues row=new ContentValues();row.put("generation",session.processGeneration);row.put("space",session.identity.spaceId);row.put("package",session.identity.packageName);row.put("installGeneration",session.identity.installGeneration);row.put("pid",session.pid);row.put("state",session.state.name());
        row.put("processName",session.identity.processName);row.put("activityStub",stub);row.put("authorityGeneration",authorityGeneration);
        if(getWritableDatabase().insertOrThrow("sessions",null,row)==-1)throw new IllegalStateException("SESSION_RESERVE_FAILED");
        openGenerations.add(session.processGeneration);closingGenerations.remove(session.processGeneration);deadGenerations.remove(session.processGeneration);
    }
    void session(ExecutionContract.Session session){
        ContentValues row=new ContentValues();row.put("pid",session.pid);row.put("state",session.state.name());
        if(getWritableDatabase().update("sessions",row,"generation=? AND authorityGeneration=?",new String[]{session.processGeneration,authorityGeneration})!=1)throw new IllegalStateException("SESSION_PERSIST_FAILED");
    }
    void deadSession(String generation){ContentValues row=new ContentValues();row.put("state","DEAD");getWritableDatabase().update("sessions",row,"generation=?",new String[]{generation});openGenerations.remove(generation);deadGenerations.add(generation);}
    void requestClose(String generation){getWritableDatabase().execSQL("UPDATE sessions SET closeRequested=1 WHERE generation=?",new Object[]{generation});openGenerations.remove(generation);closingGenerations.add(generation);requireClosed(generation);}
    private void requireClosed(String generation){if(!closeRequested(generation))throw new IllegalStateException("PROCESS_CLOSE_NOT_PERSISTED");}
    boolean closeRequested(String generation){if(closingGenerations.contains(generation))return true;try(Cursor c=getReadableDatabase().rawQuery("SELECT closeRequested FROM sessions WHERE generation=?",new String[]{generation})){if(!c.moveToFirst())throw new SecurityException("SESSION_MISSING");boolean closing=c.getInt(0)!=0;if(closing){closingGenerations.add(generation);openGenerations.remove(generation);}return closing;}}
    boolean isDead(String generation){if(deadGenerations.contains(generation))return true;try(Cursor c=getReadableDatabase().rawQuery("SELECT state FROM sessions WHERE generation=?",new String[]{generation})){if(!c.moveToFirst())throw new SecurityException("SESSION_MISSING");boolean dead="DEAD".equals(c.getString(0));if(dead){deadGenerations.add(generation);openGenerations.remove(generation);}return dead;}}
    void requireOpen(String generation){if(closingGenerations.contains(generation))throw new IllegalStateException("PROCESS_CLOSING");if(openGenerations.contains(generation))return;if(closeRequested(generation))throw new IllegalStateException("PROCESS_CLOSING");}
    synchronized void requireExplicitHookRecovery(String generation,String operation){
        var db=getWritableDatabase();db.beginTransaction();try{
            db.execSQL("UPDATE sessions SET hookOperation=COALESCE(hookOperation,?) WHERE generation=?",new Object[]{operation,generation});
            if(!hookRecoveryBlocked(generation))throw new IllegalStateException("HOOK_RECOVERY_POLICY_NOT_PERSISTED");
            new JobLedger(this).hook(generation,operation);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized String hookOperation(String generation){try(Cursor c=getReadableDatabase().rawQuery("SELECT hookOperation FROM sessions WHERE generation=?",new String[]{generation})){if(!c.moveToFirst())throw new SecurityException("RECOVERY_SESSION_MISSING");return c.getString(0);}}
    synchronized boolean hookRecoveryBlocked(String generation){try(Cursor c=getReadableDatabase().rawQuery("SELECT hookOperation FROM sessions WHERE generation=?",new String[]{generation})){if(!c.moveToFirst())throw new SecurityException("RECOVERY_SESSION_MISSING");return !c.isNull(0);}}
    /** Recovery never kills a recorded PID: PID reuse is not identity evidence. */
    synchronized void recoverSessions(Context context){
        openGenerations.clear();closingGenerations.clear();deadGenerations.clear();
        var running=((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
        if(running==null)throw new IllegalStateException("PROCESS_OBSERVATION_UNAVAILABLE");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT generation,pid FROM sessions WHERE state NOT IN ('DEAD','FAILED')",null)){
            while(c.moveToNext())for(var process:running)if(process.pid==c.getInt(1)&&process.uid==android.os.Process.myUid())throw new IllegalStateException("ORPHAN_PROCESS_STILL_LIVE:"+c.getString(0));
        }
        getWritableDatabase().execSQL("UPDATE sessions SET state='DEAD' WHERE state NOT IN ('DEAD','FAILED')");
    }
    @Override public synchronized void close(){
        closed=true;
        try{super.close();}finally{
            synchronized(RuntimeStore.class){try{if(authorityLock!=null){authorityLock.release();authorityLock=null;}if(authorityFile!=null){authorityFile.close();authorityFile=null;}if(authorityKey!=null){LOCAL_AUTHORITIES.remove(authorityKey);authorityKey=null;}}
            catch(java.io.IOException error){throw new IllegalStateException("AUTHORITY_RELEASE_FAILED",error);}
            }
        }
    }
}
