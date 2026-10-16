package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.util.*;

/** Durable component publications and client references. Access only under the authority lock. */
final class ProviderLedger {
    private final RuntimeStore store;
    ProviderLedger(RuntimeStore store){this.store=store;}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE provider_leases(id TEXT PRIMARY KEY, generation TEXT NOT NULL, component TEXT NOT NULL, state TEXT NOT NULL, UNIQUE(generation,component))");
        db.execSQL("CREATE TABLE provider_acquisitions(cap TEXT PRIMARY KEY, source TEXT NOT NULL, lease TEXT NOT NULL, authority TEXT NOT NULL, stable INTEGER NOT NULL, state TEXT NOT NULL)");
        db.execSQL("CREATE TABLE provider_calls(operation TEXT PRIMARY KEY, cap TEXT NOT NULL, kind TEXT NOT NULL, uri TEXT NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL, delivery TEXT NOT NULL)");
        db.execSQL("CREATE INDEX provider_refs ON provider_acquisitions(lease,state)");
    }
    void reserve(RuntimeBroker.Slot slot){
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
            for(var component:new RuntimeProviderIndex(slot.install.info.providers,slot.install.components()).publications(slot.allocation.session.identity.processName)){
                ContentValues v=new ContentValues();v.put("id",UUID.randomUUID().toString());v.put("generation",slot.allocation.session.processGeneration);v.put("component",component.name);v.put("state","DECLARED");db.insertOrThrow("provider_leases",null,v);
            }db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    Bundle publications(String generation){Bundle out=new Bundle();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT component,id FROM provider_leases WHERE generation=?",new String[]{generation})){while(c.moveToNext())out.putString(c.getString(0),c.getString(1));}return out;}
    Bundle local(RuntimeBroker.Slot slot,String component,boolean existing){
        String generation=slot.allocation.session.processGeneration;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM provider_leases WHERE generation=? AND component=?",new String[]{generation,component})){if(c.moveToFirst())return lease(c.getString(0));}
        if(existing)return null;
        var info=new RuntimeProviderIndex(slot.install.info.providers,slot.install.components()).component(component);
        if(!info.enabled||!info.multiprocess||!info.packageName.equals(slot.install.info.packageName))throw new SecurityException("LOCAL_PROVIDER_DECLARATION_INVALID");
        String id=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("id",id);row.put("generation",generation);row.put("component",component);row.put("state","DECLARED");store.getWritableDatabase().insertOrThrow("provider_leases",null,row);return lease(id);
    }
    Bundle lease(String id){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT generation,component,state FROM provider_leases WHERE id=?",new String[]{id})){
        if(!c.moveToFirst())throw new SecurityException("PROVIDER_LEASE_UNKNOWN");Bundle b=new Bundle();b.putString("lease",id);b.putString("target",c.getString(0));b.putString("component",c.getString(1));b.putString("publication",c.getString(2));return b;
    }}
    Bundle find(String generation,String component){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM provider_leases WHERE generation=? AND component=?",new String[]{generation,component})){if(!c.moveToFirst())throw new SecurityException("PROVIDER_NOT_RESERVED");return lease(c.getString(0));}}
    void publication(String id,String generation,String state){
        Bundle b=lease(id);if(!generation.equals(b.getString("target")))throw new SecurityException("PROVIDER_GENERATION_MISMATCH");
        String prior=b.getString("publication");if(!(prior.equals("DECLARED")&&state.equals("INITIALIZING")||prior.equals("INITIALIZING")&&(state.equals("READY")||state.equals("FAILED"))))throw new IllegalStateException("PROVIDER_PUBLICATION_TRANSITION");
        update("provider_leases","state",state,"id=?",id);
    }
    String acquire(String source,Bundle lease,String authority,boolean stable){
        byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String cap=HexFormat.of().formatHex(bytes);
        ContentValues v=new ContentValues();v.put("cap",cap);v.put("source",source);v.put("lease",lease.getString("lease"));v.put("authority",authority);v.put("stable",stable?1:0);v.put("state","OPEN");store.getWritableDatabase().insertOrThrow("provider_acquisitions",null,v);return cap;
    }
    Bundle acquisition(String cap){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT source,lease,authority,stable,state FROM provider_acquisitions WHERE cap=?",new String[]{cap})){
        if(!c.moveToFirst())throw new SecurityException("PROVIDER_CAPABILITY_UNKNOWN");Bundle b=lease(c.getString(1));b.putString("cap",cap);b.putString("source",c.getString(0));b.putString("authority",c.getString(2));b.putBoolean("stable",c.getInt(3)==1);b.putString("reference",c.getString(4));return b;
    }}
    void release(String cap,String source){Bundle b=acquisition(cap);if(!source.equals(b.getString("source")))throw new SecurityException("PROVIDER_SOURCE_MISMATCH");if(!b.getString("reference").equals("OPEN"))throw new IllegalStateException("PROVIDER_ALREADY_RELEASED");update("provider_acquisitions","state","CLOSED","cap=?",cap);ContentValues v=new ContentValues();v.put("delivery","UNKNOWN");store.getWritableDatabase().update("provider_calls",v,"cap=? AND delivery='PENDING'",new String[]{cap});}
    void begin(String operation,String cap,String kind,String uri,String digest){ContentValues v=new ContentValues();v.put("operation",operation);v.put("cap",cap);v.put("kind",kind);v.put("uri",uri);v.put("digest",digest);v.put("state","PREPARED");v.put("delivery","PENDING");store.getWritableDatabase().insertOrThrow("provider_calls",null,v);}
    Bundle invocation(String operation){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT cap,kind,uri,state,digest,delivery FROM provider_calls WHERE operation=?",new String[]{operation})){
        if(!c.moveToFirst())throw new SecurityException("PROVIDER_OPERATION_UNKNOWN");Bundle b=acquisition(c.getString(0));b.putString("operation",operation);b.putString("kind",c.getString(1));b.putString("uri",c.getString(2));b.putString("invocation",c.getString(3));b.putString("digest",c.getString(4));b.putString("delivery",c.getString(5));return b;
    }}
    void received(Bundle invocation){if(invocation.getString("delivery").equals("RECEIVED"))return;update("provider_calls","delivery","RECEIVED","operation=? AND delivery='PENDING' AND state='DONE'",invocation.getString("operation"));}
    void claimed(String operation){update("provider_calls","state","RUNNING","operation=? AND state='PREPARED'",operation);}
    void finished(String operation,boolean success){update("provider_calls","state",success?"DONE":"UNKNOWN","operation=? AND state='RUNNING'",operation);}
    void dead(String generation,ExecutionContract contract){
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();try{
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT pc.operation,pc.state,pc.kind FROM provider_calls pc JOIN provider_acquisitions pa ON pc.cap=pa.cap JOIN provider_leases pl ON pl.id=pa.lease WHERE (pa.source=? OR pl.generation=?) AND pc.state IN ('PREPARED','RUNNING')",new String[]{generation,generation})){
            while(c.moveToNext()){
                String operation=c.getString(0);boolean dispatched=c.getString(1).equals("RUNNING")&&!c.getString(2).equals("ready");
                // Authority observed death before claim: guest callback provably never entered.
                update("provider_calls","state",dispatched?"UNKNOWN":"DONE","operation=?",operation);
                if(contract.query(operation).state==ExecutionContract.OperationState.RUNNING){if(dispatched)contract.markUnknown(operation,"PROVIDER_PROCESS_DIED_DURING_CALLBACK");else contract.fail(operation,"PROVIDER_DIED_WITHOUT_GUEST_CALLBACK");}
                else if(!dispatched&&contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN)contract.reconcile(operation,ExecutionContract.OperationState.FAILED,"PROVIDER_DIED_WITHOUT_GUEST_CALLBACK","provider-call="+operation+";observed-state="+c.getString(1)+";kind="+c.getString(2)+";dead-generation="+generation);
            }
        }
        ContentValues v=new ContentValues();v.put("state","CLOSED");store.getWritableDatabase().update("provider_acquisitions",v,"source=?",new String[]{generation});
        v.clear();v.put("delivery","UNKNOWN");store.getWritableDatabase().update("provider_calls",v,"delivery='PENDING' AND cap IN (SELECT cap FROM provider_acquisitions WHERE source=?)",new String[]{generation});
        db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    void recover(ExecutionContract contract){List<String> generations=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT generation FROM provider_leases UNION SELECT DISTINCT source FROM provider_acquisitions",null)){while(c.moveToNext())generations.add(c.getString(0));}for(String generation:generations)dead(generation,contract);}
    // UNKNOWN business effects retain installation/data, not a physical process whose death is confirmed.
    // Open client/Cursor references still retain the stub; RuntimeCatalog independently checks UNKNOWN.
    boolean occupied(String stub){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM provider_leases pl JOIN sessions s ON pl.generation=s.generation JOIN provider_acquisitions pa ON pa.lease=pl.id LEFT JOIN provider_calls pc ON pc.cap=pa.cap WHERE s.activityStub=? AND (pa.state='OPEN' OR (s.state!='DEAD' AND pc.state IN ('RUNNING','UNKNOWN'))) LIMIT 1",new String[]{stub})){return c.moveToFirst();}}
    private void update(String table,String key,String value,String where,String arg){ContentValues v=new ContentValues();v.put(key,value);if(store.getWritableDatabase().update(table,v,where,new String[]{arg})!=1)throw new IllegalStateException("PROVIDER_LEDGER_TRANSITION");}
}
