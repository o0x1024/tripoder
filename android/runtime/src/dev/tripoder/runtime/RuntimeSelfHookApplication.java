package dev.tripoder.runtime;

import android.app.Application;
import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Looper;
import dev.tripoder.selfhook.ApplicationAdapter;
import dev.tripoder.selfhook.FrameworkAccess;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Direct engine-owned seams. Framework interception remains a separate required installation. */
final class RuntimeSelfHookApplication {
    /** Fixed JNI field reads only: a managed currentApplication call is not a physical bypass. */
    interface PhysicalAccess {
        void prepare(Application expectedHost,FrameworkAccess.Members members);
        void check(Application expectedHost);
    }
    private static volatile RuntimeSelfHookApplication current;
    private static boolean attempted;
    private final RuntimeEngine engine;
    private final Application host;
    private final FrameworkAccess.Members members;
    private final Method addFinisher;
    private final PhysicalAccess physicalAccess;
    private Application guest;
    private boolean ownershipPrepared,viewPrepared,creationAttempted,attachAttempted,attached;

    static synchronized void prepareFramework(RuntimeEngine engine,PhysicalAccess physicalAccess) throws Exception {
        if(attempted)throw new IllegalStateException("SELF_APPLICATION_PREPARE_ALREADY_ATTEMPTED");
        attempted=true;
        Objects.requireNonNull(engine,"SELF_APPLICATION_ENGINE_REQUIRED");
        if(engine!=RuntimeEngine.current||!(engine.host instanceof Application))
            throw new SecurityException("SELF_APPLICATION_PHYSICAL_HOST_REQUIRED");
        Objects.requireNonNull(physicalAccess,"SELF_APPLICATION_PHYSICAL_ACCESS_REQUIRED");
        FrameworkAccess.Members members=RuntimeFixedPreparation.framework();
        physicalAccess.prepare((Application)engine.host,members);
        physicalAccess.check((Application)engine.host);
        current=new RuntimeSelfHookApplication(engine,members,physicalAccess);
    }

    static synchronized void prepareOwnership()throws Exception{
        RuntimeSelfHookApplication value=require();
        if(value.ownershipPrepared)throw new IllegalStateException("SELF_APPLICATION_OWNERSHIP_ALREADY_ATTEMPTED");
        value.ownershipPrepared=true;
        // ContentResolver.wrap uses a null-context platform constructor. Capture
        // the real infrastructure resolver while currentApplication is still
        // the physical host, before the pending logical view becomes visible.
        if(value.engine.settingsResolver().getClass()!=Class.forName("android.content.ContentResolver$1",false,null))
            throw new IllegalStateException("FIXED_WRAPPED_RESOLVER_IDENTITY_CHANGED");
        ApplicationAdapter.prepareOwnership(value.host,value.members);
    }

    static synchronized void prepareView(RuntimeManagedInstaller installer)throws Exception{
        RuntimeSelfHookApplication value=require();
        if(!value.ownershipPrepared||value.viewPrepared)throw new IllegalStateException("SELF_APPLICATION_VIEW_ORDER");
        value.viewPrepared=true;ApplicationAdapter.beginView();
        java.util.Map<java.lang.reflect.Executable,RuntimeManagedInstaller.Callback> callbacks=new java.util.LinkedHashMap<>();
        callbacks.put((Method)value.members.methods[1],(receiver,args,original)->ApplicationAdapter.current());
        callbacks.put(FrameworkAccess.activityThreadApplicationMethod(),(receiver,args,original)->ApplicationAdapter.current());
        callbacks.put(FrameworkAccess.processNameMethod(),(receiver,args,original)->value.engine.processName);
        installer.install(callbacks);
    }

    private RuntimeSelfHookApplication(RuntimeEngine engine,FrameworkAccess.Members members,PhysicalAccess physicalAccess) {
        this.engine=engine;host=(Application)engine.host;this.members=members;this.physicalAccess=physicalAccess;
        addFinisher=(Method)members.methods[7];addFinisher.setAccessible(true);
    }

