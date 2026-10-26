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
    private static volatile boolean factoryEvaluationAttempted,factoryEvaluationCompleted,factoryPreparedOwnerVerified;
    private static native void prepareOwnershipNative(Application host,java.lang.reflect.Field[] fields,Object[] methods);
    private static native void beginViewNative();
    private static native Context createNative(Application host,Application guest,ApplicationInfo info,
        ClassLoader loader,ClassLoader owner,AppComponentFactory factory,Resources resources);
    private static native void preparePackageNative(Application host,ApplicationInfo info,ClassLoader loader,ClassLoader owner,Resources resources);
    private static native void publishFactoryNative(AppComponentFactory factory);
    private static native void publishLoaderNative(ClassLoader loader);
    public static void preparePackage(Application host,ApplicationInfo info,ClassLoader loader,ClassLoader owner,Resources resources,String process,java.util.List<android.content.pm.ProviderInfo> providers){
        preparePackage(host,info,loader,owner,resources,process,providers,false);
    }
    public static void prepareIsolatedPackage(Application host,ApplicationInfo info,ClassLoader loader,Resources resources,String process){
        preparePackage(host,info,loader,loader,resources,process,java.util.List.of(),true);
    }
    private static boolean fixedUid(Application host,ApplicationInfo info,boolean isolated){
        return host!=null&&info!=null&&(isolated?Process.isIsolated()&&host.getApplicationInfo().uid!=Process.myUid()&&info.uid==host.getApplicationInfo().uid:info.uid==Process.myUid()&&!Process.isIsolated());
    }
    private static void preparePackage(Application host,ApplicationInfo info,ClassLoader loader,ClassLoader owner,Resources resources,String process,java.util.List<android.content.pm.ProviderInfo> providers,boolean isolated){
        mainThread();if(!fixedUid(host,info,isolated)||loader==null||owner==null||resources==null||process==null||info.packageName==null||info.dataDir==null)throw new IllegalArgumentException("APPLICATION_FIXED_IDENTITY_REQUIRED");
        preparePackageNative(host,info,loader,owner,resources);
        ApplicationBinding.publish(host,info,packageInfoNative(),process,providers);
    }
    public static void publishFactory(AppComponentFactory factory){mainThread();ApplicationBinding.check();publishFactoryNative(java.util.Objects.requireNonNull(factory));ApplicationBinding.check();}
    /** Evaluate the declared selector through the SDK after logical binding.
     * The SDK owns both its successful result and its exception contract. */
    public static AppComponentFactory createFactory(ApplicationInfo info,ClassLoader initial)throws Exception{
        mainThread();ApplicationBinding.check();Object logical=packageInfoNative();
        if(logical==null||info==null||initial==null
                ||FixedFrameworkMembers.get(FixedFrameworkMembers.field(8,2,"mApplicationInfo","Landroid/content/pm/ApplicationInfo;",false),logical)!=info
                ||FixedFrameworkMembers.get(FixedFrameworkMembers.field(4,2,"mClassLoader","Ljava/lang/ClassLoader;",false),logical)!=initial
                ||FixedFrameworkMembers.get(FixedFrameworkMembers.field(5,2,"mDefaultClassLoader","Ljava/lang/ClassLoader;",false),logical)!=initial)
            throw new SecurityException("APPLICATION_FACTORY_PREPARED_INPUTS_REQUIRED");
        if(factoryEvaluationAttempted)throw new IllegalStateException("APPLICATION_FACTORY_ALREADY_EVALUATED");
        factoryEvaluationAttempted=true;
        java.lang.reflect.Field factoryField=FixedFrameworkMembers.field(7,2,"mAppComponentFactory","Landroid/app/AppComponentFactory;",false);
        Object prepared=FixedFrameworkMembers.get(factoryField,logical);
        if(!(prepared instanceof AppComponentFactory)||prepared.getClass().getClassLoader()!=AppComponentFactory.class.getClassLoader())throw new SecurityException("APPLICATION_PREPARED_SDK_FACTORY_REQUIRED");
        AppComponentFactory result=(AppComponentFactory)FixedFrameworkMembers.call(FixedFrameworkMembers.method(2,"createAppFactory","(Landroid/content/pm/ApplicationInfo;Ljava/lang/ClassLoader;)Landroid/app/AppComponentFactory;",false),logical,info,initial);
        ApplicationBinding.check();
        if(FixedFrameworkMembers.get(factoryField,logical)!=prepared)throw new SecurityException("APPLICATION_FACTORY_OWNER_CHANGED_DURING_EVALUATION");
        java.util.Objects.requireNonNull(result,"APPLICATION_SDK_FACTORY_REQUIRED");factoryPreparedOwnerVerified=true;factoryEvaluationCompleted=true;return result;
    }
    /** Fixed boundary observations; never returns framework roots or guest data. */
    public static java.util.Map<String,Boolean> factoryEvaluationEvidence(){return java.util.Map.of("attempted",factoryEvaluationAttempted,"completed",factoryEvaluationCompleted,"preparedOwnerVerified",factoryPreparedOwnerVerified);}
    public static void publishLoader(ClassLoader loader){mainThread();ApplicationBinding.check();publishLoaderNative(java.util.Objects.requireNonNull(loader));ApplicationBinding.check();}
    public static native Application current();
    private static native void attachNative(Context context);
    private static native void checkNative();
    /** The loader the guest itself publishes on its LoadedApk; equals the runtime loader until the guest replaces it. */
    private static native ClassLoader loaderNative();
    public static ClassLoader loader(){return loaderNative();}
    /** The guest's LoadedApk: the object a real application context exposes as {@code mPackageInfo}. */
    private static native Object packageInfoNative();
    public static Object packageInfo(){return packageInfoNative();}
    public static java.util.Map<String,Object> resourceRelations(Context context,Resources view,Resources initial)throws Exception{return ApplicationBinding.resourceRelations(context,view,initial);}
    public static void requireLogicalContext(Context context){ApplicationBinding.requireLogicalContext(context);}
    public static java.util.List<android.content.pm.ProviderInfo> providerInstallationBatch(){mainThread();return ApplicationBinding.providerInstallationBatch();}
    /** The Application the guest publishes on that LoadedApk; hot-fix proxies replace it during onCreate. */
    private static native Application installedApplicationNative();
    public static Application installedApplication(){return installedApplicationNative();}
    private static native void publishApplicationNative(Application application);
    /** Commit the Application explicitly exposed by the guest's application-context contract after onCreate. */
    public static void publishApplication(Application application){mainThread();if(application==null)throw new IllegalArgumentException("APPLICATION_PUBLICATION_REQUIRED");publishApplicationNative(application);}
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
    /**
     * @param loader the loader the platform publishes as the application loader; the guest may replace it during
     *               attachBaseContext with a hot-fix/plugin loader
     * @param owner  the verified defining loader of this installation's DEX sequence; every published loader must
     *               resolve inside it, which is what keeps a replacement from escaping the installation
     */
    public static Context create(Application host,Application guest,ApplicationInfo info,ClassLoader loader,ClassLoader owner,
        AppComponentFactory factory,Resources resources){
        return create(host,guest,info,loader,owner,factory,resources,false);
    }
    public static Context createIsolated(Application host,Application guest,ApplicationInfo info,ClassLoader loader,AppComponentFactory factory,Resources resources){
        return create(host,guest,info,loader,loader,factory,resources,true);
    }
    private static Context create(Application host,Application guest,ApplicationInfo info,ClassLoader loader,ClassLoader owner,AppComponentFactory factory,Resources resources,boolean isolated){
        mainThread();
        if(host==null||guest==null||host==guest||info==null||loader==null||owner==null||factory==null||resources==null||
            !fixedUid(host,info,isolated)||info.packageName==null||info.dataDir==null)
            throw new IllegalArgumentException("APPLICATION_FIXED_IDENTITY_REQUIRED");
        try{
            if(Class.forName(guest.getClass().getName(),false,loader)!=guest.getClass())
                throw new IllegalArgumentException("APPLICATION_SELECTED_LOADER_IDENTITY");
        }catch(ClassNotFoundException error){throw new IllegalArgumentException("APPLICATION_SELECTED_LOADER_IDENTITY",error);}
        ApplicationBinding.check();return createNative(host,guest,info,loader,owner,factory,resources);
    }
    public static void attach(Context context){mainThread();ApplicationBinding.check();attachNative(context);ApplicationBinding.check();}
    public static void check(){mainThread();ApplicationBinding.check();checkNative();}
    /** Probe teardown only. Roots/code remain resident; this is not object retirement. */
    public static void closeProbeView(){mainThread();ApplicationBinding.restoreProbe();closeViewNative();}
}
