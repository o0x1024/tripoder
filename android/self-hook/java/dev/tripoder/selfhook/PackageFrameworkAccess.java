package dev.tripoder.selfhook;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
/** Closed package-field and call contracts. Query authorization and package
 * world state remain in RuntimePackages; this class adds no package resolver. */
public final class PackageFrameworkAccess {
    private static boolean attempted;private static PackageFrameworkAccess instance;
    private final Class<?> contextType,managerType,contract;
    private final Constructor<?> constructor;
    private final Field endpoint,global,services,contextCache,managerContext;
    private final Method systemContext,packageGetter,userId,feature,features,systemLibraries,sandbox,notifyPackageUse,notifyDexLoad,notifyPackagesReplaced,componentEnabled,sharedPreferences;
    private final Method[] disable=new Method[3];
    private final Map<String,Method> physical=new HashMap<>(),logical=new HashMap<>();
    private final Object physicalEndpoint;
    private PackageFrameworkAccess()throws Exception {
        contextType=FixedFrameworkMembers.owner(3);managerType=FixedFrameworkMembers.owner(41);contract=FixedFrameworkMembers.owner(16);
        constructor=FixedFrameworkMembers.constructor(41,"(Landroid/app/ContextImpl;Landroid/content/pm/IPackageManager;)V");
        endpoint=FixedFrameworkMembers.field(35,41,"mPM","Landroid/content/pm/IPackageManager;",false);global=FixedFrameworkMembers.field(36,1,"sPackageManager","Landroid/content/pm/IPackageManager;",true);
        services=FixedFrameworkMembers.field(37,44,"sCache","Ljava/util/Map;",true);contextCache=FixedFrameworkMembers.field(38,3,"mPackageManager","Landroid/content/pm/PackageManager;",false);
        managerContext=FixedFrameworkMembers.field(56,41,"mContext","Landroid/app/ContextImpl;",false);
        systemContext=FixedFrameworkMembers.method(1,"getSystemContext","()Landroid/app/ContextImpl;",false);packageGetter=FixedFrameworkMembers.method(1,"getPackageManager","()Landroid/content/pm/IPackageManager;",true);
        sharedPreferences=FixedFrameworkMembers.method(3,"getSharedPreferences","(Ljava/io/File;I)Landroid/content/SharedPreferences;",false);
        userId=FixedFrameworkMembers.method(49,"getIdentifier","()I",false);
        disable[0]=FixedFrameworkMembers.method(42,"disableApplicationInfoCache","()V",true);disable[1]=FixedFrameworkMembers.method(42,"disablePackageInfoCache","()V",true);disable[2]=FixedFrameworkMembers.method(41,"disableGetPackagesForUidCache","()V",true);
        physical.put("package",FixedFrameworkMembers.method(16,"getPackageInfo","(Ljava/lang/String;JI)Landroid/content/pm/PackageInfo;",false));
        physical.put("application",FixedFrameworkMembers.method(16,"getApplicationInfo","(Ljava/lang/String;JI)Landroid/content/pm/ApplicationInfo;",false));
        for(String kind:new String[]{"Activity","Service","Provider"})physical.put("get"+kind+"Info",FixedFrameworkMembers.method(16,"get"+kind+"Info","(Landroid/content/ComponentName;JI)Landroid/content/pm/"+kind+"Info;",false));
        feature=FixedFrameworkMembers.method(16,"hasSystemFeature","(Ljava/lang/String;I)Z",false);sandbox=FixedFrameworkMembers.method(16,"getSdkSandboxPackageName","()Ljava/lang/String;",false);
        features=FixedFrameworkMembers.method(16,"getSystemAvailableFeatures","()Landroid/content/pm/ParceledListSlice;",false);
        systemLibraries=FixedFrameworkMembers.method(16,"getSystemSharedLibraryNames","()[Ljava/lang/String;",false);
        notifyPackageUse=FixedFrameworkMembers.method(16,"notifyPackageUse","(Ljava/lang/String;I)V",false);
        notifyDexLoad=FixedFrameworkMembers.method(16,"notifyDexLoad","(Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;)V",false);
        notifyPackagesReplaced=FixedFrameworkMembers.method(16,"notifyPackagesReplacedReceived","([Ljava/lang/String;)V",false);
        componentEnabled=FixedFrameworkMembers.method(16,"getComponentEnabledSetting","(Landroid/content/ComponentName;I)I",false);
        logical.put("hasSystemFeature",feature);logical.put("getSdkSandboxPackageName",sandbox);logical.put("getComponentEnabledSetting",componentEnabled);
        logical.putAll(physical);logical.put("getPackageInfo",physical.get("package"));logical.put("getApplicationInfo",physical.get("application"));
        logical.put("getPackageInfoVersioned",FixedFrameworkMembers.method(16,"getPackageInfoVersioned","(Landroid/content/pm/VersionedPackage;JI)Landroid/content/pm/PackageInfo;",false));
        logical.put("resolveContentProvider",FixedFrameworkMembers.method(16,"resolveContentProvider","(Ljava/lang/String;JI)Landroid/content/pm/ProviderInfo;",false));
        logical.put("isInstantApp",FixedFrameworkMembers.method(16,"isInstantApp","(Ljava/lang/String;I)Z",false));logical.put("getPackagesForUid",FixedFrameworkMembers.method(16,"getPackagesForUid","(I)[Ljava/lang/String;",false));
        physicalEndpoint=ComponentFrameworkAccess.physicalPackages();if(!contract.isInstance(physicalEndpoint))throw new IllegalStateException("PHYSICAL_PACKAGE_ENDPOINT_TYPE");
    }
    public static synchronized void prepare()throws Exception{if(attempted)throw new IllegalStateException("PACKAGE_FRAMEWORK_ALREADY_ATTEMPTED");attempted=true;instance=new PackageFrameworkAccess();}
    private static PackageFrameworkAccess require(){if(instance==null)throw new IllegalStateException("PACKAGE_FRAMEWORK_NOT_READY");return instance;}
    private Context unwrap(Context context){Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());while(context!=null&&seen.size()<16&&seen.add(context)){
        if(context.getClass()==contextType)return context;if(!(context instanceof ContextWrapper))break;context=((ContextWrapper)context).getBaseContext();
    }throw new SecurityException("PACKAGE_CONTEXT_IMPL_REQUIRED");}
    public static Context systemContext(){PackageFrameworkAccess v=require();return (Context)Objects.requireNonNull(FixedFrameworkMembers.call(v.systemContext,PhysicalApplication.thread()),"PACKAGE_SYSTEM_CONTEXT_REQUIRED");}
    public static SharedPreferences sharedPreferences(Context context,java.io.File file,int mode){PackageFrameworkAccess v=require();return (SharedPreferences)Objects.requireNonNull(FixedFrameworkMembers.call(v.sharedPreferences,v.unwrap(Objects.requireNonNull(context)),Objects.requireNonNull(file),mode),"PLATFORM_SHARED_PREFERENCES_REQUIRED");}
    public static int userId(){return userId(android.os.Process.myUserHandle());}
    public static int userId(UserHandle user){return (Integer)FixedFrameworkMembers.call(require().userId,Objects.requireNonNull(user));}
    public static Object currentEndpoint(){PackageFrameworkAccess v=require();Object root=FixedFrameworkMembers.call(v.packageGetter,null);if(!v.contract.isInstance(root))throw new IllegalStateException("PACKAGE_ENDPOINT_TYPE");return root;}
    public static Object endpoint(PackageManager manager){PackageFrameworkAccess v=require();if(!v.managerType.isInstance(manager))throw new SecurityException("PACKAGE_FACADE_TYPE");Object root=FixedFrameworkMembers.get(v.endpoint,manager);if(!v.contract.isInstance(root))throw new IllegalStateException("PACKAGE_ENDPOINT_MISSING");return root;}
    /** Raw fixed-field observations only. Never calls a possibly replaced IPM or lazy getter. */
    public static Map<String,Object> endpointRelations(Context context,PackageManager manager,Object expected,Object argument,IBinder expectedBinder){
        PackageFrameworkAccess v=require();Map<String,Object> out=new LinkedHashMap<>();
        Object global=FixedFrameworkMembers.get(v.global,null);
        out.put("globalIsRoot",global==expected);out.put("globalIsPhysical",global==v.physicalEndpoint);out.put("globalNull",global==null);
        describeEndpoint(out,"global",global);
        out.put("managerPresent",manager!=null);out.put("constructorArgumentIsRoot",argument==expected);out.put("constructorArgumentIsGlobal",argument==global);
        out.put("contextHasFixedType",context!=null&&context.getClass()==v.contextType);
        if(manager!=null){
            boolean typed=v.managerType.isInstance(manager);out.put("managerHasFixedType",typed);
            if(typed){Object endpoint=FixedFrameworkMembers.get(v.endpoint,manager);
                out.put("managerContextExact",context!=null&&context.getClass()==v.contextType&&FixedFrameworkMembers.get(v.managerContext,manager)==context);
                out.put("endpointIsRoot",endpoint==expected);out.put("endpointIsPhysical",endpoint==v.physicalEndpoint);
                out.put("endpointIsGlobal",endpoint==global);out.put("endpointIsConstructorArgument",endpoint==argument);out.put("endpointNull",endpoint==null);
                describeEndpoint(out,"endpoint",endpoint);
            }
        }
        Object cache=FixedFrameworkMembers.get(v.services,null);boolean known=cache!=null&&(cache.getClass()==android.util.ArrayMap.class||cache.getClass()==HashMap.class);
        out.put("serviceCacheHasKnownMapType",known);
        if(known){Object binder=((Map<?,?>)cache).get("package");out.put("serviceCacheIsRootBinder",binder==expectedBinder);out.put("serviceCachePackageNull",binder==null);}
        return out;
    }
    private static void describeEndpoint(Map<String,Object> out,String prefix,Object endpoint){
        if(endpoint==null)return;Class<?> type=endpoint.getClass();out.put(prefix+"Class",type.getName());
        if(Proxy.isProxyClass(type))out.put(prefix+"HandlerClass",Proxy.getInvocationHandler(endpoint).getClass().getName());
    }
    public static PackageManager construct(Context context,Object root){PackageFrameworkAccess v=require();if(!v.contract.isInstance(root))throw new IllegalArgumentException("PACKAGE_ENDPOINT_TYPE");return (PackageManager)FixedFrameworkMembers.create(v.constructor,v.unwrap(Objects.requireNonNull(context)),root);}
    public static PackageManager cached(Context context){PackageFrameworkAccess v=require();return (PackageManager)FixedFrameworkMembers.get(v.contextCache,v.unwrap(context));}
    public static void requireContext(PackageManager manager,Context context){PackageFrameworkAccess v=require();if(!v.managerType.isInstance(manager)||FixedFrameworkMembers.get(v.managerContext,manager)!=v.unwrap(context))throw new SecurityException("PACKAGE_FACADE_CONTEXT_MISMATCH");}
    /** QA relations expose no Context, raw framework roots, paths or guest resource values. */
    public static Map<String,Object> resourceRelations(PackageManager manager,android.content.res.Resources view,android.content.res.Resources initial,ResourceFrameworkAccess.AssetSnapshot frozen)throws Exception{
        PackageFrameworkAccess v=require();if(!v.managerType.isInstance(manager))throw new SecurityException("PACKAGE_FACADE_TYPE");
        Context context=(Context)FixedFrameworkMembers.get(v.managerContext,manager);
        return ApplicationBinding.resourceRelations(context,view,initial,frozen);
    }
    public static void cache(Context context,PackageManager value){PackageFrameworkAccess v=require();FixedFrameworkMembers.set(v.contextCache,v.unwrap(context),Objects.requireNonNull(value));}
    @SuppressWarnings("unchecked") public static void publish(Object root,PackageManager hostFacade){
        PackageFrameworkAccess v=require();if(!v.contract.isInstance(root)||!v.managerType.isInstance(hostFacade))throw new IllegalArgumentException("PACKAGE_PUBLICATION_TYPES");
        for(Method method:v.disable)FixedFrameworkMembers.call(method,null);
        FixedFrameworkMembers.set(v.endpoint,hostFacade,root);Object raw=FixedFrameworkMembers.get(v.services,null);if(!(raw instanceof Map))throw new IllegalStateException("PACKAGE_SERVICE_CACHE_TYPE");
        Map<String,IBinder> cache=(Map<String,IBinder>)raw;IBinder binder=((IInterface)root).asBinder();if(binder==null)throw new IllegalStateException("PACKAGE_BINDER_REQUIRED");cache.put("package",binder);FixedFrameworkMembers.set(v.global,null,root);
        if(FixedFrameworkMembers.get(v.global,null)!=root||FixedFrameworkMembers.get(v.endpoint,hostFacade)!=root||cache.get("package")!=binder||currentEndpoint()!=root)throw new IllegalStateException("PACKAGE_WORLD_INSTALL_UNCONFIRMED");
    }
    public static Object physical(String kind,Object key,long flags,int user){PackageFrameworkAccess v=require();Method method=v.physical.get(kind);if(method==null)throw new IllegalArgumentException("PHYSICAL_PACKAGE_QUERY_KIND");return FixedFrameworkMembers.call(method,v.physicalEndpoint,key,flags,user);}
    /** An IPackageManager method the logical world has no translation for, answered by the platform's own endpoint. */
    public static Object physicalCall(Method method,Object[] args){PackageFrameworkAccess v=require();if(method.getDeclaringClass()!=v.contract)throw new SecurityException("PHYSICAL_PACKAGE_CONTRACT");return FixedFrameworkMembers.call(method,v.physicalEndpoint,args);}
    public static IBinder physicalBinder(){return ((IInterface)require().physicalEndpoint).asBinder();}
    public static boolean feature(String name,int version){PackageFrameworkAccess v=require();return (Boolean)FixedFrameworkMembers.call(v.feature,v.physicalEndpoint,name,version);}
    /** The physical device feature table as the platform's own ParceledListSlice. */
    public static Object features(){PackageFrameworkAccess v=require();return FixedFrameworkMembers.call(v.features,v.physicalEndpoint);}
    /** Device capability names only; no library paths, loaders or package visibility. */
    public static String[] systemLibraries(){PackageFrameworkAccess v=require();String[] names=(String[])FixedFrameworkMembers.call(v.systemLibraries,v.physicalEndpoint);return names==null?null:names.clone();}
    public static String sandbox(){PackageFrameworkAccess v=require();return (String)FixedFrameworkMembers.call(v.sandbox,v.physicalEndpoint);}
    public static void notifyPackageUse(String name,int reason){PackageFrameworkAccess v=require();FixedFrameworkMembers.call(v.notifyPackageUse,v.physicalEndpoint,name,reason);}
    public static void notifyDexLoad(String name,Map<String,String> contexts,String isa){PackageFrameworkAccess v=require();FixedFrameworkMembers.call(v.notifyDexLoad,v.physicalEndpoint,name,contexts,isa);}
    /** ActivityThread's physical package-replacement receipt, including the empty handled set. */
    public static void notifyPackagesReplacedReceived(String[] packages){PackageFrameworkAccess v=require();FixedFrameworkMembers.call(v.notifyPackagesReplaced,v.physicalEndpoint,(Object)packages);}
    public static int componentEnabled(ComponentName component,int user){PackageFrameworkAccess v=require();return (Integer)FixedFrameworkMembers.call(v.componentEnabled,v.physicalEndpoint,component,user);}
    public static Object query(PackageManager manager,String name,Object... args){PackageFrameworkAccess v=require();Method method=v.logical.get(name);if(method==null)throw new IllegalArgumentException("LOGICAL_PACKAGE_QUERY_KIND");return FixedFrameworkMembers.call(method,endpoint(manager),args);}
}
