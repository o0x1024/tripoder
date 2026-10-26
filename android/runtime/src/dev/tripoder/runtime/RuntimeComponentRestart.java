package dev.tripoder.runtime;

import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** A component setting commit owns a finite set of old process generations. */
final class RuntimeComponentRestart {
    static final String RESULT="COMPONENT_SETTING_RESTART_CONFIRMED";
    /** Shares the setting's transaction. Invalidate admission caches BEFORE commit. */
    static List<String> mark(RuntimeBroker broker,String operation,ApkStore.BundleInstall install){
        var db=broker.store.getWritableDatabase();List<String> targets=new ArrayList<>();
        try(var c=db.rawQuery("SELECT generation,componentRestartOperation FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state!='DEAD'",new String[]{install.space,install.info.packageName,install.generation})){
            while(c.moveToNext()){
                if(!c.isNull(1))throw new IllegalStateException("COMPONENT_RESTART_ALREADY_PENDING");
                targets.add(c.getString(0));
            }
        }
        if(targets.isEmpty())throw new IllegalStateException("COMPONENT_RESTART_TARGET_MISSING");
        for(String generation:targets){
            broker.store.invalidateOpen(generation);
            android.content.ContentValues row=new android.content.ContentValues();row.put("componentRestartOperation",operation);
            if(db.update("sessions",row,"generation=? AND componentRestartOperation IS NULL",new String[]{generation})!=1)throw new IllegalStateException("COMPONENT_RESTART_TARGET_CHANGED");
        }
        return List.copyOf(targets);
    }
    static void requireCompleted(SQLiteDatabase db,String space,String pkg,String install){
        try(var c=db.rawQuery("SELECT 1 FROM sessions s LEFT JOIN operations o ON o.id=s.componentRestartOperation WHERE s.space=? AND s.package=? AND s.installGeneration=? AND s.componentRestartOperation IS NOT NULL AND (s.state!='DEAD' OR o.id IS NULL OR o.state!='SUCCEEDED' OR o.result IS NULL OR o.result!=? OR o.caller!=?) LIMIT 1",new String[]{space,pkg,install,RESULT,"pending-install:"+install})){
            if(c.moveToFirst())throw new IllegalStateException("COMPONENT_RESTART_UNCONFIRMED");
        }
    }
    /** A marker is durable typed intent, never a PID or operation-name heuristic. */
    static boolean target(RuntimeBroker broker,String generation){
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT s.installGeneration,o.caller,o.state,o.result FROM sessions s JOIN operations o ON o.id=s.componentRestartOperation WHERE s.generation=?",new String[]{generation})){
            if(!c.moveToFirst())return false;
            return ("pending-install:"+c.getString(0)).equals(c.getString(1))&&(Set.of("RUNNING","UNKNOWN").contains(c.getString(2))||("SUCCEEDED".equals(c.getString(2))&&RESULT.equals(c.getString(3))));
        }
    }
    static void uncertain(RuntimeBroker broker,String operation){
        var receipt=broker.contract.query(operation);
        if(receipt!=null&&receipt.state==ExecutionContract.OperationState.RUNNING)broker.contract.markUnknown(operation,"COMPONENT_RESTART_DEATH_UNCONFIRMED");
        reconcile(broker);
    }
    /** Only positive death observations close the setting's existing operation. */
    static void reconcile(RuntimeBroker broker){
        if(!Thread.holdsLock(broker))throw new IllegalStateException("COMPONENT_RESTART_WITHOUT_BROKER_LOCK");
        var db=broker.store.getWritableDatabase();List<String> operations=new ArrayList<>();
        try(var c=db.rawQuery("SELECT DISTINCT s.componentRestartOperation FROM sessions s JOIN operations o ON o.id=s.componentRestartOperation WHERE o.state IN ('RUNNING','UNKNOWN')",null)){while(c.moveToNext())operations.add(c.getString(0));}
        for(String operation:operations){
            var receipt=broker.contract.query(operation);String owner=null;List<String> targets=new ArrayList<>();boolean dead=true;
            try(var c=db.rawQuery("SELECT generation,installGeneration,state FROM sessions WHERE componentRestartOperation=? ORDER BY generation",new String[]{operation})){
                while(c.moveToNext()){
                    if(owner==null)owner=c.getString(1);
                    if(!owner.equals(c.getString(1))||!"DEAD".equals(c.getString(2)))dead=false;
                    targets.add(c.getString(0));
                }
            }
            if(!dead||targets.isEmpty()||!("pending-install:"+owner).equals(receipt.callerIdentity))continue;
            if(receipt.state==ExecutionContract.OperationState.RUNNING){broker.contract.succeed(operation,RESULT);continue;}
            try{
                var prior=new org.json.JSONObject().put("state",receipt.state.name()).put("result",nullable(receipt.result)).put("detail",nullable(receipt.detail)).put("evidence",nullable(receipt.evidence)).put("updatedAtMs",receipt.updatedAtMs);
                String evidence=new org.json.JSONObject().put("prior",prior).put("observation","ALL_COMPONENT_RESTART_TARGETS_DEAD").put("targets",targets.size()).put("targetDigest",ApkStore.textDigest(targets.toString())).toString();
                if(evidence.length()>2048){android.util.Log.w("TripoderComponents","RESTART_EVIDENCE_CAPACITY:"+operation);continue;}
                broker.contract.reconcile(operation,ExecutionContract.OperationState.SUCCEEDED,RESULT,evidence);
            }catch(Exception error){throw new IllegalStateException("COMPONENT_RESTART_EVIDENCE_FAILED",error);}
        }
    }
    private static Object nullable(String value){return value==null?org.json.JSONObject.NULL:value;}
    private RuntimeComponentRestart(){}
}
