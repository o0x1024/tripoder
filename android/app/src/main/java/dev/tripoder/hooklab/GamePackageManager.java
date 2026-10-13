package dev.tripoder.hooklab;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ActivityInfo;
import android.content.ComponentName;
import android.content.res.Resources;

/** Resolve self-relaunches to the host, including PendingIntent/AlarmManager restarts. */
final class GamePackageManager extends ForwardingPackageManager {
    private final GameRuntime runtime;
    GamePackageManager(PackageManager delegate,GameRuntime runtime){super(delegate);this.runtime=runtime;}
    @Override public ApplicationInfo getApplicationInfo(String name,int flags) throws NameNotFoundException {
        return GameRuntime.PACKAGE.equals(name)?new ApplicationInfo(runtime.info):delegate.getApplicationInfo(name,flags);
    }
    @Override public PackageInfo getPackageInfo(String name,int flags) throws NameNotFoundException {
        PackageInfo original=delegate.getPackageInfo(name,flags);if(!GameRuntime.PACKAGE.equals(name))return original;
        android.os.Parcel parcel=android.os.Parcel.obtain();
        try{original.writeToParcel(parcel,0);parcel.setDataPosition(0);PackageInfo copy=PackageInfo.CREATOR.createFromParcel(parcel);copy.applicationInfo=new ApplicationInfo(runtime.info);return copy;}finally{parcel.recycle();}
    }
    @Override public ActivityInfo getActivityInfo(ComponentName component,int flags) throws NameNotFoundException {
        ActivityInfo result=delegate.getActivityInfo(component,flags);if(GameRuntime.PACKAGE.equals(component.getPackageName())){result=new ActivityInfo(result);result.applicationInfo=new ApplicationInfo(runtime.info);}return result;
    }
    @Override public Resources getResourcesForApplication(ApplicationInfo info) throws NameNotFoundException { return GameRuntime.PACKAGE.equals(info.packageName)?runtime.resources:delegate.getResourcesForApplication(info); }
    @Override public Resources getResourcesForApplication(String name) throws NameNotFoundException { return GameRuntime.PACKAGE.equals(name)?runtime.resources:delegate.getResourcesForApplication(name); }
    @Override public Intent getLaunchIntentForPackage(String packageName){
        if(!GameRuntime.PACKAGE.equals(packageName))return delegate.getLaunchIntentForPackage(packageName);
        runtime.event("self-relaunch-routed");
        return ProjectStore.intent(runtime.host,GameLaunchActivity.class,runtime.projectId)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("freshGameProcess",true);
    }
}
