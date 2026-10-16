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
    public static String physicalSdkSandboxPackageName(){return dev.tripoder.selfhook.PackageFrameworkAccess.sandbox();}
    public static void requireInfrastructure(String kind,String pkg,ComponentName component,int user){world().requireInfrastructure(kind,pkg,component,user);}
    public static boolean registered(PackageManager pm){return world().registered(pm);}
    public static void infrastructureFlags(String kind,long flags){
        long allowed=PackageManager.GET_META_DATA|PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        switch(kind){
            case "package":allowed|=PackageManager.GET_SIGNATURES|PackageManager.GET_SIGNING_CERTIFICATES;break;
            case "application":allowed|=PackageManager.GET_SHARED_LIBRARY_FILES|PackageManager.MATCH_UNINSTALLED_PACKAGES;break;
            case "getProviderInfo":allowed|=PackageManager.GET_URI_PERMISSION_PATTERNS;break;
            case "getActivityInfo":case "getServiceInfo":break;
            default:throw new IllegalArgumentException("INFRASTRUCTURE_QUERY_KIND_INVALID");
        }
        if((flags&~allowed)!=0)throw new UnsupportedOperationException("INFRASTRUCTURE_PACKAGE_FLAGS_UNSUPPORTED:"+kind+":0x"+Long.toHexString(flags));
    }
    public static android.os.IBinder localBinder(){return world().binder();}
    public static PackageManager contextManager(Context context){return world().contextManager(context);}
    public static void registerInfrastructureFacade(PackageManager pm){world().registerInfrastructureFacade(pm);}
    public static String packageName(){return RuntimeEngine.current.packageName;}
    public static Resources resources(PackageManager pm,String pkg,ApplicationInfo info){return world().resources(pm,pkg,info);}
    public static XmlResourceParser xml(PackageManager pm,String pkg,int id,ApplicationInfo info){return world().xml(pm,pkg,id,info);}
    public static CharSequence text(PackageManager pm,String pkg,int id,ApplicationInfo info){return world().text(pm,pkg,id,info);}
    public static android.graphics.drawable.Drawable drawable(PackageManager pm,String pkg,int id,ApplicationInfo info){return world().drawable(pm,pkg,id,info);}
    private RuntimePackageBridge(){}
}
