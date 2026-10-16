package dev.tripoder.runtime;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Durable one-use ingress capability; ActivityLedger remains the component execution authority. */
final class ControllerLaunchLedger {
    static final long VALIDITY_MS=30000;
    private final RuntimeBroker broker;
    ControllerLaunchLedger(RuntimeBroker broker){this.broker=broker;}
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE controller_launches(operation TEXT PRIMARY KEY REFERENCES operations(id),tokenHash TEXT UNIQUE NOT NULL,authority TEXT NOT NULL,lease TEXT NOT NULL,uid INTEGER NOT NULL,project TEXT NOT NULL,bindingOperation TEXT NOT NULL,space TEXT NOT NULL,package TEXT NOT NULL,installGeneration TEXT NOT NULL,processGeneration TEXT NOT NULL,component TEXT NOT NULL,launcher INTEGER NOT NULL,deadline INTEGER NOT NULL,state TEXT NOT NULL,ticket TEXT,gatewayTaskId INTEGER NOT NULL DEFAULT -1)");}
    static final class Entry {
        final String operation,hash,authority,lease,project,binding,space,pkg,install,process,component,state,ticket;final int uid;final long deadline;final boolean launcher;final int gatewayTaskId;
        Entry(Cursor c){operation=c.getString(0);hash=c.getString(1);authority=c.getString(2);lease=c.getString(3);uid=c.getInt(4);project=c.getString(5);binding=c.getString(6);space=c.getString(7);pkg=c.getString(8);install=c.getString(9);process=c.getString(10);component=c.getString(11);launcher=c.getInt(12)!=0;deadline=c.getLong(13);state=c.getString(14);ticket=c.getString(15);gatewayTaskId=c.getInt(16);}
    }
    String prepare(String operation,String lease,int uid,Bundle target,RuntimeBroker.Slot slot,String component,boolean launcher)throws Exception{
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);String token=HexFormat.of().formatHex(random);
        ContentValues row=new ContentValues();row.put("operation",operation);row.put("tokenHash",ApkStore.textDigest(token));row.put("authority",broker.store.authorityGeneration);row.put("lease",lease);row.put("uid",uid);row.put("project",target.getString("project"));row.put("bindingOperation",target.getString("bindingOperation"));row.put("space",target.getString("space"));row.put("package",target.getString("package"));row.put("installGeneration",target.getString("generation"));row.put("processGeneration",slot.allocation.session.processGeneration);row.put("component",component);row.put("launcher",launcher?1:0);row.put("deadline",SystemClock.elapsedRealtime()+VALIDITY_MS);row.put("state","READY");
        broker.store.getWritableDatabase().insertOrThrow("controller_launches",null,row);return token;
    }
    Entry get(String operation){try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT * FROM controller_launches WHERE operation=?",new String[]{operation})){return c.moveToFirst()?new Entry(c):null;}}
    void recover(){
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT * FROM controller_launches WHERE authority!=? AND state='READY' AND ticket IS NULL",new String[]{broker.store.authorityGeneration})){
            while(c.moveToNext()){
                Entry entry=new Entry(c);var operation=broker.contract.query(entry.operation);if(operation==null||operation.state!=ExecutionContract.OperationState.UNKNOWN)throw new IllegalStateException("LAUNCH_RECOVERY_STATE_INVALID");
                var db=broker.store.getWritableDatabase();db.beginTransaction();try{
                    broker.contract.reconcile(entry.operation,ExecutionContract.OperationState.FAILED,"LAUNCH_AUTHORITY_RESTARTED_BEFORE_DISPATCH","capability=READY;ticket=null;previousAuthority="+entry.authority+";currentAuthority="+broker.store.authorityGeneration);
                    ContentValues row=new ContentValues();row.put("state","REVOKED");db.update("controller_launches",row,"operation=? AND state='READY' AND ticket IS NULL",new String[]{entry.operation});db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
        }
    }
    void expire(String operation){Entry entry=get(operation);if(entry==null||!"READY".equals(entry.state))return;
        if(!entry.authority.equals(broker.store.authorityGeneration))return;
        if(SystemClock.elapsedRealtime()<=entry.deadline)return;
        var db=broker.store.getWritableDatabase();db.beginTransaction();try{
            if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING)broker.contract.fail(operation,"LAUNCH_CAPABILITY_EXPIRED_BEFORE_DISPATCH");
            ContentValues row=new ContentValues();row.put("state","EXPIRED");db.update("controller_launches",row,"operation=? AND state='READY'",new String[]{operation});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    Intent consume(String operation,String token,int uid,String callerPackage,int gatewayTaskId)throws Exception{
        if(gatewayTaskId<0)throw new SecurityException("GATEWAY_TASK_REQUIRED");
        expire(operation);Entry entry=get(operation);
        if(entry==null||token==null||!token.matches("[a-f0-9]{64}")||!java.security.MessageDigest.isEqual(entry.hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),ApkStore.textDigest(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new SecurityException("LAUNCH_CAPABILITY_REJECTED");
        if(!entry.authority.equals(broker.store.authorityGeneration)||entry.uid!=uid||!"READY".equals(entry.state))throw new SecurityException("LAUNCH_CAPABILITY_STALE_OR_USED");
        if(broker.contract.query(operation).state!=ExecutionContract.OperationState.RUNNING)throw new IllegalStateException("LAUNCH_OPERATION_NOT_RUNNING");
        requireBinding(entry);var slot=broker.requiredSlot(entry.process);
        if(!slot.install.generation.equals(entry.install)||!slot.install.space.equals(entry.space)||!slot.install.info.packageName.equals(entry.pkg)||broker.contract.getSession(entry.process).state!=ExecutionContract.ProcessState.RUNNING)throw new IllegalStateException("LAUNCH_PROCESS_STALE");
        ActivityEntryFences.requireNoOpenRoot(broker.store,entry.space,entry.pkg,entry.install);
        String ticket=broker.activities.prepareWithCommit(slot,entry.component,null,operation,callerPackage,null,null,created->{
            ContentValues row=new ContentValues();row.put("state","DISPATCHED");row.put("gatewayTaskId",gatewayTaskId);
            row.put("ticket",created);if(broker.store.getWritableDatabase().update("controller_launches",row,"operation=? AND state='READY'",new String[]{operation})!=1)throw new SecurityException("LAUNCH_CAPABILITY_ALREADY_USED");
        });
        var activity=broker.activities.get(ticket);Intent intent=new Intent().setComponent(new ComponentName(broker.host,activity.stub)).putExtra("tripoder.component",entry.component).putExtra("tripoder.generation",entry.process).putExtra("tripoder.ticket",ticket).putExtra("tripoder.delivery",broker.activities.launches.forTicket(ticket));
        if(entry.launcher)intent.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);return intent;
    }
    private void requireBinding(Entry entry){
        // Empty project and binding together denote an installation-scoped launch.
        // Only the dedicated application command can create this target.
        if(entry.project.isEmpty()&&entry.binding.isEmpty()){
            var install=broker.apks.row(entry.space,entry.pkg);
            if(install==null||!"COMMITTED".equals(install.state)||!entry.install.equals(install.generation))throw new IllegalStateException("LAUNCH_INSTALL_STALE");return;
        }
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM project_refs p JOIN installs i ON i.space=p.space AND i.package=p.package WHERE p.id=? AND p.operation=? AND p.kind='DIRECT' AND p.state='COMMITTED' AND i.state='COMMITTED' AND p.generation=? AND i.generation=p.generation AND p.space=? AND p.package=?",new String[]{entry.project,entry.binding,entry.install,entry.space,entry.pkg})){if(!c.moveToFirst())throw new IllegalStateException("LAUNCH_BINDING_STALE");}
    }
}
