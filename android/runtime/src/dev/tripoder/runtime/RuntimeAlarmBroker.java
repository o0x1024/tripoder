package dev.tripoder.runtime;

import android.app.*;
import android.content.ContentValues;
import android.os.Bundle;
import java.util.*;

/** Real AlarmManager effects, retained separately from broadcast completion. */
final class RuntimeAlarmBroker {
    private final RuntimeBroker broker;
    RuntimeAlarmBroker(RuntimeBroker broker){this.broker=broker;}
    Bundle route(ApkStore.BundleInstall install,Bundle input)throws Exception{
        if(Thread.holdsLock(broker))throw new IllegalStateException("ALARM_SYSTEM_CALL_INSIDE_BROKER_LOCK");
        AlarmManager alarms=Objects.requireNonNull(broker.host.getSystemService(AlarmManager.class));String action=RuntimeWire.required(input,"action");Bundle out=new Bundle();
        if(action.equals("canScheduleExactAlarms")){out.putBoolean("allowed",alarms.canScheduleExactAlarms());return out;}
        if(!Set.of("set","setExact","setAndAllowWhileIdle","setExactAndAllowWhileIdle","setWindow","setRepeating","setInexactRepeating","cancel","cancelAll").contains(action))throw new UnsupportedOperationException("ALARM_OPERATION_UNSUPPORTED");
        if(!action.startsWith("cancel")&&(input.getInt("type")<0||input.getInt("type")>3||input.getLong("window")<0||input.getLong("interval")<0))throw new IllegalArgumentException("ALARM_ARGUMENTS_UNSUPPORTED");
        if((action.equals("setExact")||action.equals("setExactAndAllowWhileIdle"))&&!alarms.canScheduleExactAlarms())throw new SecurityException("ALARM_EXACT_PERMISSION_NOT_GRANTED");
        String operation=RuntimeWire.required(input,"operation");List<PendingIntentLedger.Version> targets=new ArrayList<>();
        synchronized(broker){
            broker.pendingIntentBroker.current(install);broker.pendingIntents.requireIdle(install.generation);
            if(action.equals("cancelAll"))targets.addAll(broker.pendingIntents.versions(install.generation));
            else targets.add(broker.pendingIntentBroker.owned(install,RuntimeWire.required(input,"version")));
            String digest=ApkStore.textDigest(action+":"+input.getString("version","")+":"+input.getInt("type")+":"+input.getLong("trigger")+":"+input.getLong("window")+":"+input.getLong("interval"));
            var db=broker.store.getWritableDatabase();db.beginTransaction();try{
                if(!broker.contract.accept(operation,"pending-install:"+install.generation,digest).created)throw new IllegalStateException("ALARM_ALREADY_ADMITTED_QUERY_REQUIRED");broker.contract.start(operation);
                broker.pendingIntents.mutation(operation,install.generation,input.getString("version",""),"ALARM:"+action);
                for(var target:targets){ContentValues row=new ContentValues();row.put("operation",operation+":"+target.version);row.put("version",target.version);row.put("kind",action);row.put("type",input.getInt("type"));row.put("triggerMs",input.getLong("trigger"));row.put("windowMs",input.getLong("window"));row.put("intervalMs",input.getLong("interval"));row.put("state","PREPARED");db.insertOrThrow("pending_alarms",null,row);}
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
        boolean effectAttempted=false;
        try{
            for(var target:targets){
                PendingIntent token=broker.pendingIntentBroker.lookup(target);
                if(action.equals("cancel")||action.equals("cancelAll")){if(token!=null){effectAttempted=true;alarms.cancel(token);}}
                else{
                    if(target.fenced||target.consumed||token==null)throw new IllegalStateException("ALARM_PENDING_TOKEN_CANCELED");
                    int type=input.getInt("type");long trigger=input.getLong("trigger");
                    effectAttempted=true;
                    switch(action){
                        case "set":alarms.set(type,trigger,token);break;
                        case "setExact":alarms.setExact(type,trigger,token);break;
                        case "setAndAllowWhileIdle":alarms.setAndAllowWhileIdle(type,trigger,token);break;
                        case "setExactAndAllowWhileIdle":alarms.setExactAndAllowWhileIdle(type,trigger,token);break;
                        case "setWindow":alarms.setWindow(type,trigger,input.getLong("window"),token);break;
                        case "setRepeating":alarms.setRepeating(type,trigger,input.getLong("interval"),token);break;
                        case "setInexactRepeating":alarms.setInexactRepeating(type,trigger,input.getLong("interval"),token);break;
                        default:throw new UnsupportedOperationException("ALARM_OPERATION_UNSUPPORTED");
                    }
                }
            }
            synchronized(broker){var db=broker.store.getWritableDatabase();db.beginTransaction();try{
                for(var target:targets)state(operation,target.version,"SYSTEM_RPC_RETURNED");broker.contract.succeed(operation,"ALARM_SYSTEM_RPC_RETURNED");broker.pendingIntents.close(operation);db.setTransactionSuccessful();
            }finally{db.endTransaction();}}return out;
        }catch(Exception error){synchronized(broker){
            if(effectAttempted){for(var target:targets)state(operation,target.version,"UNKNOWN");broker.contract.markUnknown(operation,"ALARM_SYSTEM_OUTCOME_UNCONFIRMED:"+error.getClass().getName());}
            else{for(var target:targets)state(operation,target.version,"REJECTED");broker.contract.fail(operation,"ALARM_REJECTED_BEFORE_SYSTEM_MUTATION");broker.pendingIntents.close(operation);}
        }throw error;}
    }
    private void state(String operation,String version,String state){ContentValues value=new ContentValues();value.put("state",state);if(broker.store.getWritableDatabase().update("pending_alarms",value,"operation=?",new String[]{operation+":"+version})!=1)throw new IllegalStateException("ALARM_RECEIPT_MISSING");}
}
