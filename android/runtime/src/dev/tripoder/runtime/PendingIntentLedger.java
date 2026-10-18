package dev.tripoder.runtime;

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Durable creator and version ownership, independent of a short-lived process session. */
final class PendingIntentLedger {
    private final RuntimeStore store;
    PendingIntentLedger(RuntimeStore store){this.store=store;}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE pending_intents(version TEXT PRIMARY KEY,identityKey TEXT NOT NULL,space TEXT NOT NULL,package TEXT NOT NULL,installGeneration TEXT NOT NULL,dataId TEXT NOT NULL,processName TEXT NOT NULL,processStub TEXT NOT NULL,receiver TEXT NOT NULL,requestCode INTEGER NOT NULL,flags INTEGER NOT NULL,payload BLOB NOT NULL,state TEXT NOT NULL,fenced INTEGER NOT NULL,currentVersion INTEGER NOT NULL,oneShotConsumed INTEGER NOT NULL DEFAULT 0,hookGeneration TEXT,routeAction TEXT NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX pending_current_identity ON pending_intents(identityKey) WHERE currentVersion=1");
        db.execSQL("CREATE TABLE pending_mutations(operation TEXT PRIMARY KEY,installGeneration TEXT NOT NULL,version TEXT NOT NULL,kind TEXT NOT NULL,closed INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE UNIQUE INDEX pending_mutation_owner ON pending_mutations(installGeneration) WHERE closed=0");
        db.execSQL("CREATE TABLE pending_alarms(operation TEXT PRIMARY KEY,version TEXT NOT NULL,kind TEXT NOT NULL,type INTEGER NOT NULL,triggerMs INTEGER NOT NULL,windowMs INTEGER NOT NULL,intervalMs INTEGER NOT NULL,state TEXT NOT NULL)");
    }
    static final class Version {
        final String version,key,space,pkg,install,data,process,processStub,receiver,state,hookGeneration,routeAction;
        final int requestCode,flags;final byte[] payload;final boolean fenced,current,consumed;
        Version(Cursor c){version=c.getString(0);key=c.getString(1);space=c.getString(2);pkg=c.getString(3);install=c.getString(4);data=c.getString(5);process=c.getString(6);processStub=c.getString(7);receiver=c.getString(8);requestCode=c.getInt(9);flags=c.getInt(10);payload=c.getBlob(11);state=c.getString(12);fenced=c.getInt(13)!=0;current=c.getInt(14)!=0;consumed=c.getInt(15)!=0;hookGeneration=c.getString(16);routeAction=c.getString(17);}
        Intent logical(){return RuntimeBroadcastIntent.decode(payload);}
        /** "broadcast" (receiver column holds the receiver name or ""), or "activity"/"service" (the column holds "<kind>:<component>"). */
        String kind(){return receiver.startsWith("activity:")?"activity":receiver.startsWith("service:")?"service":"broadcast";}
        String component(){return kind().equals("broadcast")?receiver:receiver.substring(receiver.indexOf(':')+1);}
        // Historical physical components remain exact cancellation identities.
        // New tokens always use the authority gateway and reserve no process.
        String stub(){return !processStub.isEmpty()?RuntimeReceiverSlots.type(RuntimeSlot.index(processStub)).getName():kind().equals("activity")?RuntimePendingActivityIngress.class.getName():kind().equals("service")?RuntimePendingServiceIngress.class.getName():RuntimePendingIntentIngress.class.getName();}
    }
    private static final String COLUMNS="version,identityKey,space,package,installGeneration,dataId,processName,processStub,receiver,requestCode,flags,payload,state,fenced,currentVersion,oneShotConsumed,hookGeneration,routeAction";
    Version get(String version){try(var c=store.getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM pending_intents WHERE version=?",new String[]{version})){if(!c.moveToFirst())throw new SecurityException("PENDING_VERSION_UNKNOWN");return new Version(c);}}
    Version current(String key){try(var c=store.getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM pending_intents WHERE identityKey=? AND currentVersion=1",new String[]{key})){return c.moveToFirst()?new Version(c):null;}}
    List<Version> versions(String install){List<Version> out=new ArrayList<>();try(var c=store.getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM pending_intents WHERE installGeneration=? ORDER BY rowid",new String[]{install})){while(c.moveToNext())out.add(new Version(c));}return out;}
    List<Version> retiredDirectRoutes(){List<Version> out=new ArrayList<>();try(var c=store.getReadableDatabase().rawQuery("SELECT "+COLUMNS+" FROM pending_intents WHERE processStub!='' AND fenced=1 AND NOT EXISTS(SELECT 1 FROM pending_mutations m WHERE m.version=pending_intents.version AND m.kind='TRANSPORT_RETIREMENT' AND m.closed=1)",null)){while(c.moveToNext())out.add(new Version(c));}return out;}
    void retiredTransportReceipt(String operation,Version version){ContentValues v=new ContentValues();v.put("operation",operation);v.put("installGeneration",version.install);v.put("version",version.version);v.put("kind","TRANSPORT_RETIREMENT");v.put("closed",1);store.getWritableDatabase().insertOrThrow("pending_mutations",null,v);}
    void prepare(String version,String key,ApkStore.BundleInstall install,String process,String receiver,int requestCode,int flags,byte[] payload,String hookGeneration){
        // The quota bounds live system tokens. Retired (fenced) versions stay as exact cancellation identities that reject late delivery,
        // so they do not consume it; their total remains bounded separately.
        try(var c=store.getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(fenced=0),0) FROM pending_intents WHERE installGeneration=?",new String[]{install.generation})){c.moveToFirst();if(c.getInt(1)>=512||c.getInt(0)>=65536)throw new IllegalStateException("PENDING_VERSION_LIMIT");}
        ContentValues v=new ContentValues();v.put("version",version);v.put("identityKey",key);v.put("space",install.space);v.put("package",install.info.packageName);v.put("installGeneration",install.generation);v.put("dataId",install.data.getName());v.put("processName",process);v.put("processStub","");v.put("receiver",receiver);v.put("requestCode",requestCode);v.put("flags",flags);v.put("payload",payload);v.put("state","PREPARED");v.put("fenced",0);v.put("currentVersion",1);v.put("hookGeneration",hookGeneration);v.put("routeAction",RuntimePendingIntentPayload.ACTION+"."+version);store.getWritableDatabase().insertOrThrow("pending_intents",null,v);
    }
    void retire(String version){ContentValues v=new ContentValues();v.put("fenced",1);v.put("currentVersion",0);update(version,v);}
    void state(String version,String state){ContentValues v=new ContentValues();v.put("state",state);update(version,v);}
    void payload(String version,byte[] payload){ContentValues v=new ContentValues();v.put("payload",payload);update(version,v);}
    void consume(String version){ContentValues v=new ContentValues();v.put("oneShotConsumed",1);if(store.getWritableDatabase().update("pending_intents",v,"version=? AND oneShotConsumed=0 AND fenced=0",new String[]{version})!=1)throw new SecurityException("PENDING_ONE_SHOT_ALREADY_CONSUMED");}
    void requireIdle(String install){try(var c=store.getReadableDatabase().rawQuery("SELECT operation FROM pending_mutations WHERE installGeneration=? AND closed=0",new String[]{install})){if(c.moveToFirst())throw new IllegalStateException("PENDING_MUTATION_OUTCOME_UNCONFIRMED:"+c.getString(0));}}
    void mutation(String operation,String install,String version,String kind){ContentValues v=new ContentValues();v.put("operation",operation);v.put("installGeneration",install);v.put("version",version);v.put("kind",kind);store.getWritableDatabase().insertOrThrow("pending_mutations",null,v);}
    void close(String operation){ContentValues v=new ContentValues();v.put("closed",1);if(store.getWritableDatabase().update("pending_mutations",v,"operation=? AND closed=0",new String[]{operation})!=1)throw new IllegalStateException("PENDING_MUTATION_OWNER_MISSING");}
    private void update(String version,ContentValues values){if(store.getWritableDatabase().update("pending_intents",values,"version=?",new String[]{version})!=1)throw new IllegalStateException("PENDING_LEDGER_WRITE_FAILED");}
}
