package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.UUID;

/** One launch delivery is distinct from the Android instance that eventually receives it. */
final class ActivityLaunchLedger {
    private final RuntimeStore store;private final ExecutionContract contract;
    ActivityLaunchLedger(RuntimeStore store,ExecutionContract contract){this.store=store;this.contract=contract;}
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE activity_launches(id TEXT PRIMARY KEY,candidate TEXT UNIQUE NOT NULL,generation TEXT NOT NULL,operation TEXT,expectsResult INTEGER NOT NULL,receiver TEXT,instance TEXT,kind TEXT,state TEXT NOT NULL,callerPackage TEXT NOT NULL,payloadDigest TEXT,sourceTicket TEXT,sourceInstance TEXT,callerComponent TEXT,callerKind TEXT NOT NULL,startResult INTEGER,requestFlags INTEGER)");}
    static void migrateSource(SQLiteDatabase db){
        db.execSQL("ALTER TABLE activity_launches ADD COLUMN sourceTicket TEXT");
        db.execSQL("ALTER TABLE activity_launches ADD COLUMN sourceInstance TEXT");
        db.execSQL("ALTER TABLE activity_launches ADD COLUMN callerComponent TEXT");
        // Missing historical provenance must never be inferred from a shared package/UID.
        db.execSQL("ALTER TABLE activity_launches ADD COLUMN callerKind TEXT NOT NULL DEFAULT 'UNRECORDED'");
    }
    static void correctStartClassification(SQLiteDatabase db){
        // Schema 15's experimental receipt classified result=2 as callback-free for
        // non-standard modes. Preserve the old evidence, withdraw its success claim.
        try(Cursor c=db.rawQuery("SELECT l.id,l.candidate,l.operation,op.result,op.detail FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate LEFT JOIN operations op ON op.id=l.operation WHERE l.state='SYSTEM_CONFIRMED' AND t.stub GLOB '*M[123]C*'",null)){
            while(c.moveToNext()){
                if(!c.isNull(2)){ContentValues op=new ContentValues();op.put("state","UNKNOWN");op.put("detail","schema16:INVALID_TASK_ONLY_RECEIPT;priorResult="+c.getString(3)+";priorDetail="+c.getString(4));op.put("updated",System.currentTimeMillis());db.update("operations",op,"id=?",new String[]{c.getString(2)});}
                ContentValues launch=new ContentValues();launch.put("state","UNKNOWN");db.update("activity_launches",launch,"id=?",new String[]{c.getString(0)});
                ContentValues ticket=new ContentValues();ticket.put("state","UNKNOWN");ticket.put("resourceProof","schema16:INVALID_TASK_ONLY_RECEIPT;no-callback-proof");db.update("activity_tickets",ticket,"ticket=?",new String[]{c.getString(1)});
            }
        }
    }
    static final class Launch {
        final String id,candidate,generation,operation,receiver,instance,kind,state,callerPackage,payloadDigest,sourceTicket,sourceInstance,callerComponent,callerKind;final boolean expectsResult;final Integer startResult,requestFlags;
        Launch(Cursor c){id=c.getString(0);candidate=c.getString(1);generation=c.getString(2);operation=c.getString(3);expectsResult=c.getInt(4)!=0;receiver=c.getString(5);instance=c.getString(6);kind=c.getString(7);state=c.getString(8);callerPackage=c.getString(9);payloadDigest=c.getString(10);sourceTicket=c.getString(11);sourceInstance=c.getString(12);callerComponent=c.getString(13);callerKind=c.getString(14);startResult=c.isNull(15)?null:c.getInt(15);requestFlags=c.isNull(16)?null:c.getInt(16);}
        void caller(android.os.Bundle out){out.putString("callerPackage",callerPackage);out.putString("callerComponent",callerComponent);out.putString("callerKind",callerKind);out.putBoolean("expectsResult",expectsResult);}
    }
    String prepare(String candidate,String generation,String operation,boolean expectsResult,String callerPackage,String payloadDigest,ActivityLedger.Ticket source,int requestFlags){
        String id=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("id",id);row.put("candidate",candidate);row.put("generation",generation);row.put("operation",operation);row.put("expectsResult",expectsResult?1:0);row.put("callerPackage",callerPackage);row.put("payloadDigest",payloadDigest);row.put("state","PREPARED");
        row.put("callerKind",source==null?"CONTROLLER":"ACTIVITY");
        row.put("requestFlags",requestFlags);
        if(source!=null){if(!callerPackage.equals(source.pkg))throw new SecurityException("ACTIVITY_SOURCE_PACKAGE_MISMATCH");row.put("sourceTicket",source.id);row.put("sourceInstance",source.instance);row.put("callerComponent",source.component);}
        store.getWritableDatabase().insertOrThrow("activity_launches",null,row);return id;
    }
    Launch get(String id){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM activity_launches WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new SecurityException("ACTIVITY_DELIVERY_MISSING");return new Launch(c);}}
    String forTicket(String ticket){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM activity_launches WHERE candidate=?",new String[]{ticket})){if(!c.moveToFirst())throw new SecurityException("ACTIVITY_DELIVERY_MISSING");return c.getString(0);}}
    void startResult(String id,int result){Launch launch=get(id);if(launch.startResult!=null)throw new SecurityException("ACTIVITY_START_RESULT_REPLAY");ContentValues row=new ContentValues();row.put("startResult",result);update(id,row);}
    void taskToFront(String id,ActivityLedger.Ticket root){
        Launch launch=get(id);if(!"PREPARED".equals(launch.state)||!(Integer.valueOf(2).equals(launch.startResult)||Integer.valueOf(3).equals(launch.startResult))||launch.expectsResult)throw new SecurityException("ACTIVITY_TASK_FRONT_STATE");
        var db=store.getWritableDatabase();db.beginTransaction();try{
            if(launch.operation!=null&&contract.query(launch.operation).state==ExecutionContract.OperationState.RUNNING)contract.succeed(launch.operation,"android-standard-task-reused;startResult="+launch.startResult+";task="+root.taskId+";root="+root.id);
            ContentValues row=new ContentValues();row.put("state","SYSTEM_CONFIRMED");row.put("kind",launch.startResult==2?"TASK_TO_FRONT":"TASK_ALREADY_FRONT");row.put("receiver",root.id);row.put("instance",root.instance);update(id,row);
            ContentValues ticket=new ContentValues();ticket.put("state","FINISHED");ticket.put("taskId",root.taskId);ticket.put("resourceProof","SYSTEM_TASK_TO_FRONT;root="+root.id);db.update("activity_tickets",ticket,"ticket=?",new String[]{launch.candidate});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void unsupportedStart(String id){unknown(get(id),"ANDROID_START_RESULT_UNSUPPORTED");}
    void enter(String id,ActivityLedger.Ticket candidate,ActivityLedger.Ticket receiver,String instance,String kind,String payloadDigest){
        Launch launch=get(id);if(!launch.candidate.equals(candidate.id)||!launch.generation.equals(candidate.generation)||!launch.state.equals("PREPARED"))throw new SecurityException("ACTIVITY_DELIVERY_NOT_CLAIMABLE");
        if(launch.payloadDigest!=null&&!launch.payloadDigest.equals(payloadDigest))throw new SecurityException("ACTIVITY_PAYLOAD_MISMATCH");
        ContentValues row=new ContentValues();row.put("receiver",receiver.id);row.put("instance",instance);row.put("kind",kind);row.put("state","ENTERED");update(id,row);
    }
    void received(String id,String receiver,String instance){
        Launch launch=get(id);if(!launch.state.equals("ENTERED")||!receiver.equals(launch.receiver)||!instance.equals(launch.instance))throw new SecurityException("ACTIVITY_DELIVERY_ACK_MISMATCH");
        var db=store.getWritableDatabase();db.beginTransaction();try{
            if(launch.operation!=null&&!launch.expectsResult&&contract.query(launch.operation).state==ExecutionContract.OperationState.RUNNING)contract.succeed(launch.operation,"activity-"+launch.kind+"-callback-completed");
            ContentValues row=new ContentValues();row.put("state","RECEIVED");update(id,row);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void failed(String id){Launch launch=get(id);if(!launch.state.equals("ENTERED"))throw new SecurityException("ACTIVITY_DELIVERY_NOT_ENTERED");unknown(launch,"ACTIVITY_CALLBACK_FAILED");}
    void dead(String generation){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM activity_launches WHERE generation=? AND state IN ('PREPARED','ENTERED')",new String[]{generation})){while(c.moveToNext())unknown(new Launch(c),"ACTIVITY_DELIVERY_PROCESS_DIED");}}
    void recover(){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT l.* FROM activity_launches l JOIN sessions s ON l.generation=s.generation WHERE s.state='DEAD' AND l.state IN ('PREPARED','ENTERED')",null)){while(c.moveToNext())unknown(new Launch(c),"ACTIVITY_DELIVERY_RECOVERED_UNKNOWN");}}
    private void unknown(Launch launch,String reason){var db=store.getWritableDatabase();db.beginTransaction();try{
        if(launch.operation!=null&&contract.query(launch.operation).state==ExecutionContract.OperationState.RUNNING)contract.markUnknown(launch.operation,reason);ContentValues row=new ContentValues();row.put("state","UNKNOWN");update(launch.id,row);
        // A reused delivery never created its candidate instance. The real receiver ticket
        // continues to pin the Android task; uncertainty separately protects guest data.
        if("NEW_INTENT".equals(launch.kind)){ContentValues ticket=new ContentValues();ticket.put("state","FINISHED");db.update("activity_tickets",ticket,"ticket=?",new String[]{launch.candidate});}
        db.setTransactionSuccessful();
    }finally{db.endTransaction();}}
    private void update(String id,ContentValues row){if(store.getWritableDatabase().update("activity_launches",row,"id=?",new String[]{id})!=1)throw new IllegalStateException("ACTIVITY_DELIVERY_PERSIST_FAILED");}
}