    private static RuntimeSelfHookApplication require() {
        RuntimeSelfHookApplication value=current;
        if(value==null||RuntimeEngine.current!=value.engine)
            throw new IllegalStateException("SELF_APPLICATION_NOT_PREPARED");
        return value;
    }

    private static void mainThread() {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("APPLICATION_MAIN_THREAD_REQUIRED");
    }

    static Context create(Context physical,Application guest,ApplicationInfo info,ClassLoader loader,
                          AppComponentFactory factory,Resources resources) {
        mainThread();RuntimeSelfHookApplication value=require();
        if(value.creationAttempted)throw new IllegalStateException("SELF_APPLICATION_CREATE_ALREADY_ATTEMPTED");
        value.creationAttempted=true;
        if(physical!=value.host||guest==null||guest==value.host||guest.getBaseContext()!=null||guest!=value.engine.application||loader!=value.engine.loader||
            factory!=value.engine.componentFactory||resources!=value.engine.resources||info==null||
            !value.engine.packageName.equals(info.packageName)||!value.engine.info.sourceDir.equals(info.sourceDir)||
            !value.engine.info.dataDir.equals(info.dataDir)||info.uid!=value.engine.info.uid)
            throw new SecurityException("SELF_APPLICATION_ENGINE_IDENTITY_MISMATCH");
        // The verified manifest may select Android's exact default Application.
        // Its instance belongs to this guest, while its defining Class is boot.
        RuntimeSelfHookLoaders.requireApplication(loader,guest.getClass(),value.engine.platformDefaultApplication?Application.class:null,value.engine::ownsLoader);
        value.guest=guest;
        return ApplicationAdapter.create(value.host,guest,info,loader,factory,resources);
    }

    static void attach(Application guest,Context context) {
        mainThread();RuntimeSelfHookApplication value=require();
        if(guest==null||guest!=value.guest||context==null||value.attachAttempted)
            throw new SecurityException("SELF_APPLICATION_ATTACH_IDENTITY_MISMATCH");
        value.attachAttempted=true;
        ApplicationAdapter.attach(context);
        value.attached=true;
    }

    static void check(Context host,Application guest) {
        mainThread();RuntimeSelfHookApplication value=require();
        if(!value.attached||host!=value.host||guest!=value.guest)
            throw new SecurityException("SELF_APPLICATION_CHECK_IDENTITY_MISMATCH");
        ApplicationAdapter.check();
    }

    static void observe(String stage,Context physical,Application guest) {
        mainThread();RuntimeSelfHookApplication value=require();
        if(physical!=value.host||guest==null||guest!=value.engine.application||stage==null||
            !(stage.equals("before-attach")||stage.equals("after-attach")||stage.equals("after-onCreate")))
            throw new SecurityException("SELF_APPLICATION_OBSERVATION_IDENTITY_MISMATCH");
        value.physicalAccess.check(value.host);
        if(value.attached)ApplicationAdapter.check();
        android.util.Log.i("TripoderOwnership",stage+" host="+System.identityHashCode(value.host)+
            " guest="+System.identityHashCode(guest)+" "+ApplicationAdapter.reportNative());
    }

    static void addFinisher(Runnable finisher) {
        RuntimeSelfHookApplication value=require();
        invoke(value.addFinisher,Objects.requireNonNull(finisher,"STORAGE_FINISHER_REQUIRED"));
    }

    private static Object invoke(Method method,Object... args) {
        try {return method.invoke(null,args);}
        catch(InvocationTargetException error) {
            Throwable cause=error.getCause();
            if(cause instanceof RuntimeException)throw (RuntimeException)cause;
            if(cause instanceof Error)throw (Error)cause;
            throw new IllegalStateException("SELF_APPLICATION_FRAMEWORK_CALL_FAILED:"+method.getName(),cause);
        } catch(IllegalAccessException error) {
            throw new IllegalStateException("SELF_APPLICATION_FRAMEWORK_ACCESS_FAILED:"+method.getName(),error);
        }
    }
}
