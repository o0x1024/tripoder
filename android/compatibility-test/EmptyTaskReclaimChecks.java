package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.*;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Scratch SQLite + constructed platform observations; never removes a real task. */
final class EmptyTaskReclaimChecks {
    private final BiConsumer<String,Boolean> check;
    EmptyTaskReclaimChecks(BiConsumer<String,Boolean> check){this.check=check;}
    void run(Context owned)throws Exception{
        RuntimeBroker broker=new RuntimeBroker(owned);
        try{
            var db=broker.store.getWritableDatabase();String space="owned-empty",pkg="dev.tripoder.owned.empty",install=UUID.randomUUID().toString(),generation=UUID.randomUUID().toString(),ticket=UUID.randomUUID().toString(),delivery=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
            String stub=RuntimeSlot.activity(6).getName();ComponentName physical=new ComponentName(owned,stub);String[] removal={space,pkg,install};
            db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName) VALUES(?,?,?,?,0,'DEAD',?)",new Object[]{generation,space,pkg,install,pkg});
            db.execSQL("INSERT INTO activity_tickets(ticket,space,package,installGeneration,processName,stub,component,generation,state,instance,taskId,processStub,resourceProof) VALUES(?,?,?,?,?,?,?,?,'RETIRED',?,-1,?,'OWNED_OLD_PROOF')",new Object[]{ticket,space,pkg,install,pkg,stub,pkg+".Target",generation,ticket,stub});
            broker.contract.accept(operation,"owned-task-model","a".repeat(64));broker.contract.markUnknown(operation,"OWNED_ORIGINAL_UNKNOWN");
            db.execSQL("INSERT INTO activity_launches(id,candidate,generation,operation,expectsResult,state,callerPackage,callerKind) VALUES(?,?,?,?,0,'UNKNOWN',?,'CONTEXT')",new Object[]{delivery,ticket,generation,operation,pkg});
            ActivityManager.RecentTaskInfo observed=new ActivityManager.RecentTaskInfo();observed.taskId=93401;observed.numActivities=0;
            observed.baseIntent=new Intent().setComponent(physical).putExtra("tripoder.ticket",ticket).putExtra("tripoder.component",pkg+".Target").putExtra("tripoder.delivery",delivery).putExtra("tripoder.generation",generation);
            Method plan=RuntimeEmptyTasks.class.getDeclaredMethod("plan",RuntimeBroker.class,ActivityManager.RecentTaskInfo.class,String.class,String[].class);plan.setAccessible(true);
            check.accept("empty-authenticated-task-with-thin-instance-and-no-ledger-task-id-is-reclaimable",plan.invoke(null,broker,observed,null,removal)!=null);
            check.accept("automatic-empty-reclaim-uses-same-positive-root-proof",plan.invoke(null,broker,observed,null,null)!=null);
            db.execSQL("UPDATE activity_tickets SET instance=NULL WHERE ticket=?",new Object[]{ticket});
            check.accept("empty-authenticated-task-without-instance-keeps-existing-reclaim-contract",plan.invoke(null,broker,observed,null,removal)!=null);
            observed.numActivities=1;observed.baseActivity=physical;observed.topActivity=physical;
            check.accept("unacknowledged-nonempty-task-is-never-inferred-owned-by-instance",plan.invoke(null,broker,observed,null,removal)==null);
            observed.numActivities=0;observed.baseActivity=null;observed.topActivity=null;
            db.execSQL("UPDATE activity_tickets SET instance=?,taskId=93402 WHERE ticket=?",new Object[]{ticket,ticket});
            check.accept("empty-task-cannot-replace-another-acknowledged-task-id",plan.invoke(null,broker,observed,null,removal)==null);
            db.execSQL("UPDATE activity_tickets SET taskId=-1 WHERE ticket=?",new Object[]{ticket});
            for(String key:new String[]{"tripoder.delivery","tripoder.component","tripoder.generation"}){
                String original=observed.baseIntent.getStringExtra(key);observed.baseIntent.putExtra(key,"owned-unrelated");
                check.accept("empty-task-reclaim-requires-exact-root-"+key,plan.invoke(null,broker,observed,null,removal)==null);observed.baseIntent.putExtra(key,original);
            }
            observed.baseIntent.setComponent(new ComponentName("dev.tripoder.foreign",stub));
            check.accept("empty-task-reclaim-requires-real-host-component-package",plan.invoke(null,broker,observed,null,removal)==null);observed.baseIntent.setComponent(physical);
            observed.baseIntent.setComponent(new ComponentName(owned,stub+"Other"));
            check.accept("empty-task-reclaim-requires-exact-ticket-physical-stub",plan.invoke(null,broker,observed,null,removal)==null);observed.baseIntent.setComponent(physical);
            for(String state:new String[]{"RUNNING","FAILED"}){
                db.execSQL("UPDATE sessions SET state=? WHERE generation=?",new Object[]{state,generation});
                check.accept("empty-task-reclaim-rejects-owner-state-"+state,plan.invoke(null,broker,observed,null,removal)==null);
            }
            db.execSQL("UPDATE sessions SET state='DEAD' WHERE generation=?",new Object[]{generation});
            String other=UUID.randomUUID().toString();db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName) VALUES(?,?,?,?,0,'RUNNING',?)",new Object[]{other,space,pkg,install,pkg+":remote"});
            check.accept("empty-task-reclaim-rejects-any-live-installation-process",plan.invoke(null,broker,observed,null,removal)==null);
            db.execSQL("UPDATE sessions SET state='DEAD' WHERE generation=?",new Object[]{other});
            check.accept("empty-task-reclaim-cannot-cross-removal-scope",plan.invoke(null,broker,observed,null,new String[]{"other-space",pkg,install})==null);
            check.accept("automatic-reclaim-cannot-cross-requested-process-generation",plan.invoke(null,broker,observed,"other-generation",null)==null);
            db.execSQL("UPDATE activity_tickets SET taskId=? WHERE ticket=?",new Object[]{observed.taskId,ticket});observed.numActivities=1;observed.baseActivity=physical;observed.topActivity=physical;
            check.accept("explicit-stop-keeps-fully-attached-dead-task-removal-contract",plan.invoke(null,broker,observed,null,removal)!=null);
            check.accept("automatic-reclaim-never-removes-a-nonempty-task",plan.invoke(null,broker,observed,null,null)==null);
            observed.topActivity=new ComponentName("dev.tripoder.foreign","dev.tripoder.foreign.Activity");
            check.accept("explicit-stop-cannot-remove-foreign-top-activity",plan.invoke(null,broker,observed,null,removal)==null);
            observed.numActivities=0;observed.baseActivity=null;observed.topActivity=null;db.execSQL("UPDATE activity_tickets SET taskId=-1 WHERE ticket=?",new Object[]{ticket});
            String alien=UUID.randomUUID().toString();db.execSQL("INSERT INTO activity_tickets(ticket,space,package,installGeneration,processName,stub,component,generation,state,taskId,processStub) VALUES(?,'foreign-space',?,?,?,?,?,?,'RETIRED',?,?)",new Object[]{alien,pkg,install,pkg,stub,pkg+".Target",generation,observed.taskId,stub});
            check.accept("empty-task-reclaim-rejects-cross-scope-ticket-sharing-task",plan.invoke(null,broker,observed,null,removal)==null);db.delete("activity_tickets","ticket=?",new String[]{alien});
            check.accept("planning-reclaim-preserves-original-unknown-and-unacknowledged-ticket",broker.contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN&&broker.contract.query(operation).detail.equals("OWNED_ORIGINAL_UNKNOWN")&&broker.activities.get(ticket).taskId==-1&&broker.activities.get(ticket).resourceProof.equals("OWNED_OLD_PROOF"));
            check.accept("restored-complete-empty-task-proof-remains-reclaimable",plan.invoke(null,broker,observed,null,removal)!=null);
            recordedControllerRoot(broker,owned,plan);
        }finally{broker.store.close();}
    }
    private void recordedControllerRoot(RuntimeBroker broker,Context owned,Method plan)throws Exception{
        var db=broker.store.getWritableDatabase();String space="owned-recorded-root",pkg="dev.tripoder.owned.recorded",install=UUID.randomUUID().toString(),generation=UUID.randomUUID().toString(),ticket=UUID.randomUUID().toString(),delivery=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString(),lease=UUID.randomUUID().toString();
        String stub=RuntimeActivitySlots.name(5,3,0,0,false),component=pkg+".Root";ComponentName physical=new ComponentName(owned,stub);String[] removal={space,pkg,install};
        db.execSQL("INSERT INTO sessions(generation,space,package,installGeneration,pid,state,processName) VALUES(?,?,?,?,0,'DEAD',?)",new Object[]{generation,space,pkg,install,pkg});
        db.execSQL("INSERT INTO activity_components(stub,space,package,installGeneration,processName,processStub,component,generation,lease,profile) VALUES(?,?,?,?,?,?,?,?,?,'0:owned:false')",new Object[]{stub,space,pkg,install,pkg,stub,component,generation,lease});
        db.execSQL("INSERT INTO activity_tickets(ticket,space,package,installGeneration,processName,stub,component,generation,state,instance,taskId,processStub,componentLease,resourceProof) VALUES(?,?,?,?,?,?,?,?,'LIVE',?,93411,?,?,'OWNED_TASK_ATTACHED')",new Object[]{ticket,space,pkg,install,pkg,stub,component,generation,ticket,stub,lease});
        // This scratch model constructs an already persisted controller receipt;
        // the earlier independent global UNKNOWN correctly forbids fresh admission.
        db.execSQL("INSERT INTO operations(id,caller,digest,state,result,detail,created,updated,evidence) VALUES(?,?,?,'UNKNOWN','','OWNED_UNCHANGED_ROOT_OUTCOME',1,1,'')",new Object[]{operation,ControllerTrust.OPERATIONS,"b".repeat(64)});
        db.execSQL("INSERT INTO activity_launches(id,candidate,generation,operation,expectsResult,state,callerPackage,callerKind,receiver,instance,kind) VALUES(?,?,?,?,0,'RECEIVED',?,'CONTROLLER',?,?,'CREATE')",new Object[]{delivery,ticket,generation,operation,pkg,ticket,ticket});
        long updated;try(var row=db.rawQuery("SELECT updated FROM operations WHERE id=?",new String[]{operation})){row.moveToFirst();updated=row.getLong(0);}
        ActivityManager.RecentTaskInfo info=new ActivityManager.RecentTaskInfo();info.taskId=93411;info.numActivities=1;info.baseIntent=new Intent().setComponent(physical);info.baseActivity=physical;info.topActivity=physical;
        check.accept("recorded-controller-root-without-intent-extras-is-explicitly-reclaimable",plan.invoke(null,broker,info,null,removal)!=null);
        check.accept("recorded-root-does-not-allow-automatic-nonempty-task-removal",plan.invoke(null,broker,info,null,null)==null);
        info.numActivities=0;info.baseActivity=null;info.topActivity=null;
        check.accept("recorded-controller-empty-root-without-extras-is-reclaimable",plan.invoke(null,broker,info,null,null)!=null);info.numActivities=1;info.baseActivity=physical;info.topActivity=physical;
        for(String key:new String[]{"tripoder.ticket","tripoder.component","tripoder.delivery","tripoder.generation"}){
            info.baseIntent.putExtra(key,"owned-conflict");check.accept("recorded-root-rejects-conflicting-"+key,plan.invoke(null,broker,info,null,removal)==null);info.baseIntent.removeExtra(key);
        }
        for(String kind:new String[]{"ACTIVITY","CONTEXT"}){db.execSQL("UPDATE activity_launches SET callerKind=? WHERE id=?",new Object[]{kind,delivery});check.accept("recorded-root-rejects-non-controller-source-"+kind,plan.invoke(null,broker,info,null,removal)==null);}db.execSQL("UPDATE activity_launches SET callerKind='CONTROLLER' WHERE id=?",new Object[]{delivery});
        for(String kind:new String[]{"NEW_INTENT","TASK_TO_FRONT","TASK_ALREADY_FRONT"}){db.execSQL("UPDATE activity_launches SET kind=? WHERE id=?",new Object[]{kind,delivery});check.accept("recorded-root-rejects-reused-delivery-"+kind,plan.invoke(null,broker,info,null,removal)==null);}db.execSQL("UPDATE activity_launches SET kind='CREATE' WHERE id=?",new Object[]{delivery});
        db.execSQL("UPDATE operations SET caller='owned-untrusted' WHERE id=?",new Object[]{operation});check.accept("recorded-root-requires-trusted-controller-operation",plan.invoke(null,broker,info,null,removal)==null);db.execSQL("UPDATE operations SET caller=? WHERE id=?",new Object[]{ControllerTrust.OPERATIONS,operation});
        db.execSQL("UPDATE activity_launches SET sourceTicket=? WHERE id=?",new Object[]{ticket,delivery});check.accept("recorded-root-rejects-guest-child-provenance",plan.invoke(null,broker,info,null,removal)==null);db.execSQL("UPDATE activity_launches SET sourceTicket=NULL WHERE id=?",new Object[]{delivery});
        db.execSQL("UPDATE activity_launches SET instance='another-instance' WHERE id=?",new Object[]{delivery});check.accept("recorded-root-never-guesses-a-recreated-instance",plan.invoke(null,broker,info,null,removal)==null);db.execSQL("UPDATE activity_launches SET instance=? WHERE id=?",new Object[]{ticket,delivery});
        db.execSQL("UPDATE activity_components SET profile='0:owned:true' WHERE lease=?",new Object[]{lease});check.accept("recorded-root-rejects-reparentable-component",plan.invoke(null,broker,info,null,removal)==null);db.execSQL("UPDATE activity_components SET profile='0:owned:false' WHERE lease=?",new Object[]{lease});
        db.execSQL("UPDATE activity_tickets SET componentLease='unrelated-lease' WHERE ticket=?",new Object[]{ticket});check.accept("recorded-root-requires-current-physical-component-lease",plan.invoke(null,broker,info,null,removal)==null);db.execSQL("UPDATE activity_tickets SET componentLease=? WHERE ticket=?",new Object[]{lease,ticket});
        info.baseActivity=new ComponentName(owned,stub+"Other");check.accept("recorded-root-requires-platform-base-activity-match",plan.invoke(null,broker,info,null,removal)==null);info.baseActivity=physical;
        info.topActivity=new ComponentName("dev.tripoder.foreign","dev.tripoder.foreign.Activity");check.accept("recorded-root-rejects-foreign-top-activity",plan.invoke(null,broker,info,null,removal)==null);info.topActivity=physical;
        for(String state:new String[]{"RUNNING","FAILED","ALLOCATED"}){db.execSQL("UPDATE sessions SET state=? WHERE generation=?",new Object[]{state,generation});check.accept("recorded-root-requires-dead-generation-not-"+state,plan.invoke(null,broker,info,null,removal)==null);}db.execSQL("UPDATE sessions SET state='DEAD' WHERE generation=?",new Object[]{generation});
        db.execSQL("UPDATE activity_tickets SET generation='owned-missing-session' WHERE ticket=?",new Object[]{ticket});check.accept("recorded-root-rejects-missing-session",plan.invoke(null,broker,info,null,removal)==null);db.execSQL("UPDATE activity_tickets SET generation=? WHERE ticket=?",new Object[]{generation,ticket});
        String second=UUID.randomUUID().toString(),secondDelivery=UUID.randomUUID().toString();db.execSQL("INSERT INTO activity_tickets(ticket,space,package,installGeneration,processName,stub,component,generation,state,instance,taskId,processStub,componentLease) VALUES(?,?,?,?,?,?,?,?,'LIVE',?,93411,?,?)",new Object[]{second,space,pkg,install,pkg,stub,component,generation,second,stub,lease});db.execSQL("INSERT INTO activity_launches(id,candidate,generation,operation,expectsResult,state,callerPackage,callerKind,receiver,instance,kind) VALUES(?,?,?,?,0,'RECEIVED',?,'CONTROLLER',?,?,'CREATE')",new Object[]{secondDelivery,second,generation,operation,pkg,second,second});
        check.accept("recorded-root-rejects-ambiguous-controller-attachments",plan.invoke(null,broker,info,null,removal)==null);db.delete("activity_launches","id=?",new String[]{secondDelivery});db.delete("activity_tickets","ticket=?",new String[]{second});
        String alien=UUID.randomUUID().toString();db.execSQL("INSERT INTO activity_tickets(ticket,space,package,installGeneration,processName,stub,component,generation,state,taskId,processStub) VALUES(?,'foreign-space',?,?,?,?,?,?,'LIVE',93411,?)",new Object[]{alien,pkg,install,pkg,stub,component,generation,stub});check.accept("recorded-root-rejects-cross-installation-task-record",plan.invoke(null,broker,info,null,removal)==null);db.delete("activity_tickets","ticket=?",new String[]{alien});
        check.accept("recorded-root-rejects-other-installation-stop",plan.invoke(null,broker,info,null,new String[]{space,pkg,"other-install"})==null);
        check.accept("recorded-root-proof-restored-after-negative-observations",plan.invoke(null,broker,info,null,removal)!=null);
        boolean unchanged;try(var row=db.rawQuery("SELECT updated FROM operations WHERE id=?",new String[]{operation})){row.moveToFirst();unchanged=row.getLong(0)==updated;}
        check.accept("recorded-root-planning-never-rewrites-original-unknown",unchanged&&broker.contract.query(operation).state==ExecutionContract.OperationState.UNKNOWN&&broker.contract.query(operation).detail.equals("OWNED_UNCHANGED_ROOT_OUTCOME")&&broker.activities.get(ticket).state.equals("LIVE"));
    }
}
