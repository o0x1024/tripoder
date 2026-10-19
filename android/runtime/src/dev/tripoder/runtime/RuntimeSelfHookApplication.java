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
        // currentPackageName() reads the host's bind record; guest code asking who it is must get the guest package.
        callbacks.put((Method)value.members.methods[FrameworkAccess.CURRENT_PACKAGE_NAME],(receiver,args,original)->value.engine.packageName);
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
        ApplicationInfo expected=GuestPackageManager.copyApplicationInfo(value.engine.info,true);
        if(physical!=value.host||guest==null||guest==value.host||guest.getBaseContext()!=null||guest!=value.engine.application||loader!=value.engine.loader||
            factory!=value.engine.componentFactory||resources!=value.engine.resources||info==null||
            !value.engine.packageName.equals(info.packageName)||!expected.sourceDir.equals(info.sourceDir)||
            !expected.dataDir.equals(info.dataDir)||info.uid!=value.engine.info.uid)
            throw new SecurityException("SELF_APPLICATION_ENGINE_IDENTITY_MISMATCH");
        // The verified manifest may select Android's exact default Application.
        // Its instance belongs to this guest, while its defining Class is boot.
        RuntimeSelfHookLoaders.requireApplication(loader,guest.getClass(),value.engine.platformDefaultApplication?Application.class:null,value.engine::ownsLoader);
        value.guest=guest;
        Context created=ApplicationAdapter.create(value.host,guest,info,loader,value.engine.artifactLoader(),factory,resources);
        // Two identities exist by design: what the guest sees, and what platform services validate against the real
        // UID. Both are recorded before attach so a package/UID rejection inside Application.attach is attributable.
        try{android.util.Log.i("TripoderIdentity","create package="+created.getPackageName()
            +" opPackage="+created.getOpPackageName()+" host="+value.host.getPackageName()
            +" uid="+android.os.Process.myUid());}catch(Throwable ignored){}
        return created;
    }

    static void attach(Application guest,Context context) {
        mainThread();RuntimeSelfHookApplication value=require();
        if(guest==null||guest!=value.guest||context==null||value.attachAttempted)
            throw new SecurityException("SELF_APPLICATION_ATTACH_IDENTITY_MISMATCH");
        value.attachAttempted=true;
        // The base context carries the platform application-context plumbing (mOuterContext/mPackageInfo) before the
        // guest's attachBaseContext runs, exactly as ActivityThread sets it up before Application.attach.
        if(context instanceof GuestContext)((GuestContext)context).attachedApplication(guest);
        ApplicationAdapter.attach(context);
        value.attached=true;
    }

    /** The application the guest published on its LoadedApk after onCreate, or the created instance before that. */
    static Application installedApplication(){require();return ApplicationAdapter.installedApplication();}

    static void check(Context host,Application guest) {
        mainThread();RuntimeSelfHookApplication value=require();
        if(!value.attached||host!=value.host||guest!=value.guest)
            throw new SecurityException("SELF_APPLICATION_CHECK_IDENTITY_MISMATCH");
        ApplicationAdapter.check();
    }

    /**
     * The guest's own LoadedApk is authoritative for its class loader: hot-fix and plugin runtimes replace it during
     * attachBaseContext to install patch/plugin DEX. Components published after attach must resolve through the loader
     * the guest just installed, otherwise classes it made reachable are reported missing.
     *
     * <p>Ownership is rooted at the verified defining loader, not at the facade object the runtime first published:
     * RePlugin installs a sibling loader ({@code parent = original.getParent()}) that delegates to the original through
     * a field, and that loader is legitimate precisely because every class it resolves still comes from this
     * installation's DEX sequence.
     */
    static ClassLoader adoptLoader(ClassLoader current) {
        RuntimeSelfHookApplication value=require();
        ClassLoader declared=ApplicationAdapter.loader();
        if(declared==null||declared==current){android.util.Log.i("TripoderLoader","attach kept="+loaderChain(current));return current;}
        android.util.Log.i("TripoderLoader","attach declared="+loaderChain(declared));
        if(!value.engine.ownsLoader(declared))throw new SecurityException("SELF_APPLICATION_LOADER_IDENTITY_MISMATCH");
        return declared;
    }
    /**
     * The loader the application publishes on its own LoadedApk right now, or null before create.
     *
     * <p>This is the loader Android itself would hand to {@code Instrumentation} when the platform asks the
     * application to instantiate a component, so it is the authority for later component construction. It is read per
     * question rather than cached at attach: hot-fix, plugin and App Bundle runtimes install their DEX during
     * Application.onCreate, after attach.
     */
    static ClassLoader publishedLoader(){
        try{return ApplicationAdapter.loader();}catch(Throwable unavailable){return null;}
    }
    /** Bounded class-loader chain for startup evidence; a loader alone never proves which DEX it can see. */
    static String loaderChain(ClassLoader start){
        StringBuilder out=new StringBuilder();ClassLoader current=start;
        for(int i=0;current!=null&&i<6;i++){if(i>0)out.append(" -> ");out.append(current.getClass().getName());current=current.getParent();}
        return out.toString();
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
        if(stage.equals("after-attach")){
            // Two identities exist by design: what the guest sees, and what platform services validate against the real
            // UID. Recording both here makes any package/UID rejection traceable to the context that produced it.
            try{android.util.Log.i("TripoderIdentity","attached package="+guest.getPackageName()
                +" opPackage="+guest.getOpPackageName()+" uid="+android.os.Process.myUid());}catch(Throwable ignored){}
        }
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
