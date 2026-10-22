package dev.tripoder.runtime;

import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.*;

/** Explicit production commands. No fault injection, arbitrary physical path or raw worker Binder. */
final class RuntimeControllerCommands {
    static Bundle execute(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        try(RuntimeMutationGuard scope=RuntimeMutationGuard.enter(broker,input)){return executeScoped(broker,input,lease);}
    }
    private static Bundle executeScoped(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        if("install".equals(input.getString("command")))return RuntimeControllerInstall.install(broker,input);
        if(input.hasFileDescriptors())throw new SecurityException("CONTROL_FILE_DESCRIPTOR_UNSUPPORTED");
        String command=ControlWire.required(input,"command");Bundle out=new Bundle();
        if(command.equals("catalog")){fields(input,"command");return RuntimeControllerCatalog.read(broker);}
        if(command.equals("appPresentation"))return RuntimeControllerPresentation.read(broker,input);
        if(command.equals("reclaimEmptyTasks")){fields(input,"command");String receipt=RuntimeEmptyTasks.reclaim(broker,null).toString();Bundle resources=RuntimeControllerResources.inspect(broker);resources.putString("reclaimed",receipt);return resources;}
        if(command.equals("resources")){fields(input,"command");return RuntimeControllerResources.inspect(broker);}
        if(command.equals("fileSource")){
            fields(input,"command","space","package","generation","root");
            synchronized(broker){
                var install=broker.apks.get(ControlWire.required(input,"space"),ControlWire.required(input,"package"));
                if(!install.generation.equals(ControlWire.required(input,"generation")))throw new IllegalStateException("STALE_INSTALL");
                String root=ControlWire.required(input,"root");java.io.File path=switch(root){
                    case "data"->install.data;
                    case "apk"->install.apk.getParentFile();
                    default->throw new IllegalArgumentException("FILE_GUEST_ROOT_INVALID");
                };out.putString("path",path.toString());return out;
            }
        }
        if(command.startsWith("agent"))return RuntimeControllerService.agents().control(input,lease);
        if("launchTargets".equals(command))return RuntimeControllerLaunch.targets(broker,input);
        if("launchEntries".equals(command))return RuntimeControllerEntryClosures.list(broker,input);
        if("launchRecovery".equals(command))return RuntimeControllerEntryClosures.recovery(broker,input);
        if("entryClosureReceipt".equals(command))return RuntimeControllerEntryClosures.receipt(broker,input);
        if("closeLaunchEntry".equals(command)){RuntimeControllerService.agents().requireUiControl(input.getString("project"),"closeLaunchEntry");return RuntimeControllerEntryClosures.close(broker,input);}
        if("prepareLaunch".equals(command)||"prepareApplicationLaunch".equals(command))return RuntimeControllerLaunch.prepare(broker,input,lease);
        if("abandonLaunch".equals(command))return RuntimeControllerLaunch.abandon(broker,input,lease);
        if("stopApplication".equals(command))return RuntimeControllerApplications.stop(broker,input,lease);
        if("uninstallApplication".equals(command))return RuntimeControllerApplications.uninstall(broker,input,lease);
        if("reverifyInstall".equals(command)){
            fields(input,"command","space","package","generation");
            synchronized(broker){RuntimeControllerService.requireLease(lease);}
            broker.apks.reverify(ControlWire.required(input,"space"),ControlWire.required(input,"package"),ControlWire.required(input,"generation"));
            out.putString("generation",ControlWire.required(input,"generation"));return out;
        }
        if("deleteApplication".equals(command))return RuntimeControllerApplications.delete(broker,input,lease);
        if("deleteProject".equals(command))return RuntimeControllerProjects.delete(broker,input,lease);
        if("applicationTarget".equals(command)){
            fields(input,"command","space","package","generation");return new ApplicationProjects(broker).target(input.getString("space"),input.getString("package"),input.getString("generation"));
        }
        if("selectProjectTarget".equals(command)){
            fields(input,"command","operation","project","space","package","generation");
            Bundle target=new ApplicationProjects(broker).target(input.getString("space"),input.getString("package"),input.getString("generation"));
            if(!input.getString("project").equals(target.getString("project")))throw new IllegalStateException("PROJECT_PACKAGE_CHANGE_UNSUPPORTED");
            synchronized(broker){String operation=input.getString("operation"),digest=ApkStore.textDigest("select-target-v1\n"+input.getString("project")+"\n"+input.getString("space")+"\n"+input.getString("generation"));var accepted=broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest);if(accepted.created){broker.contract.start(operation);var db=broker.store.getWritableDatabase();db.beginTransaction();try{new ApplicationProjects(broker).select(input.getString("project"),input.getString("space"));broker.contract.succeed(operation,"{}");db.setTransactionSuccessful();}finally{db.endTransaction();}}else if(accepted.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED");}
            return target;
        }
        if("deleteSpace".equals(command))return RuntimeControllerSpaces.delete(broker,input,lease);
        if("hookProcesses".equals(command))return RuntimeControllerHooks.processes(broker,input);
        if("hook".equals(command)){RuntimeControllerService.agents().requireUiControl(input.getString("project"),new JSONObject(input.getString("request")).getString("command"));return RuntimeControllerHooks.execute(broker,input,lease);}
        if("bindProject".equals(command)){
            RuntimeControllerService.agents().requireUiControl(input.getString("project"),"bindProject");
            if(input.containsKey("expectedBinding"))fields(input,"command","operation","project","space","package","generation","expectedBinding");else fields(input,"command","operation","project","space","package","generation");
            return new ProjectTargetBindings(broker).bind(ControlWire.required(input,"operation"),ControllerTrust.OPERATIONS,ControlWire.required(input,"project"),ControlWire.required(input,"space"),ControlWire.required(input,"package"),ControlWire.required(input,"generation"),input.getString("expectedBinding"));
        }
        if("setApplicationMode".equals(command)){fields(input,"command","operation","project","space","generation","mode");return new ProjectTargetBindings(broker).setModeIn(input.getString("operation"),ControllerTrust.OPERATIONS,input.getString("project"),input.getString("space"),input.getString("generation"),input.getString("mode"));}
        if("setProjectMode".equals(command)){
            RuntimeControllerService.agents().requireUiControl(input.getString("project"),"setProjectMode");
            fields(input,"command","operation","project","mode");
            synchronized(broker){RuntimeControllerService.requireLease(lease);}
            return new ProjectTargetBindings(broker).setMode(ControlWire.required(input,"operation"),ControllerTrust.OPERATIONS,ControlWire.required(input,"project"),ControlWire.required(input,"mode"));
        }
        if("resolveProject".equals(command)){fields(input,"command","project");return new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project"));}
        synchronized(broker){
            switch(command){
                case "status":
                    fields(input,"command");out.putString("authority",broker.store.authorityGeneration);out.putString("lease",lease);out.putInt("engineUid",android.os.Process.myUid());out.putInt("enginePid",android.os.Process.myPid());out.putInt("controllerUid",android.os.Process.myUid());out.putInt("schema",broker.store.getReadableDatabase().getVersion());out.putBoolean("strongIsolation",false);out.putBoolean("hookReady",true);break;
                case "createSpace":{
                    fields(input,"command","space","operation");String space=ControlWire.required(input,"space"),operation=ControlWire.required(input,"operation");RuntimeFiles.id(space);
                    String digest=ApkStore.textDigest("controller-create-space-v1\n"+space);var admission=broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest);
                    if(!admission.created){if(admission.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED:"+admission.operation.state);out.putString("space",admission.operation.result);break;}
                    broker.contract.start(operation);var db=broker.store.getWritableDatabase();
                    try{db.beginTransaction();try{broker.catalog.createSpace(space);broker.contract.succeed(operation,space);db.setTransactionSuccessful();}finally{db.endTransaction();}}
                    catch(Exception error){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING)broker.contract.markUnknown(operation,"SPACE_CREATION_PERSISTENCE_UNCONFIRMED");throw error;}
                    out.putString("space",space);break;
                }
                case "query":{
                    fields(input,"command","operation");var operation=broker.contract.query(ControlWire.required(input,"operation"));if(operation==null)throw new IllegalStateException("OPERATION_NOT_FOUND");
                    if(!ControllerTrust.OPERATIONS.equals(operation.callerIdentity))throw new SecurityException("OPERATION_OWNER_MISMATCH");out.putString("state",operation.state.name());out.putString("result",operation.result);out.putString("detail",operation.detail);out.putString("evidence",operation.evidence);break;
                }
                case "cancelNotAccepted":{
                    fields(input,"command","operation");String id=ControlWire.required(input,"operation");var operation=broker.contract.query(id);
                    if(operation==null){var db=broker.store.getWritableDatabase();db.beginTransaction();try{broker.contract.accept(id,ControllerTrust.OPERATIONS,ApkStore.textDigest("cancel-before-admission-v1\n"+id));broker.contract.start(id);operation=broker.contract.fail(id,"CANCELED_BEFORE_ACCEPT");db.setTransactionSuccessful();}finally{db.endTransaction();}}
                    if(!ControllerTrust.OPERATIONS.equals(operation.callerIdentity))throw new SecurityException("OPERATION_OWNER_MISMATCH");
                    out.putString("state",operation.state.name());out.putString("result",operation.result);out.putString("detail",operation.detail);break;
                }
                default:throw new UnsupportedOperationException("CONTROL_COMMAND_UNSUPPORTED:"+command);
            }
        }
        return out;
    }
    static void fields(Bundle input,String...names){if(!input.keySet().equals(Set.of(names)))throw new IllegalArgumentException("CONTROL_FIELDS_UNSUPPORTED");for(String name:names)if(!(input.get(name) instanceof String))throw new IllegalArgumentException("CONTROL_FIELD_TYPE:"+name);}
    static void targetFields(Bundle input,String...names){if(input.containsKey("bindingOperation")&&!java.util.Arrays.asList(names).contains("bindingOperation")){String[] all=java.util.Arrays.copyOf(names,names.length+1);all[names.length]="bindingOperation";fields(input,all);}else fields(input,names);}
    private RuntimeControllerCommands(){}
}
