package dev.tripoder.runtime;

import android.content.pm.*;

/** Declaration-driven resolution. Platform PackageManager is used only to decode this immutable APK. */
final class RuntimeComponents {
    static ApkManifest.Component declared(ApkStore.BundleInstall install,String type,String name){
        for(var component:install.components())if(component.type.equals(type)&&component.name.equals(name)){
            if(!component.enabled)throw new IllegalStateException("COMPONENT_DISABLED");return component;
        }
        throw new IllegalArgumentException("COMPONENT_NOT_DECLARED:"+type+":"+name);
    }
    static ActivityInfo activity(ApkStore.BundleInstall install,String name){
        ActivityInfo info=RuntimeActivityIndex.require(install.activityIndex(),name);
        int unsupported=ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH|ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH|ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE|ActivityInfo.FLAG_NO_HISTORY|ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY|ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS;
        if((info.flags&unsupported)!=0||info.documentLaunchMode!=ActivityInfo.DOCUMENT_LAUNCH_NONE)throw new UnsupportedOperationException("ACTIVITY_TASK_POLICY_UNSUPPORTED");
        if(info.launchMode<ActivityInfo.LAUNCH_MULTIPLE||info.launchMode>ActivityInfo.LAUNCH_SINGLE_INSTANCE)throw new IllegalStateException("ACTIVITY_LAUNCH_MODE_UNSUPPORTED");return info;
    }
    static boolean reparents(ActivityInfo info){return info.launchMode<ActivityInfo.LAUNCH_SINGLE_TASK&&(info.flags&ActivityInfo.FLAG_ALLOW_TASK_REPARENTING)!=0;}
    static void authorize(ActivityInfo component,String callerPackage){
        if(!callerPackage.equals(component.packageName)&&!component.exported)throw new SecurityException("COMPONENT_NOT_EXPORTED");
        if(component.permission!=null&&!component.permission.isEmpty())throw new SecurityException("COMPONENT_PERMISSION_UNSUPPORTED");
    }
    static void authorize(ApkManifest.Component component,String targetPackage,String callerPackage){
        if(!callerPackage.equals(targetPackage)&&!component.exported)throw new SecurityException("COMPONENT_NOT_EXPORTED");
        if(!component.permission.isEmpty())throw new SecurityException("COMPONENT_PERMISSION_UNSUPPORTED");
    }
    static void requireExecutable(ApkStore.BundleInstall install){
        if(install.artifacts.parts.size()!=1)throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        for(var c:install.components())if(!c.type.equals("activity")&&!c.type.equals("activity-alias")&&!c.type.equals("service")&&!c.type.equals("provider")&&!c.type.equals("receiver"))throw new IllegalStateException("RUNTIME_COMPONENT_SET_NOT_SUPPORTED");
    }
    private RuntimeComponents(){}
}
