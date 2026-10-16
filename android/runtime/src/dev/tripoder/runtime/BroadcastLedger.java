package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.util.*;

/** Single authority durable delivery ownership; never replays an admitted broadcast. */
final class BroadcastLedger {
    private final RuntimeStore store;
    BroadcastLedger(RuntimeStore store){this.store=store;}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE broadcast_registrations(id TEXT PRIMARY KEY,generation TEXT NOT NULL,space TEXT NOT NULL,package TEXT NOT NULL,action TEXT NOT NULL,filter BLOB NOT NULL,exported INTEGER NOT NULL,state TEXT NOT NULL,volumeRoots TEXT NOT NULL,permission TEXT NOT NULL)");
        db.execSQL("CREATE TABLE broadcast_deliveries(ticket TEXT PRIMARY KEY,operation TEXT UNIQUE NOT NULL,source TEXT NOT NULL,sourceIdentity TEXT NOT NULL,target TEXT NOT NULL,component TEXT NOT NULL,registration TEXT NOT NULL,payload BLOB NOT NULL,digest TEXT NOT NULL,ordered INTEGER NOT NULL,state TEXT NOT NULL,instance TEXT NOT NULL,returned INTEGER NOT NULL,finished INTEGER NOT NULL,asynchronous INTEGER NOT NULL,systemDone INTEGER NOT NULL,resourceRetired INTEGER NOT NULL DEFAULT 0,resourceProof TEXT)");
    }
    static String token(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    String registration(ExecutionContract.Session source,byte[] filter,boolean exported,Set<String> roots,String permission){String id=token();ContentValues v=new ContentValues();v.put("id",id);v.put("generation",source.processGeneration);v.put("space",source.identity.spaceId);v.put("package",source.identity.packageName);v.put("action",action(id));v.put("filter",filter);v.put("exported",exported?1:0);v.put("state","PREPARED");v.put("volumeRoots",new org.json.JSONArray(roots).toString());v.put("permission",permission==null?"":permission);store.getWritableDatabase().insertOrThrow("broadcast_registrations",null,v);return id;}
    static String action(String id){return "dev.tripoder.runtime.broadcast."+id;}
    boolean registrationActive(String registration,String generation){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT state FROM broadcast_registrations WHERE id=? AND generation=?",new String[]{registration,generation})){
            if(!c.moveToFirst())throw new SecurityException("RECEIVER_REGISTRATION_UNKNOWN");
            String state=c.getString(0);if(state.equals("ACTIVE"))return true;if(state.equals("CLOSED"))return false;
            throw new SecurityException("RECEIVER_REGISTRATION_NOT_ACTIVE");
        }
    }
    void closePending(String registration,String generation,ExecutionContract contract){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT ticket,operation FROM broadcast_deliveries WHERE registration=? AND target=? AND state='PREPARED'",new String[]{registration,generation})){
            while(c.moveToNext()){ContentValues v=new ContentValues();v.put("state","UNKNOWN");update(c.getString(0),v);contract.markUnknown(c.getString(1),"BROADCAST_REGISTRATION_CLOSED_BEFORE_CLAIM");}
        }
    }
    byte[] activeFilter(String registration,String generation){try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT filter FROM broadcast_registrations WHERE id=? AND generation=? AND state='ACTIVE'",new String[]{registration,generation})){if(!cursor.moveToFirst())throw new SecurityException("RECEIVER_REGISTRATION_CLOSED");return cursor.getBlob(0);}}
    Set<String> activeRoots(String registration,String generation)throws Exception{try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT volumeRoots FROM broadcast_registrations WHERE id=? AND generation=? AND state='ACTIVE'",new String[]{registration,generation})){if(!cursor.moveToFirst())throw new SecurityException("RECEIVER_REGISTRATION_CLOSED");org.json.JSONArray roots=new org.json.JSONArray(cursor.getString(0));Set<String> result=new HashSet<>();for(int i=0;i<roots.length();i++)result.add(roots.getString(i));return Set.copyOf(result);}}
    void rememberRoots(String registration,String generation,Set<String> roots){if(roots.size()>64)throw new IllegalStateException("REGISTRATION_VOLUME_LIMIT");ContentValues values=new ContentValues();values.put("volumeRoots",new org.json.JSONArray(roots).toString());if(store.getWritableDatabase().update("broadcast_registrations",values,"id=? AND generation=? AND state='ACTIVE'",new String[]{registration,generation})!=1)throw new SecurityException("RECEIVER_REGISTRATION_CLOSED");}
    String prepareSystem(String operation,ExecutionContract.Session target,String registration,byte[] payload)throws Exception{
        String ticket=prepare(operation,target,target.processGeneration,"",registration,payload,false);
        ContentValues v=new ContentValues();v.put("source","");v.put("sourceIdentity","PLATFORM_PROTECTED:"+RuntimeBroadcastIntent.decode(payload).getAction());update(ticket,v);return ticket;
    }
    void registrationState(String id,String generation,String prior,String next){ContentValues v=new ContentValues();v.put("state",next);if(store.getWritableDatabase().update("broadcast_registrations",v,"id=? AND generation=? AND state=?",new String[]{id,generation,prior})!=1)throw new SecurityException("RECEIVER_REGISTRATION_TRANSITION");}
    List<Bundle> registrations(String space){List<Bundle> out=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,generation,package,filter,exported,permission FROM broadcast_registrations WHERE space=? AND state='ACTIVE'",new String[]{space})){while(c.moveToNext()){Bundle b=new Bundle();b.putString("registration",c.getString(0));b.putString("target",c.getString(1));b.putString("package",c.getString(2));b.putByteArray("filter",c.getBlob(3));b.putBoolean("exported",c.getInt(4)!=0);b.putString("permission",c.getString(5));out.add(b);}}return out;}
    String prepare(String operation,ExecutionContract.Session source,String target,String component,String registration,byte[] payload,boolean ordered)throws Exception{
        String ticket=token();ContentValues v=new ContentValues();v.put("ticket",ticket);v.put("operation",operation);v.put("source",source.processGeneration);v.put("sourceIdentity",source.identity.key());v.put("target",target);v.put("component",component);v.put("registration",registration);v.put("payload",payload);v.put("digest",RuntimeBroadcastIntent.digest(payload));v.put("ordered",ordered?1:0);v.put("state","PREPARED");v.put("instance","");for(String key:List.of("returned","finished","asynchronous","systemDone"))v.put(key,0);store.getWritableDatabase().insertOrThrow("broadcast_deliveries",null,v);return ticket;
    }
    Bundle get(String ticket){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT operation,source,sourceIdentity,target,component,registration,payload,digest,ordered,state,instance,returned,finished,asynchronous,systemDone FROM broadcast_deliveries WHERE ticket=?",new String[]{ticket})){
        if(!c.moveToFirst())throw new SecurityException("BROADCAST_TICKET_UNKNOWN");Bundle b=new Bundle();b.putString("ticket",ticket);String[] names={"operation","source","sourceIdentity","target","component","registration"};for(int i=0;i<names.length;i++)b.putString(names[i],c.getString(i));b.putByteArray("payload",c.getBlob(6));b.putString("digest",c.getString(7));b.putBoolean("ordered",c.getInt(8)!=0);b.putString("state",c.getString(9));b.putString("instance",c.getString(10));for(int i=0;i<4;i++)b.putBoolean(new String[]{"returned","finished","asynchronous","systemDone"}[i],c.getInt(11+i)!=0);return b;
    }}
    Bundle claim(String ticket,String generation,String component,String registration,String instance)throws Exception{
        Bundle b=get(ticket);if(!b.getString("target").equals(generation)||!b.getString("component").equals(component)||!b.getString("registration").equals(registration))throw new SecurityException("BROADCAST_TICKET_NOT_CLAIMABLE");
        if(!RuntimeBroadcastIntent.digest(b.getByteArray("payload")).equals(b.getString("digest")))throw new SecurityException("BROADCAST_PAYLOAD_CORRUPT");
        if(!registration.isEmpty()&&!registrationActive(registration,generation)){b.putBoolean("rejectedClosedRegistration",true);return b;}
        if(!b.getString("state").equals("PREPARED"))throw new SecurityException("BROADCAST_TICKET_NOT_CLAIMABLE");
        ContentValues v=new ContentValues();v.put("state","RUNNING");v.put("instance",instance);update(ticket,v);return b;
    }
    void event(String ticket,String generation,String instance,String event,ExecutionContract contract){
        Bundle b=get(ticket);boolean system=event.equals("systemDone");
        if(!generation.equals(b.getString(system?"source":"target"))||!system&&!instance.equals(b.getString("instance")))throw new SecurityException("BROADCAST_EVENT_OWNER_MISMATCH");
        if(system&&!b.getBoolean("ordered"))throw new SecurityException("BROADCAST_NOT_ORDERED");
        if(!Set.of("returned","finished","asynchronous","systemDone","failed").contains(event)||b.getString("state").equals("COMPLETED"))throw new IllegalStateException("BROADCAST_EVENT_TRANSITION");
        if(event.equals("failed")){ContentValues v=new ContentValues();v.put("state","UNKNOWN");update(ticket,v);contract.markUnknown(b.getString("operation"),"BROADCAST_CALLBACK_FAILED_EFFECT_UNKNOWN");return;}
        if(b.getBoolean(event))throw new IllegalStateException("BROADCAST_DUPLICATE_EVENT");
        ContentValues v=new ContentValues();v.put(event,1);b.putBoolean(event,true);
        if(retired(ticket)){
            if(!system)throw new SecurityException("BROADCAST_RESOURCE_RETIRED");
            // Android finishing an old physical delivery proves no guest effect.
            // Preserve UNKNOWN and never manufacture returned/finished receipts.
            update(ticket,v);return;
        }
        if(b.getBoolean("returned")&&b.getBoolean("finished")){
            if(!b.getBoolean("ordered")||b.getBoolean("systemDone")){
                v.put("state","COMPLETED");String op=b.getString("operation");String result=b.getBoolean("ordered")?"ANDROID_ORDERED_RESULT_OBSERVED":"RECEIVER_CALLBACK_AND_FINISH_RETURNED";
                if(contract.query(op).state==ExecutionContract.OperationState.UNKNOWN)contract.reconcile(op,ExecutionContract.OperationState.SUCCEEDED,result,"ticket="+ticket+";returned=true;finished=true;systemDone="+b.getBoolean("systemDone"));else contract.succeed(op,result);
            }else v.put("state","FINISH_REQUESTED");
        }
        update(ticket,v);
    }
    void dead(String generation,ExecutionContract contract){
        ContentValues closed=new ContentValues();closed.put("state","CLOSED");store.getWritableDatabase().update("broadcast_registrations",closed,"generation=?",new String[]{generation});
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT ticket,operation FROM broadcast_deliveries WHERE target=? AND state NOT IN ('COMPLETED','UNKNOWN')",new String[]{generation})){while(c.moveToNext()){ContentValues v=new ContentValues();v.put("state","UNKNOWN");update(c.getString(0),v);contract.markUnknown(c.getString(1),"BROADCAST_TARGET_DIED_DELIVERY_UNCONFIRMED");}}
        store.getWritableDatabase().execSQL("UPDATE broadcast_deliveries SET resourceRetired=1,resourceProof='TARGET_DEAD_AND_INGRESS_FENCED' WHERE target=? AND state IN ('UNKNOWN','COMPLETED') AND EXISTS(SELECT 1 FROM sessions s WHERE s.generation=target AND s.state='DEAD') AND (registration='' OR EXISTS(SELECT 1 FROM broadcast_registrations r WHERE r.id=registration AND r.generation=target AND r.state='CLOSED'))",new Object[]{generation});
    }
    void recover(ExecutionContract contract){List<String> targets=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT target FROM broadcast_deliveries UNION SELECT DISTINCT generation FROM broadcast_registrations",null)){while(c.moveToNext())targets.add(c.getString(0));}for(String target:targets)dead(target,contract);}
    boolean occupied(String stub){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM broadcast_deliveries b JOIN sessions s ON b.target=s.generation WHERE s.activityStub=? AND b.state!='COMPLETED' AND b.resourceRetired=0 LIMIT 1",new String[]{stub})){return c.moveToFirst();}}
    boolean retired(String ticket){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT resourceRetired FROM broadcast_deliveries WHERE ticket=?",new String[]{ticket})){if(!c.moveToFirst())throw new SecurityException("BROADCAST_TICKET_UNKNOWN");return c.getInt(0)!=0;}}
    /** Called on every cold or warm physical Factory ingress, before any guest code. */
    boolean rejectRetiredEntry(String ticket,int index){
        Bundle delivery=get(ticket);if(delivery.getString("component").isEmpty()||!delivery.getString("registration").isEmpty())throw new SecurityException("BROADCAST_STATIC_COMPONENT_REQUIRED");
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT state,activityStub FROM sessions WHERE generation=?",new String[]{delivery.getString("target")})){
            if(!c.moveToFirst()||!RuntimeSlot.activity(index).getName().equals(c.getString(1)))throw new SecurityException("BROADCAST_ENTRY_SLOT_MISMATCH");
            if(retired(ticket)){if(!"DEAD".equals(c.getString(0)))throw new SecurityException("BROADCAST_RETIREMENT_PROOF_INVALID");return true;}
            if("DEAD".equals(c.getString(0)))throw new SecurityException("BROADCAST_DEAD_WITHOUT_RETIREMENT");
            return false;
        }
    }
    private void update(String ticket,ContentValues v){if(store.getWritableDatabase().update("broadcast_deliveries",v,"ticket=?",new String[]{ticket})!=1)throw new IllegalStateException("BROADCAST_LEDGER_WRITE_FAILED");}
}
