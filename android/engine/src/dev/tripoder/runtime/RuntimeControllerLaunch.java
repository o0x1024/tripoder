package dev.tripoder.runtime;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.*;

/** Installation ingress with an optional project binding; both use the same one-use gateway. */
final class RuntimeControllerLaunch {
    static Bundle targets(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.fields(input,"command","project");Bundle target=new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project"));
        var install=broker.apks.get(target.getString("space"),target.getString("package"));if(!install.generation.equals(target.getString("generation")))throw new IllegalStateException("LAUNCH_BINDING_STALE");
        JSONArray list=new JSONArray();for(var info:install.activityIndex().values()){
            if(!info.exported||!info.enabled||!info.applicationInfo.enabled||info.permission!=null&&!info.permission.isEmpty())continue;
            // UI lists actual declarations, including ones whose task policy is not implemented.
            String unsupported="";try{RuntimeComponents.activity(install,info.name);}catch(UnsupportedOperationException|IllegalStateException error){unsupported=error.getMessage();}
            list.put(new JSONObject().put("component",info.name).put("launcher",launcher(install,info.name)).put("supported",unsupported.isEmpty()).put("reason",unsupported));
        }
        if(list.length()>2048)throw new IllegalStateException("ACTIVITY_TARGET_PAGE_REQUIRED");target.putString("targets",list.toString());return target;
    }
    static Bundle prepare(RuntimeBroker broker,Bundle input,String lease,int uid)throws Exception{
        boolean application="prepareApplicationLaunch".equals(input.getString("command"));
        if(application)RuntimeControllerCommands.fields(input,"command","operation","space","package","generation");
        else RuntimeControllerCommands.fields(input,"command","operation","project","bindingOperation","generation","component");
        String operation=ControlWire.required(input,"operation"),generation=ControlWire.required(input,"generation");
        String project=application?"":ControlWire.required(input,"project"),binding=application?"":ControlWire.required(input,"bindingOperation");
        JSONObject identity=new JSONObject().put("kind",application?"controller-application-v1":"controller-activity-v1").put("generation",generation);
        if(application)identity.put("space",ControlWire.required(input,"space")).put("package",ControlWire.required(input,"package"));
        else identity.put("project",project).put("binding",binding).put("component",ControlWire.required(input,"component"));
        String digest=ApkStore.textDigest(identity.toString());
        synchronized(broker){var accepted=broker.contract.accept(operation,"trusted-workbench",digest);if(!accepted.created)throw new IllegalStateException("LAUNCH_ALREADY_ACCEPTED_QUERY_REQUIRED");broker.contract.start(operation);}
        boolean started=false;
        try{
            Bundle target;
            if(application){target=new Bundle();target.putString("project","");target.putString("bindingOperation","");target.putString("space",input.getString("space"));target.putString("package",input.getString("package"));target.putString("generation",generation);}
            else target=new ProjectTargetBindings(broker).resolve(project);
            if(!binding.equals(target.getString("bindingOperation"))||!generation.equals(target.getString("generation")))throw new IllegalStateException("LAUNCH_BINDING_STALE");
            var install=broker.apks.get(target.getString("space"),target.getString("package"));if(!generation.equals(install.generation))throw new IllegalStateException("LAUNCH_BINDING_STALE");
            String component=application?launcherComponent(install):input.getString("component");
            ActivityInfo activity=RuntimeComponents.activity(install,component);RuntimeComponents.authorize(activity,ControllerTrust.PACKAGE);
            // Retire authenticated empty tasks before deciding whether a root remains
            // open. The same proof-based maintenance is used by ProcessSupervisor.
            RuntimeEmptyTasks.reclaim(broker,null);
            synchronized(broker){requireNoActiveRoot(broker,install.space,install.info.packageName,generation);}
            started=true;var slot=broker.processes.ensure(install.space,install.info.packageName,activity.processName);
            synchronized(broker){
                RuntimeControllerService.requireLease(lease,uid);
                var current=broker.apks.row(install.space,install.info.packageName);if(current==null||!"COMMITTED".equals(current.state)||!generation.equals(current.generation)||!slot.install.generation.equals(generation))throw new IllegalStateException("LAUNCH_INSTALL_STALE");
                requireNoActiveRoot(broker,install.space,install.info.packageName,generation);
                if(!application)try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM project_refs WHERE id=? AND operation=? AND state='COMMITTED' AND kind='DIRECT'",new String[]{project,binding})){if(!c.moveToFirst())throw new IllegalStateException("LAUNCH_BINDING_STALE");}
                Bundle out=broker.processEvidence(slot);out.putString("operation",operation);out.putString("capability",new ControllerLaunchLedger(broker).prepare(operation,lease,uid,target,slot,component,launcher(install,component)));out.putString("component",component);return out;
            }
        }catch(Exception error){synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){if(started)broker.contract.markUnknown(operation,"LAUNCH_PREPARATION_UNCONFIRMED");else broker.contract.fail(operation,"LAUNCH_TARGET_REJECTED");}}throw error;}
    }
    private static String launcherComponent(ApkStore.BundleInstall install){
        String selected=null;
        for(var info:install.activityIndex().values())if(info.enabled&&info.applicationInfo.enabled&&info.exported&&(info.permission==null||info.permission.isEmpty())&&launcher(install,info.name)){
            if(selected!=null)throw new IllegalStateException("APPLICATION_HAS_MULTIPLE_LAUNCHERS");selected=info.name;
        }
        if(selected==null)throw new IllegalStateException("APPLICATION_HAS_NO_LAUNCHER");return selected;
    }
    static void requireNoActiveRoot(RuntimeBroker broker,String space,String pkg,String generation){
        ActivityEntryFences.requireNoOpenRoot(broker.store,space,pkg,generation);
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM controller_launches WHERE space=? AND package=? AND installGeneration=? AND authority=? AND state='READY' AND deadline>=? LIMIT 1",new String[]{space,pkg,generation,broker.store.authorityGeneration,Long.toString(android.os.SystemClock.elapsedRealtime())})){if(c.moveToFirst())throw new IllegalStateException("APPLICATION_LAUNCH_ALREADY_PREPARED");}
    }
    private static boolean launcher(ApkStore.BundleInstall install,String name){
        for(var component:install.artifacts.components)if(component.name.equals(name))for(var filter:component.declaration.children)if(filter.name.equals("intent-filter")){
            boolean main=false,launcher=false;for(var child:filter.children){if(child.name.equals("action")&&Intent.ACTION_MAIN.equals(child.android("name","")))main=true;if(child.name.equals("category")&&Intent.CATEGORY_LAUNCHER.equals(child.android("name","")))launcher=true;}if(main&&launcher)return true;
        }return false;
    }
}
