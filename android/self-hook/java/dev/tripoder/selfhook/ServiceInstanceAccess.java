package dev.tripoder.selfhook;

import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;

/** Attach a delegate using this exact physical Service's framework-owned token. */
public final class ServiceInstanceAccess {
    public static void attach(Service physical,Service guest,Context context,Application application)throws Exception{
        if(!android.os.Process.isIsolated()||physical==null||guest==null||physical==guest||guest.getBaseContext()!=null)throw new SecurityException("ISOLATED_SERVICE_ATTACH_IDENTITY");
        var token=FixedFrameworkMembers.field(13,13,"mToken","Landroid/os/IBinder;",false);
        var manager=FixedFrameworkMembers.field(12,13,"mActivityManager","Landroid/app/IActivityManager;",false);
        IBinder actual=(IBinder)FixedFrameworkMembers.get(token,physical);Object am=FixedFrameworkMembers.get(manager,physical);
        ComponentName component=new ComponentName(physical,physical.getClass());
        Object scoped=ServiceTokenAccess.scopedManager(am,actual,component,context.getPackageName(),false);
        Object thread=FixedFrameworkMembers.call(FixedFrameworkMembers.method(1,"currentActivityThread","()Landroid/app/ActivityThread;",true),null);
        FixedFrameworkMembers.call(FixedFrameworkMembers.method(13,"attach","(Landroid/content/Context;Landroid/app/ActivityThread;Ljava/lang/String;Landroid/os/IBinder;Landroid/app/Application;Ljava/lang/Object;)V",false),guest,context,thread,component.getClassName(),actual,application,scoped);
        if(guest.getBaseContext()!=context||guest.getApplication()!=application||FixedFrameworkMembers.get(token,guest)!=actual||FixedFrameworkMembers.get(manager,guest)!=scoped)throw new SecurityException("ISOLATED_SERVICE_ATTACH_NOT_COMMITTED");
    }
    private ServiceInstanceAccess(){}
}
