package dev.tripoder.selfhook;

import android.app.Application;
import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Looper;
import android.os.Process;

/** One immutable logical application per process; fixed framework access only. */
public final class ApplicationAdapter {
    private ApplicationAdapter() {}
    private static native void prepareOwnershipNative(Application host,java.lang.reflect.Field[] fields,Object[] methods);
    private static native void beginViewNative();
    private static native Context createNative(Application host,Application guest,ApplicationInfo info,
        ClassLoader loader,AppComponentFactory factory,Resources resources);
    public static native Application current();
    private static native void attachNative(Context context);
    private static native void checkNative();
    private static native void closeViewNative();
    public static native String reportNative();
    private static void mainThread(){
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("APPLICATION_MAIN_THREAD_REQUIRED");
    }
    /** Before any guest factory, class initializer or constructor executes.
     * No guest instance exists yet; the logical view is explicitly null. */
    public static void prepareOwnership(Application host,FrameworkAccess.Members members){
        if(host==null||members==null)throw new IllegalArgumentException("APPLICATION_FIXED_IDENTITY_REQUIRED");
        prepareOwnershipNative(host,members.fields,members.methods);
    }
    /** Publish the pending logical reflection view only after ownership capture. */
    public static void beginView(){beginViewNative();}
    public static Context create(Application host,Application guest,ApplicationInfo info,ClassLoader loader,
        AppComponentFactory factory,Resources resources){
        mainThread();
        if(host==null||guest==null||host==guest||info==null||loader==null||factory==null||resources==null||
            info.uid!=Process.myUid()||info.packageName==null||info.dataDir==null)
            throw new IllegalArgumentException("APPLICATION_FIXED_IDENTITY_REQUIRED");
        try{
            if(Class.forName(guest.getClass().getName(),false,loader)!=guest.getClass())
                throw new IllegalArgumentException("APPLICATION_SELECTED_LOADER_IDENTITY");
        }catch(ClassNotFoundException error){throw new IllegalArgumentException("APPLICATION_SELECTED_LOADER_IDENTITY",error);}
        return createNative(host,guest,info,loader,factory,resources);
    }
    public static void attach(Context context){mainThread();attachNative(context);}
    public static void check(){mainThread();checkNative();}
    /** Probe teardown only. Roots/code remain resident; this is not object retirement. */
    public static void closeProbeView(){mainThread();closeViewNative();}
}
