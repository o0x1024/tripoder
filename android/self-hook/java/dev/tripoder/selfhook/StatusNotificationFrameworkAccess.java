package dev.tripoder.selfhook;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Preserves the real post PID, UID, user and timestamp while projecting owned identity. */
public final class StatusNotificationFrameworkAccess {
    private static Constructor<?> constructor;
    private static Method initialPid;
    private static synchronized void prepare(){
        if(constructor!=null)return;
        try{
            Constructor<?> create=FixedFrameworkMembers.constructor(67,"(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;IILandroid/app/Notification;Landroid/os/UserHandle;Ljava/lang/String;J)V");
            Method pid=FixedFrameworkMembers.method(67,"getInitialPid","()I",false);constructor=create;initialPid=pid;
        }catch(Exception error){throw new IllegalStateException("NOTIFICATION_STATUS_FRAMEWORK_UNAVAILABLE",error);}
    }
    public static StatusBarNotification project(StatusBarNotification physical,String pkg,String tag,Notification notification){
        prepare();return (StatusBarNotification)FixedFrameworkMembers.create(constructor,pkg,physical.getOpPkg(),physical.getId(),tag,physical.getUid(),(Integer)FixedFrameworkMembers.call(initialPid,physical),notification,physical.getUser(),physical.getOverrideGroupKey(),physical.getPostTime());
    }
    private StatusNotificationFrameworkAccess(){}
}
