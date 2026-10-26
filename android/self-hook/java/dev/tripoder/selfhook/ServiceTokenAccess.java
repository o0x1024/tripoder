package dev.tripoder.selfhook;

import android.app.Service;
import android.content.ComponentName;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import android.os.IBinder;

/** Original physical Service manager/token call on the invoking thread. */
public final class ServiceTokenAccess {
    private static boolean attempted;
    private static volatile boolean ready;
    private static Class<?> managerType;
    private static Method stopMember;
    private ServiceTokenAccess() {}
    private static native boolean prepareNative(Class<?> service,Class<?> manager,Field managerField,Field tokenField,Method stop);
    private static native boolean stopNative(Service service,ComponentName physicalComponent,int startId)throws Exception;
    public static synchronized void prepare() throws Exception {
        if(attempted)throw new IllegalStateException("SERVICE_TOKEN_PREPARE_ALREADY_ATTEMPTED");attempted=true;
        Object[] group=FrameworkAccess.serviceTokenMembers();
        if(!prepareNative((Class<?>)group[0],(Class<?>)group[1],(Field)group[2],(Field)group[3],(Method)group[4]))
            throw new IllegalStateException("SERVICE_TOKEN_NATIVE_PREPARE_FAILED");
        managerType=(Class<?>)group[1];stopMember=(Method)group[4];stopMember.setAccessible(true);
        ready=true;
    }
    /** Per-instance physical manager boundary, also reached by compiled SDK callers. */
    public static Object scopedManager(Object physicalManager,IBinder physicalToken,ComponentName physicalComponent,String logicalPackage,boolean trace){
        if(!ready)throw new IllegalStateException("SERVICE_TOKEN_ACCESS_NOT_READY");
        if(!managerType.isInstance(physicalManager)||physicalToken==null||physicalComponent==null||logicalPackage==null||logicalPackage.isEmpty())throw new IllegalArgumentException("SERVICE_MANAGER_PHYSICAL_IDENTITY_REQUIRED");
        ComponentName sdkComponent=new ComponentName(logicalPackage,physicalComponent.getClassName());
        return Proxy.newProxyInstance(managerType.getClassLoader(),new Class<?>[]{managerType},(proxy,method,arguments)->{
            if(method.equals(stopMember)){
                if(arguments==null||arguments.length!=3||arguments[1]!=physicalToken)throw new SecurityException("SERVICE_STOP_TOKEN_MISMATCH");
                if(!physicalComponent.equals(arguments[0])&&!sdkComponent.equals(arguments[0]))throw new SecurityException("SERVICE_STOP_COMPONENT_MISMATCH");
                Object[] physical=arguments.clone();physical[0]=physicalComponent;
                if(trace)android.util.Log.i("TripoderServiceToken","SCOPED_STOP:startId="+arguments[2]+":physicalSelector="+physicalComponent.equals(arguments[0])+":sdkMixedSelector="+sdkComponent.equals(arguments[0])+":tokenExact=true");
                Object result=FixedFrameworkMembers.call(stopMember,physicalManager,physical);
                if(trace)android.util.Log.i("TripoderServiceToken","SCOPED_RESULT:startId="+arguments[2]+":stopped="+result);
                return result;
            }
            return FixedFrameworkMembers.call(method,physicalManager,arguments);
        });
    }
    public static boolean stop(Service service,ComponentName physicalComponent,int startId)throws Exception {
        if(!ready)throw new IllegalStateException("SERVICE_TOKEN_ACCESS_NOT_READY");
        if(service==null||physicalComponent==null)throw new IllegalArgumentException("SERVICE_TOKEN_PHYSICAL_IDENTITY_REQUIRED");
        return stopNative(service,physicalComponent,startId);
    }
}
