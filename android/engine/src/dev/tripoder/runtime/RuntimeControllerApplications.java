package dev.tripoder.runtime;

import android.database.Cursor;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import java.util.*;
import org.json.JSONObject;

/** Application-level lifecycle never manufactures or selects a project; only the user's explicit application deletion removes the projects bound to it. */
final class RuntimeControllerApplications {
    static Bundle stop(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","space","package","generation");
        String operation=ControlWire.required(input,"operation"),space=ControlWire.required(input,"space"),pkg=ControlWire.required(input,"package"),generation=ControlWire.required(input,"generation");
        String digest=ApkStore.textDigest(new JSONObject().put("kind","controller-stop-v1").put("space",space).put("package",pkg).put("generation",generation).toString());
        synchronized(broker){if(!broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest).created)throw new IllegalStateException("STOP_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(operation);}
        boolean stopping=false;int retired=0;
        try{
            RuntimeControllerService.agents().requireApplicationControl(space,pkg);
            List<String> processes=new ArrayList<>();
            synchronized(broker){
                requireInstall(broker,space,pkg,generation);RuntimeControllerService.requireLease(lease);
                try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT generation FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state NOT IN ('DEAD','FAILED')",new String[]{space,pkg,generation})){while(c.moveToNext())processes.add(c.getString(0));}
            }
            for(String process:processes){stopping=true;broker.processes.close(process);stopping=false;}
            RuntimeEmptyTasks.removeApplicationTasks(broker,space,pkg,generation);
            RuntimeControllerService.agents().requireApplicationControl(space,pkg);
            synchronized(broker){
                RuntimeControllerService.requireLease(lease);requireInstall(broker,space,pkg,generation);
                retired=broker.activities.retireDeadUnattached(space,pkg,generation,operation);
                broker.services.abandonDeadCalls(space,pkg,generation);
                broker.services.reconcile(broker.host);
                broker.activities.releaseComponentsForUninstall(space,pkg);
                broker.contract.succeed(operation,new JSONObject().put("stoppedProcesses",processes.size()).put("retiredUnattachedTickets",retired).toString());
            }
            Bundle out=new Bundle();out.putString("generation",generation);out.putInt("stoppedProcesses",processes.size());out.putInt("retiredUnattachedTickets",retired);return out;
        }catch(Exception error){
            synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){
                if(stopping)broker.contract.markUnknown(operation,"STOP_UNCONFIRMED:"+error.getMessage());
                else broker.contract.fail(operation,"STOP_REJECTED");
            }}throw error;
        }
    }
    static Bundle uninstall(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","space","package","generation");
        return uninstallOne(broker,ControlWire.required(input,"operation"),ControlWire.required(input,"space"),ControlWire.required(input,"package"),ControlWire.required(input,"generation"),lease);
    }
    /** The user's one deletion of an application: every installation of the package in every space is stopped and removed together with its data, and so are the projects bound to it. The caller learns which projects went so it can drop the source it keeps for them.
     *  Bindings alone are enough to proceed, so a retry after a partial deletion, or an application already removed from every space, still finishes. */
    static Bundle delete(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","package");
        String operation=ControlWire.required(input,"operation"),pkg=ControlWire.required(input,"package");RuntimeFiles.id(pkg);
        String digest=ApkStore.textDigest(new JSONObject().put("kind","controller-delete-application-v1").put("package",pkg).toString());
        synchronized(broker){if(!broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest).created)throw new IllegalStateException("DELETE_APPLICATION_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(operation);}
        int removed=0;
        try{
            List<String[]> installs=new ArrayList<>();List<String> bound=new ArrayList<>();
            synchronized(broker){
                RuntimeControllerService.requireLease(lease);
                try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT space,generation FROM installs WHERE package=? ORDER BY space",new String[]{pkg})){while(c.moveToNext())installs.add(new String[]{c.getString(0),c.getString(1)});}
                try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id FROM project_refs WHERE package=?",new String[]{pkg})){while(c.moveToNext())bound.add(c.getString(0));}
            }
            if(installs.isEmpty()&&bound.isEmpty())throw new IllegalStateException("APPLICATION_NOT_INSTALLED");
            for(String project:bound)RuntimeControllerService.agents().requireUiControl(project,"deleteApplication");
            for(String[] install:installs){uninstallOne(broker,operation+"-"+removed,install[0],pkg,install[1],lease);removed++;}
            org.json.JSONArray projects;
            synchronized(broker){
                var db=broker.store.getWritableDatabase();db.beginTransaction();
                try{
                    RuntimeControllerService.requireLease(lease);
                    projects=removeBindings(db,"package=?",pkg);
                    broker.contract.succeed(operation,new JSONObject().put("installs",removed).put("deletedProjects",projects).toString());
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            Bundle out=new Bundle();out.putInt("installs",removed);out.putString("deletedProjects",projects.toString());return out;
        }catch(Exception error){
            synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){
                if(removed>0)broker.contract.markUnknown(operation,"DELETE_APPLICATION_PARTIAL:"+error.getMessage());
                else broker.contract.fail(operation,"DELETE_APPLICATION_REJECTED");
            }}throw error;
        }
    }
    /** Inside the caller's transaction: every binding matching {@code where}, of any kind, goes; their ids are returned for the workbench to drop their source. */
    static org.json.JSONArray removeBindings(android.database.sqlite.SQLiteDatabase db,String where,String...args){
        org.json.JSONArray projects=new org.json.JSONArray();
        try(Cursor c=db.rawQuery("SELECT id FROM project_refs WHERE "+where,args)){while(c.moveToNext())projects.put(c.getString(0));}
        db.delete("project_refs",where,args);return projects;
    }
    static Bundle uninstallOne(RuntimeBroker broker,String operation,String space,String pkg,String generation,String lease)throws Exception{
        String digest=ApkStore.textDigest(new JSONObject().put("kind","controller-uninstall-v1").put("space",space).put("package",pkg).put("generation",generation).toString());
        synchronized(broker){if(!broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest).created)throw new IllegalStateException("UNINSTALL_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(operation);}
        boolean stopping=false,committing=false;int retired=0;
        try{
            RuntimeControllerService.agents().requireApplicationControl(space,pkg);
            List<String> processes=new ArrayList<>();
            synchronized(broker){
                requireInstall(broker,space,pkg,generation);RuntimeControllerService.requireLease(lease);
                try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT generation FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state NOT IN ('DEAD','FAILED')",new String[]{space,pkg,generation})){while(c.moveToNext())processes.add(c.getString(0));}
            }
            // The explicit deletion confirmation authorizes stopping this installation only.
            for(String process:processes){stopping=true;broker.processes.close(process);stopping=false;}
            RuntimeEmptyTasks.removeApplicationTasks(broker,space,pkg,generation);
            // Deleting an application cancels its scheduled jobs through the normal fenced cancel, then retires the emptied components. Outside the broker lock, as Android is called.
            broker.jobBroker.cancel(broker.apks.get(space,pkg),null,null,true,UUID.randomUUID().toString());
            RuntimeJobRetirement.reconcile(broker);
            RuntimeControllerService.agents().requireApplicationControl(space,pkg);
            synchronized(broker){
                RuntimeControllerService.requireLease(lease);requireInstall(broker,space,pkg,generation);
                retired=broker.activities.retireDeadUnattached(space,pkg,generation,operation);
                broker.services.abandonDeadCalls(space,pkg,generation);
                broker.services.reconcile(broker.host);
                broker.activities.releaseComponentsForUninstall(space,pkg);
                broker.catalog.requireUnused(space,pkg);committing=true;
                broker.apks.uninstallAccepted(operation,space,pkg,generation,ApkStore.NO_FAULT);
            }
            Bundle out=new Bundle();out.putString("generation",generation);out.putInt("retiredUnattachedTickets",retired);return out;
        }catch(Exception error){
            synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){
                if(stopping||committing)broker.contract.markUnknown(operation,"UNINSTALL_UNCONFIRMED:"+error.getMessage());
                else broker.contract.fail(operation,"UNINSTALL_REJECTED");
            }}throw error;
        }
    }
    private static void requireInstall(RuntimeBroker broker,String space,String pkg,String generation){
        broker.catalog.requireSpace(space);RuntimeFiles.id(pkg);RuntimeFiles.id(generation);
        var row=broker.apks.row(space,pkg);if(row==null||!generation.equals(row.generation))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
    }
    private RuntimeControllerApplications(){}
}
