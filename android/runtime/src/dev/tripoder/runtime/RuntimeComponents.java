package dev.tripoder.runtime;

import android.content.pm.*;

/** Declaration-driven resolution. Platform PackageManager is used only to decode this immutable APK. */
final class RuntimeComponents {
    static ApkManifest.Component declared(ApkStore.BundleInstall install,String type,String name){
        return declared(install,type,name,false);
    }
    static ApkManifest.Component declared(ApkStore.BundleInstall install,String type,String name,boolean allowDisabled){
        for(var component:install.components())if(component.type.equals(type)&&component.name.equals(name)){
            if(!allowDisabled&&!component.enabled)throw new IllegalStateException("COMPONENT_DISABLED");return component;
        }
        throw new IllegalArgumentException("COMPONENT_NOT_DECLARED:"+type+":"+name);
    }
    static ActivityInfo activity(ApkStore.BundleInstall install,String name){
        ActivityInfo info=RuntimeActivityIndex.require(install.activityIndex(),name);
        // finishOnTaskLaunch / clearTaskOnLaunch / alwaysRetainTaskState / noHistory /
        // relinquishTaskIdentity / autoRemoveFromRecents are task-presentation behaviours.
        // The guest runs on a physical stub whose own manifest defines the task, so the
        // platform applies them best-effort and none of them creates a second task root.
        // Rejecting them turned a cosmetic task difference into a hard launch failure for
        // ordinary launcher activities (e.g. Weibo/Amap splash declare alwaysRetainTaskState),
        // so they are accepted. documentLaunchMode is still refused: it spawns separate
        // document tasks, which the single-root launch-ticket ledger does not model.
        if(info.documentLaunchMode!=ActivityInfo.DOCUMENT_LAUNCH_NONE)throw new UnsupportedOperationException("ACTIVITY_TASK_POLICY_UNSUPPORTED");
        if(info.launchMode<ActivityInfo.LAUNCH_MULTIPLE||info.launchMode>ActivityInfo.LAUNCH_SINGLE_INSTANCE)throw new IllegalStateException("ACTIVITY_LAUNCH_MODE_UNSUPPORTED");return info;
    }
    static boolean reparents(ActivityInfo info){return info.launchMode<ActivityInfo.LAUNCH_SINGLE_TASK&&(info.flags&ActivityInfo.FLAG_ALLOW_TASK_REPARENTING)!=0;}
    static void authorize(ActivityInfo component,String callerPackage){
        if(!callerPackage.equals(component.packageName)){
            if(!component.exported)throw new SecurityException("COMPONENT_NOT_EXPORTED");
            if(component.permission!=null&&!component.permission.isEmpty())throw new SecurityException("COMPONENT_PERMISSION_UNSUPPORTED");
        }
    }
    static void authorize(ApkManifest.Component component,String targetPackage,String callerPackage){
        if(!callerPackage.equals(targetPackage)){
            if(!component.exported)throw new SecurityException("COMPONENT_NOT_EXPORTED");
            if(!component.permission.isEmpty())throw new SecurityException("COMPONENT_PERMISSION_UNSUPPORTED");
        }
    }
    static void requireExecutable(ApkStore.BundleInstall install){
        if(install.artifacts.parts.isEmpty())throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        for(var c:install.components())if(!c.type.equals("activity")&&!c.type.equals("activity-alias")&&!c.type.equals("service")&&!c.type.equals("provider")&&!c.type.equals("receiver"))throw new IllegalStateException("RUNTIME_COMPONENT_SET_NOT_SUPPORTED");
    }
    private RuntimeComponents(){}
}
