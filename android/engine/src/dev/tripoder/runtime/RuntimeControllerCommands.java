package dev.tripoder.runtime;

import android.database.Cursor;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.*;

/** Explicit production commands. No fault injection, arbitrary physical path or raw worker Binder. */
final class RuntimeControllerCommands {
    static Bundle execute(RuntimeBroker broker,Bundle input,String lease,int uid)throws Exception{
        if("install".equals(input.getString("command")))return RuntimeControllerInstall.install(broker,input);
        if(input.hasFileDescriptors())throw new SecurityException("CONTROL_FILE_DESCRIPTOR_UNSUPPORTED");
        String command=ControlWire.required(input,"command");Bundle out=new Bundle();
        if(command.equals("appPresentation"))return RuntimeControllerPresentation.read(broker,input);
        if(command.equals("reclaimEmptyTasks")){fields(input,"command");String receipt=RuntimeEmptyTasks.reclaim(broker,null).toString();Bundle resources=RuntimeControllerResources.inspect(broker);resources.putString("reclaimed",receipt);return resources;}
        if(command.equals("resources")){fields(input,"command");return RuntimeControllerResources.inspect(broker);}
        if(command.startsWith("agent"))return RuntimeControllerService.agents().control(input,lease,uid);
        if("launchTargets".equals(command))return RuntimeControllerLaunch.targets(broker,input);
        if("launchEntries".equals(command))return RuntimeControllerEntryClosures.list(broker,input);
        if("launchRecovery".equals(command))return RuntimeControllerEntryClosures.recovery(broker,input);
        if("entryClosureReceipt".equals(command))return RuntimeControllerEntryClosures.receipt(broker,input);
        if("closeLaunchEntry".equals(command)){RuntimeControllerService.agents().requireUiControl(input.getString("project"),"closeLaunchEntry");return RuntimeControllerEntryClosures.close(broker,input);}
        if("prepareLaunch".equals(command)||"prepareApplicationLaunch".equals(command))return RuntimeControllerLaunch.prepare(broker,input,lease,uid);
        if("uninstallApplication".equals(command))return RuntimeControllerApplications.uninstall(broker,input,lease,uid);
        if("hookProcesses".equals(command))return RuntimeControllerHooks.processes(broker,input);
        if("hook".equals(command)){RuntimeControllerService.agents().requireUiControl(input.getString("project"),new JSONObject(input.getString("request")).getString("command"));return RuntimeControllerHooks.execute(broker,input,lease,uid);}
        if("bindProject".equals(command)){
            RuntimeControllerService.agents().requireUiControl(input.getString("project"),"bindProject");
            if(input.containsKey("expectedBinding"))fields(input,"command","operation","project","space","package","generation","expectedBinding");else fields(input,"command","operation","project","space","package","generation");
            return new ProjectTargetBindings(broker).bind(ControlWire.required(input,"operation"),"trusted-workbench",ControlWire.required(input,"project"),ControlWire.required(input,"space"),ControlWire.required(input,"package"),ControlWire.required(input,"generation"),input.getString("expectedBinding"));
        }
        if("resolveProject".equals(command)){fields(input,"command","project");return new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project"));}
        synchronized(broker){
            switch(command){
                case "status":
                    fields(input,"command");out.putString("authority",broker.store.authorityGeneration);out.putString("lease",lease);out.putInt("engineUid",android.os.Process.myUid());out.putInt("enginePid",android.os.Process.myPid());out.putInt("controllerUid",uid);out.putInt("schema",broker.store.getReadableDatabase().getVersion());out.putBoolean("strongIsolation",false);out.putBoolean("hookReady",true);break;
                case "catalog":{
                    fields(input,"command");JSONArray spaces=new JSONArray();for(String space:broker.catalog.spaces())spaces.put(space);
                    if(spaces.length()>256)throw new IllegalStateException("CATALOG_PAGE_REQUIRED");JSONArray installs=new JSONArray();
                    try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT space,package,generation,state,version FROM installs ORDER BY space,package LIMIT 257",null)){while(c.moveToNext())installs.put(new JSONObject().put("space",c.getString(0)).put("package",c.getString(1)).put("generation",c.getString(2)).put("state",c.getString(3)).put("version",c.getLong(4)));}
                    if(installs.length()>256)throw new IllegalStateException("CATALOG_PAGE_REQUIRED");JSONArray projects=new JSONArray();
                    try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT id,space,package,generation,state,operation FROM project_refs WHERE kind='DIRECT' ORDER BY id LIMIT 257",null)){while(c.moveToNext())projects.put(new JSONObject().put("project",c.getString(0)).put("space",c.getString(1)).put("package",c.getString(2)).put("generation",c.getString(3)).put("state",c.getString(4)).put("bindingOperation",c.getString(5)));}
                    if(projects.length()>256)throw new IllegalStateException("CATALOG_PAGE_REQUIRED");out.putString("spaces",spaces.toString());out.putString("installs",installs.toString());out.putString("projects",projects.toString());break;
                }
                case "createSpace":{
                    fields(input,"command","space","operation");String space=ControlWire.required(input,"space"),operation=ControlWire.required(input,"operation");RuntimeFiles.id(space);
                    String digest=ApkStore.textDigest("controller-create-space-v1\n"+space);var admission=broker.contract.accept(operation,"trusted-workbench",digest);
                    if(!admission.created){if(admission.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+admission.operation.state);out.putString("space",admission.operation.result);break;}
                    broker.contract.start(operation);var db=broker.store.getWritableDatabase();
                    try{db.beginTransaction();try{broker.catalog.createSpace(space);broker.contract.succeed(operation,space);db.setTransactionSuccessful();}finally{db.endTransaction();}}
                    catch(Exception error){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING)broker.contract.markUnknown(operation,"SPACE_CREATION_PERSISTENCE_UNCONFIRMED");throw error;}
                    out.putString("space",space);break;
                }
                case "query":{
                    fields(input,"command","operation");new ControllerLaunchLedger(broker).expire(ControlWire.required(input,"operation"));var operation=broker.contract.query(ControlWire.required(input,"operation"));if(operation==null)throw new IllegalStateException("OPERATION_NOT_FOUND");
                    if(!"trusted-workbench".equals(operation.callerIdentity))throw new SecurityException("OPERATION_OWNER_MISMATCH");out.putString("state",operation.state.name());out.putString("result",operation.result);out.putString("detail",operation.detail);out.putString("evidence",operation.evidence);break;
                }
                case "cancelNotAccepted":{
                    fields(input,"command","operation");String id=ControlWire.required(input,"operation");var operation=broker.contract.query(id);
                    if(operation==null){var db=broker.store.getWritableDatabase();db.beginTransaction();try{broker.contract.accept(id,"trusted-workbench",ApkStore.textDigest("cancel-before-admission-v1\n"+id));broker.contract.start(id);operation=broker.contract.fail(id,"CANCELED_BEFORE_ACCEPT");db.setTransactionSuccessful();}finally{db.endTransaction();}}
                    if(!"trusted-workbench".equals(operation.callerIdentity))throw new SecurityException("OPERATION_OWNER_MISMATCH");
                    out.putString("state",operation.state.name());out.putString("result",operation.result);out.putString("detail",operation.detail);break;
                }
                default:throw new UnsupportedOperationException("CONTROL_COMMAND_UNSUPPORTED:"+command);
            }
        }
        return out;
    }
    static void fields(Bundle input,String...names){if(!input.keySet().equals(Set.of(names)))throw new IllegalArgumentException("CONTROL_FIELDS_UNSUPPORTED");for(String name:names)if(!(input.get(name) instanceof String))throw new IllegalArgumentException("CONTROL_FIELD_TYPE:"+name);}
    private RuntimeControllerCommands(){}
}
