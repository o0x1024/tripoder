package dev.tripoder.selfhook;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Handler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/** Three measured SDK HttpEngine callback types in the existing fixed boot profile. */
public final class SystemReceiverAccess {
    private static boolean attempted;
    private static volatile SystemReceiverAccess instance;
    private final Class<?> network,proxy,emptyProxy;
    private SystemReceiverAccess(List<Method> selected)throws Exception{
        network=FixedFrameworkMembers.owner(80);proxy=FixedFrameworkMembers.owner(81);emptyProxy=FixedFrameworkMembers.owner(82);
        for(Class<?> type:List.of(network,proxy,emptyProxy)){
            if(type.getClassLoader()!=Class.class.getClassLoader()||type.getSuperclass()!=BroadcastReceiver.class)throw new SecurityException("SYSTEM_RECEIVER_BOOT_IDENTITY");
            Method callback=FixedFrameworkMembers.method(type==network?80:type==proxy?81:82,"onReceive","(Landroid/content/Context;Landroid/content/Intent;)V",false);
            if(!selected.contains(callback))throw new SecurityException("SYSTEM_RECEIVER_FIXED_CALLBACK_REQUIRED");
        }
    }
    static synchronized void prepare(List<Method> selected)throws Exception{
        if(attempted)throw new IllegalStateException("SYSTEM_RECEIVER_PROFILE_ALREADY_ATTEMPTED");attempted=true;instance=new SystemReceiverAccess(selected);
    }
    private static SystemReceiverAccess require(){SystemReceiverAccess value=instance;if(value==null)throw new IllegalStateException("SYSTEM_RECEIVER_PROFILE_NOT_PREPARED");return value;}
    public static boolean ownsType(Class<?> type){SystemReceiverAccess value=require();return type==value.network||type==value.proxy||type==value.emptyProxy;}
    /** A single base-class copy is both validated here and serialized by the registration route. */
    public static IntentFilter registrationFilter(Class<?> type,IntentFilter supplied,String permission,Handler scheduler,int flags){
        SystemReceiverAccess value=require();if(!ownsType(type))throw new SecurityException("SYSTEM_RECEIVER_TYPE_UNSUPPORTED");
        IntentFilter filter=new IntentFilter(Objects.requireNonNull(supplied,"RECEIVER_FILTER_REQUIRED"));
        String action=type==value.network?"android.net.conn.CONNECTIVITY_CHANGE":type==value.proxy?"android.intent.action.PROXY_CHANGE":null;
        int expectedFlags=type==value.emptyProxy?android.content.Context.RECEIVER_NOT_EXPORTED:0;
        if(permission!=null||scheduler!=null||flags!=expectedFlags||filter.getPriority()!=0||filter.countCategories()!=0||filter.countDataSchemes()!=0||filter.countDataTypes()!=0||filter.countDataAuthorities()!=0||filter.countDataPaths()!=0||filter.countDataSchemeSpecificParts()!=0||filter.countUriRelativeFilterGroups()!=0
                ||filter.countActions()!=(action==null?0:1)||action!=null&&!action.equals(filter.getAction(0)))throw new SecurityException("SYSTEM_RECEIVER_REGISTRATION_PROFILE_CHANGED");
        return filter;
    }
}
