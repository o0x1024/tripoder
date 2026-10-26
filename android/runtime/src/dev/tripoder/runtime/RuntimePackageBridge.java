package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import android.content.res.*;

/** Pinned Android adapter. Physical capabilities are explicit and never query another app. */
public final class RuntimePackageBridge {
    private static RuntimePackages world(){if(RuntimeEngine.current==null)throw new IllegalStateException("PACKAGE_ENGINE_MISSING");return RuntimeEngine.current.packages;}
    public static boolean active(){return RuntimeEngine.current!=null&&world().active();}
    public static void activate(Object root,Context host){RuntimeSelfHookPackages.activate(root,host);}
    public static void beginActivation(Object root,Context host,Context system){world().beginActivation(root,host,system);}
    public static PackageManager construct(Context context,Object endpoint){return dev.tripoder.selfhook.PackageFrameworkAccess.construct(context,endpoint);}
    public static Object currentEndpoint(){return dev.tripoder.selfhook.PackageFrameworkAccess.currentEndpoint();}
    public static int userId(){return dev.tripoder.selfhook.PackageFrameworkAccess.userId();}
    public static PackageInfo physicalPackageInfo(String pkg,long flags,int user){requireInfrastructure("package",pkg,null,user);infrastructureFlags("package",flags);return (PackageInfo)dev.tripoder.selfhook.PackageFrameworkAccess.physical("package",pkg,flags,user);}
    public static ApplicationInfo physicalApplicationInfo(String pkg,long flags,int user){requireInfrastructure("application",pkg,null,user);infrastructureFlags("application",flags);return (ApplicationInfo)dev.tripoder.selfhook.PackageFrameworkAccess.physical("application",pkg,flags,user);}
    public static Object physicalComponent(String kind,ComponentName name,long flags,int user){if(name==null)throw new IllegalArgumentException("PHYSICAL_COMPONENT_REQUIRED");requireInfrastructure(kind,name.getPackageName(),name,user);infrastructureFlags(kind,flags);return dev.tripoder.selfhook.PackageFrameworkAccess.physical(kind,name,flags,user);}
    public static boolean physicalFeature(String name,int version){return dev.tripoder.selfhook.PackageFrameworkAccess.feature(name,version);}
    public static Object physicalFeatures(){return dev.tripoder.selfhook.PackageFrameworkAccess.features();}
    public static String[] physicalSystemSharedLibraryNames(){return dev.tripoder.selfhook.PackageFrameworkAccess.systemLibraries();}
    public static String physicalSdkSandboxPackageName(){return dev.tripoder.selfhook.PackageFrameworkAccess.sandbox();}
    public static void notifyWebViewUse(String name,int reason){RuntimeEngine.current.webView.requireCodeUse(name,reason);dev.tripoder.selfhook.PackageFrameworkAccess.notifyPackageUse(name,reason);}
    public static void notifyDexLoad(String name,java.util.Map<?,?> contexts,String isa){RuntimeEngine.current.webView.notifyDexLoad(name,contexts,isa);}
    public static void notifyPackagesReplacedReceived(String[] packages){
        // These names describe physical LoadedApk objects handled by ActivityThread.
        // Keep the real Binder UID and unchanged names; never resolve them in a space.
        dev.tripoder.selfhook.PackageFrameworkAccess.notifyPackagesReplacedReceived(packages);
        android.util.Log.i("TripoderPackage","PACKAGES_REPLACED_RECEIVED:pid="+android.os.Process.myPid()+":count="+(packages==null?-1:packages.length));
    }
    public static void requireInfrastructure(String kind,String pkg,ComponentName component,int user){world().requireInfrastructure(kind,pkg,component,user);}
    public static boolean registered(PackageManager pm){return world().registered(pm);}
    public static void infrastructureFlags(String kind,long flags){
        long allowed=PackageManager.GET_META_DATA|PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE|PackageManager.MATCH_DIRECT_BOOT_AUTO;
        switch(kind){
            case "package":allowed|=PackageManager.GET_SIGNATURES|PackageManager.GET_SIGNING_CERTIFICATES;break;
            case "application":allowed|=PackageManager.GET_SHARED_LIBRARY_FILES|PackageManager.MATCH_UNINSTALLED_PACKAGES;break;
            case "getProviderInfo":allowed|=PackageManager.GET_URI_PERMISSION_PATTERNS;break;
            case "getActivityInfo":case "getServiceInfo":break;
            default:throw new IllegalArgumentException("INFRASTRUCTURE_QUERY_KIND_INVALID");
        }
        // Infrastructure is a real installed package: further detail flags are the platform's to answer.
        if((flags&~allowed)!=0)RuntimeFailThrough.note("infrastructure-flags",kind+":0x"+Long.toHexString(flags&~allowed));
    }
    public static android.os.IBinder localBinder(){return world().binder();}
    public static PackageManager contextManager(Context context){return world().contextManager(context);}
    public static void registerInfrastructureFacade(PackageManager pm){world().registerInfrastructureFacade(pm);}
    public static String packageName(){return RuntimeEngine.current.packageName;}
    public static String installer(String pkg){return world().installer(pkg);}
    public static boolean suspended(String pkg)throws PackageManager.NameNotFoundException{return world().suspended(pkg);}
    /** Android's own recipe: the first enabled activity answering MAIN with the INFO category, else with LAUNCHER; absent for any package that is not this guest. */
    public static android.content.Intent launchIntent(String pkg,boolean leanback){
        RuntimeEngine engine=RuntimeEngine.current;if(engine==null||!engine.packageName.equals(pkg))return null;
        return GuestIntentResolver.launchIntent(engine,leanback);
    }
    public static android.content.pm.ActivityInfo receiverInfo(String className,long flags){
        RuntimeEngine engine=RuntimeEngine.current;android.content.pm.PackageInfo archive=engine==null?null:engine.packageArchive;
        if(archive==null||archive.receivers==null)return null;
        for(android.content.pm.ActivityInfo declared:archive.receivers)if(declared.name.equals(className)){
            android.os.Bundle states=RuntimeComponentState.snapshot(engine);
            if(!RuntimeComponentState.enabled(states,declared)&&(flags&android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)==0)return null;
            android.content.pm.ActivityInfo copy=new android.content.pm.ActivityInfo(declared);copy.enabled=RuntimeComponentState.enabled(states,declared.name,declared.enabled);
            copy.applicationInfo=GuestPackageManager.copyApplicationInfo(engine.info,(flags&android.content.pm.PackageManager.GET_META_DATA)!=0);
            if((flags&android.content.pm.PackageManager.GET_META_DATA)==0)copy.metaData=null;return copy;
        }
        return null;
    }
    public static void setComponentEnabled(ComponentName component,int newState,int flags){world().setComponentEnabled(component,newState,flags);}
    public static Resources resources(PackageManager pm,String pkg,ApplicationInfo info){return world().resources(pm,pkg,info);}
    public static XmlResourceParser xml(PackageManager pm,String pkg,int id,ApplicationInfo info){return world().xml(pm,pkg,id,info);}
    public static CharSequence text(PackageManager pm,String pkg,int id,ApplicationInfo info){return world().text(pm,pkg,id,info);}
    public static android.graphics.drawable.Drawable drawable(PackageManager pm,String pkg,int id,ApplicationInfo info){return world().drawable(pm,pkg,id,info);}
    private RuntimePackageBridge(){}
}
