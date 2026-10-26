package dev.tripoder.runtime;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.*;

/** Installation ingress with an optional project binding. One call waits for the guest Application and returns the entry Intent the workbench starts. */
final class RuntimeControllerLaunch {
    /** Expiration is maintenance, never a side effect of reading an operation receipt. */
    static void startMaintenance(RuntimeBroker broker){
        android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
        main.postDelayed(new Runnable(){public void run(){try{RuntimeRecoveryGate.require();expireEntries(broker);}catch(Exception error){android.util.Log.w("TripoderLaunch","Entry expiration maintenance failed",error);}finally{main.postDelayed(this,1000);}}},1000);
    }
    static Bundle targets(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.targetFields(input,"command","project");Bundle target=(input.containsKey("bindingOperation")?new ProjectTargetBindings(broker).resolveBinding(ControlWire.required(input,"project"),input.getString("bindingOperation")):new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project")));
        var install=broker.apks.get(target.getString("space"),target.getString("package"));if(!install.generation.equals(target.getString("generation")))throw new IllegalStateException("LAUNCH_BINDING_STALE");
        target.putString("targets",entries(install).toString());return target;
    }
    static JSONArray entries(ApkStore.BundleInstall install)throws Exception{
        synchronized(install){var categories=GuestIntentResolver.entryCategories(install);JSONArray list=new JSONArray();for(var info:install.activityIndex().values()){
            if(!info.exported||!info.enabled||!info.applicationInfo.enabled||info.permission!=null&&!info.permission.isEmpty())continue;
            // UI lists actual declarations, including ones whose task policy is not implemented.
            String unsupported="";try{RuntimeComponents.activity(install,info.name);}catch(UnsupportedOperationException|IllegalStateException error){unsupported=error.getMessage();}
            list.put(new JSONObject().put("component",info.name).put("launcher",categories.containsKey(info.name)).put("entryCategory",categories.containsKey(info.name)?categories.get(info.name):JSONObject.NULL).put("supported",unsupported.isEmpty()).put("reason",unsupported));
        }
        if(list.length()>2048)throw new IllegalStateException("ACTIVITY_TARGET_PAGE_REQUIRED");return list;}
    }
    static Bundle prepare(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        boolean application="prepareApplicationLaunch".equals(input.getString("command"));
        if(application)RuntimeControllerCommands.fields(input,"command","operation","space","package","generation");
        else if(input.containsKey("codeProbeRevision"))RuntimeControllerCommands.fields(input,"command","operation","project","bindingOperation","generation","component","codeProbeRevision");
        else RuntimeControllerCommands.fields(input,"command","operation","project","bindingOperation","generation","component");
        String operation=ControlWire.required(input,"operation"),generation=ControlWire.required(input,"generation");
        String project=application?"":ControlWire.required(input,"project"),binding=application?"":ControlWire.required(input,"bindingOperation");
        JSONObject identity=new JSONObject().put("kind",application?"controller-application-v1":"controller-activity-v1").put("generation",generation);
        if(application)identity.put("space",ControlWire.required(input,"space")).put("package",ControlWire.required(input,"package"));
        else identity.put("project",project).put("binding",binding).put("component",ControlWire.required(input,"component"));
        if(input.containsKey("codeProbeRevision"))identity.put("probeRevision",input.getString("codeProbeRevision"));
        String digest=ApkStore.textDigest(identity.toString());
        synchronized(broker){var accepted=broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest);if(!accepted.created)throw new IllegalStateException("LAUNCH_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(operation);}
        boolean started=false;
        try{
            Bundle target;
            if(application){target=new Bundle();target.putString("project","");target.putString("bindingOperation","");target.putString("space",input.getString("space"));target.putString("package",input.getString("package"));target.putString("generation",generation);}
            else target=new ProjectTargetBindings(broker).resolveBinding(project,binding);
            if(!binding.equals(target.getString("bindingOperation"))||!generation.equals(target.getString("generation")))throw new IllegalStateException("LAUNCH_BINDING_STALE");
            var install=broker.apks.get(target.getString("space"),target.getString("package"));if(!generation.equals(install.generation))throw new IllegalStateException("LAUNCH_BINDING_STALE");
            final String component,category;final ActivityInfo activity;
            synchronized(install){
                Intent selected=application?applicationEntry(install):null;
                component=application?selected.getComponent().getClassName():input.getString("component");
                category=application?selected.getCategories().iterator().next():GuestIntentResolver.entryCategories(install).get(component);
                activity=RuntimeComponents.activity(install,component);RuntimeComponents.authorize(activity,ControllerTrust.workbenchCaller(broker.host));
            }
            // Retire authenticated empty tasks before deciding whether a root remains
            // open. The same proof-based maintenance is used by ProcessSupervisor.
            closeOrphanedProcesses(broker,install.space,install.info.packageName,generation);
            RuntimeEmptyTasks.reclaim(broker,null);
            boolean hasLiveSession;
            synchronized(broker){
                try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state NOT IN ('DEAD','FAILED') LIMIT 1",new String[]{install.space,install.info.packageName,generation})){hasLiveSession=c.moveToFirst();}
            }
            if(!hasLiveSession)RuntimeEmptyTasks.removeApplicationTasks(broker,install.space,install.info.packageName,generation);
            // A dead process's unattached entry is retired on proof; a live attached root is woken, not rejected.
            RuntimeControllerStaleEntries.retire(broker,install.space,install.info.packageName,generation);
            var appTasks=broker.host.getSystemService(android.app.ActivityManager.class).getAppTasks();
            synchronized(broker){
                broker.activities.releaseUnused();
                if(!hasLiveSession)broker.activities.retireDeadUnattached(install.space,install.info.packageName,generation,operation);
                broker.activities.retireDeadTasks(install.space,install.info.packageName,generation,operation,appTasks);
            }
            expireEntries(broker);
            Bundle woken=RuntimeControllerWake.wake(broker,install.space,install.info.packageName,generation,operation);
            if(woken!=null)return woken;
            synchronized(broker){ActivityEntryFences.requireNoOpenRoot(broker.store,install.space,install.info.packageName,generation);broker.activities.requireCapacity(install,component);}
            var slot=broker.processes.begin(install.space,install.info.packageName,activity.processName);started=true;
            // The caller's Binder thread waits for the guest Application under the launch's one startup deadline.
            broker.processes.awaitReady(slot.allocation.session.processGeneration);
            synchronized(broker){
                RuntimeControllerService.requireLease(lease);
                var current=broker.apks.row(install.space,install.info.packageName);if(current==null||!"COMMITTED".equals(current.state)||!generation.equals(current.generation)||!slot.install.generation.equals(generation))throw new IllegalStateException("LAUNCH_INSTALL_STALE");
                if(!application)try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM project_refs WHERE id=? AND operation=? AND state='COMMITTED' AND kind='DIRECT'",new String[]{project,binding})){if(!c.moveToFirst())throw new IllegalStateException("LAUNCH_BINDING_STALE");}
                if(broker.contract.getSession(slot.allocation.session.processGeneration).state!=ExecutionContract.ProcessState.RUNNING)throw new IllegalStateException("LAUNCH_PROCESS_STALE");
                // Checked again in the transaction that creates the root, so concurrent launches of one installation open one root.
                ActivityEntryFences.requireNoOpenRoot(broker.store,install.space,install.info.packageName,generation);
                String ticket=broker.activities.prepare(slot,component,null,operation,ControllerTrust.workbenchCaller(broker.host),null,null);
                Intent intent=broker.activities.controllerEntry(broker.host,ticket,category);issued.put(operation,android.os.SystemClock.elapsedRealtime());
                Bundle out=broker.processEvidence(slot);out.putString("operation",operation);out.putString("component",component);out.putParcelable("intent",intent);return out;
            }
        }catch(Exception error){boolean modeRejected=String.valueOf(error.getMessage()).startsWith("RUNTIME_MODE_NOT_IMPLEMENTED:");synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){if(started&&!modeRejected)broker.contract.markUnknown(operation,"LAUNCH_PREPARATION_UNCONFIRMED");else broker.contract.fail(operation,modeRejected?"RUNTIME_MODE_NOT_IMPLEMENTED":"LAUNCH_TARGET_REJECTED");}}throw error;}
    }
    /** A relaunch is the user's word that the application is not in use. When an open root belongs only to dead processes,
     *  sibling processes that no open root belongs to (a crashed app's background service process) are closed first, so
     *  their sessions cannot keep the dead root from being retired. A live root is never touched. */
    private static void closeOrphanedProcesses(RuntimeBroker broker,String space,String pkg,String generation)throws Exception{
        java.util.List<String> orphans=new java.util.ArrayList<>();
        synchronized(broker){
            var db=broker.store.getReadableDatabase();boolean deadRoot=false,liveRoot=false;
            try(Cursor c=db.rawQuery("SELECT s.state FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND t.entryFenceOperation IS NULL AND t.state NOT IN ('FINISHED','REVOKED','RETIRED')",new String[]{space,pkg,generation})){
                while(c.moveToNext()){String state=c.getString(0);if(state==null||"DEAD".equals(state)||"FAILED".equals(state))deadRoot=true;else liveRoot=true;}
            }
            if(!deadRoot||liveRoot)return;
            try(Cursor c=db.rawQuery("SELECT generation FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state NOT IN ('DEAD','FAILED')",new String[]{space,pkg,generation})){while(c.moveToNext())orphans.add(c.getString(0));}
        }
        for(String process:orphans){android.util.Log.i("TripoderLaunch","CLOSE_ORPHAN generation="+process);broker.processes.close(process);}
    }
    /** Entries issued by this authority, by launch operation, with their issue time. An authority restart kills every worker, after which the stale-entry path applies. */
    private static final java.util.Map<String,Long> issued=new java.util.concurrent.ConcurrentHashMap<>();
    /** An issued entry the guest has not entered by then never will: the platform can drop a start silently (a background start aborted), with no error for the workbench to report. */
    static final long ENTRY_VALIDITY_MS=30000;
    /** The workbench received the entry but did not start it (it was no longer visible, or the start threw).
     *  Its word is enough: the root is revoked, so even a late start of that Intent only meets a rejected ticket. */
    static Bundle abandon(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation");String operation=ControlWire.required(input,"operation");RuntimeFiles.id(operation);
        synchronized(broker){
            RuntimeControllerService.requireLease(lease);
            var admitted=broker.contract.query(operation);if(admitted==null||!ControllerTrust.OPERATIONS.equals(admitted.callerIdentity))throw new SecurityException("OPERATION_OWNER_MISMATCH");
            String ticket=revokeUnentered(broker,operation,"LAUNCH_NOT_DISPATCHED");if(ticket==null)throw new IllegalStateException("LAUNCH_ENTRY_NOT_PENDING");
            Bundle out=new Bundle();out.putString("operation",operation);out.putString("ticket",ticket);out.putString("state",broker.contract.query(operation).state.name());return out;
        }
    }
    /** Revokes issued entries that were never entered within their validity; called before every launch and every operation query. */
    static void expireEntries(RuntimeBroker broker)throws Exception{
        long now=android.os.SystemClock.elapsedRealtime();
        for(var entry:issued.entrySet())if(now-entry.getValue()>ENTRY_VALIDITY_MS)synchronized(broker){revokeUnentered(broker,entry.getKey(),"LAUNCH_ENTRY_NOT_ENTERED");}
    }
    /** Revokes the root of a controller launch only while nothing has entered it. Returns the ticket, or null when it was already entered or ended. Caller holds broker. */
    private static String revokeUnentered(RuntimeBroker broker,String operation,String detail){
        issued.remove(operation);
        String ticket;try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT candidate FROM activity_launches WHERE operation=? AND callerKind='CONTROLLER'",new String[]{operation})){if(!c.moveToFirst())throw new IllegalStateException("LAUNCH_ENTRY_NOT_ISSUED");ticket=c.getString(0);}
        var db=broker.store.getWritableDatabase();db.beginTransaction();try{
            android.content.ContentValues revoked=new android.content.ContentValues();revoked.put("state","REVOKED");revoked.put("resourceProof","CONTROLLER_ENTRY_NOT_ENTERED;operation="+operation+";reason="+detail);
            if(db.update("activity_tickets",revoked,"ticket=? AND state='PREPARED' AND taskId<0 AND instance IS NULL",new String[]{ticket})!=1)return null;
            android.content.ContentValues retired=new android.content.ContentValues();retired.put("state","RETIRED");db.update("activity_launches",retired,"candidate=? AND state='PREPARED'",new String[]{ticket});
            if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING)broker.contract.fail(operation,detail);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        broker.activities.releaseUnused();return ticket;
    }
    static Intent applicationEntry(ApkStore.BundleInstall install){
        Intent selected=GuestIntentResolver.launchIntent(install,false);
        if(selected==null)throw new IllegalStateException("APPLICATION_HAS_NO_LAUNCHER");return selected;
    }
}
