package dev.tripoder.selfhook;

import android.app.Service;
import android.content.ComponentName;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Original physical Service manager/token call on the invoking thread. */
public final class ServiceTokenAccess {
    private static boolean attempted;
    private static volatile boolean ready;
    private ServiceTokenAccess() {}
    private static native boolean prepareNative(Class<?> service,Class<?> manager,Field managerField,Field tokenField,Method stop);
    private static native boolean stopNative(Service service,ComponentName physicalComponent,int startId)throws Exception;
    public static synchronized void prepare() throws Exception {
        if(attempted)throw new IllegalStateException("SERVICE_TOKEN_PREPARE_ALREADY_ATTEMPTED");attempted=true;
        Object[] group=FrameworkAccess.serviceTokenMembers();
        if(!prepareNative((Class<?>)group[0],(Class<?>)group[1],(Field)group[2],(Field)group[3],(Method)group[4]))
            throw new IllegalStateException("SERVICE_TOKEN_NATIVE_PREPARE_FAILED");
        ready=true;
    }
    public static boolean stop(Service service,ComponentName physicalComponent,int startId)throws Exception {
        if(!ready)throw new IllegalStateException("SERVICE_TOKEN_ACCESS_NOT_READY");
        if(service==null||physicalComponent==null)throw new IllegalArgumentException("SERVICE_TOKEN_PHYSICAL_IDENTITY_REQUIRED");
        return stopNative(service,physicalComponent,startId);
    }
}
