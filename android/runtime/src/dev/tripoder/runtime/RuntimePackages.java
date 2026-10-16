package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;

/** One package world per immutable worker. It is not a same-UID hostile-code sandbox. */
final class RuntimePackages implements InvocationHandler {
    private final RuntimeEngine engine;
    // Facades own their ContextImpl. The registry must not keep either alive.
    // ApplicationPackageManager retains Object identity equality/hash semantics.
    private final Map<PackageManager,Resources> resources=new WeakHashMap<>();
    private final Map<String,Set<ComponentName>> infrastructureComponents=new HashMap<>();
    private String infrastructurePackage;
    private int infrastructureUser;
    private Object root;
    private Endpoint binder;
    private GuestPackageManager metadata;
    private Resources infrastructureResources;
    private Context frameworkContext;
    private boolean activationStarted;
    private volatile boolean active;
    RuntimePackages(RuntimeEngine engine){this.engine=engine;}
    boolean active(){return active;}
    void captureInfrastructure(){
        if(infrastructurePackage!=null)throw new IllegalStateException("INFRASTRUCTURE_ALREADY_CAPTURED");
        infrastructurePackage=engine.host.getPackageName();
        infrastructureComponents.putAll(RuntimePhysicalManifest.capture(engine.host));
    }
    void initialize()throws Exception{
        if(active||root!=null)throw new IllegalStateException("PACKAGE_WORLD_ALREADY_INITIALIZED");
        if(infrastructurePackage==null)throw new IllegalStateException("INFRASTRUCTURE_MANIFEST_NOT_CAPTURED");
        infrastructureUser=RuntimePackageBridge.userId();
        infrastructureResources=engine.host.getResources();
        metadata=new GuestPackageManager(engine.host.getPackageManager(),engine,engine.resources);
        Class<?> contract=Class.forName("android.content.pm.IPackageManager");
        root=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},this);
        binder=new Endpoint((IInterface)root);
        // Adapter installs globals, invalidates old caches, and rewires existing host PM.
        // Any failure aborts worker construction; it never restores the physical world.
        RuntimePackageBridge.activate(root,engine.host);
        active=true;
    }
    synchronized PackageManager manager(Context context,Resources view){
        if(!active)throw new IllegalStateException("PACKAGE_WORLD_NOT_ACTIVE");
        PackageManager facade=Objects.requireNonNull(RuntimePackageBridge.construct(context,RuntimePackageBridge.currentEndpoint()),"PACKAGE_FACADE_CONSTRUCTION_RETURNED_NULL");
        resources.put(facade,view);return facade;
    }

    void requireInfrastructure(String kind,String pkg,ComponentName component,int user){
        if(infrastructurePackage==null||!infrastructurePackage.equals(pkg)||user!=infrastructureUser)throw new SecurityException("PHYSICAL_PACKAGE_CAPABILITY_DENIED");
        if(component!=null){Set<ComponentName> allowed=infrastructureComponents.get(kind);if(allowed==null||!allowed.contains(component))throw new SecurityException("PHYSICAL_COMPONENT_CAPABILITY_DENIED");}
        else if(!kind.equals("package")&&!kind.equals("application"))throw new SecurityException("PHYSICAL_COMPONENT_REQUIRED");
    }
    synchronized boolean registered(PackageManager facade){return resources.containsKey(facade);}
    synchronized void beginActivation(Object candidate,Context host,Context system){
        if(active||activationStarted||root==null||root!=candidate||host!=engine.host)throw new SecurityException("PACKAGE_WORLD_ACTIVATION_REJECTED");
        if(system==null||!system.getClass().getName().equals("android.app.ContextImpl")||!"android".equals(system.getPackageName()))throw new SecurityException("PACKAGE_FRAMEWORK_CONTEXT_INVALID");
        frameworkContext=system;activationStarted=true;
    }
    IBinder binder(){if(binder==null)throw new IllegalStateException("PACKAGE_ENDPOINT_NOT_READY");return binder;}
    synchronized void registerInfrastructureFacade(PackageManager value){if(root==null||active)throw new IllegalStateException("PACKAGE_WORLD_ACTIVATION_ORDER");resources.put(value,engine.resources);}
    PackageManager contextManager(Context context){
        // Public bridge calls cannot register an arbitrary caller-supplied resource view.
        if(context==null||!context.getClass().getName().equals("android.app.ContextImpl"))throw new SecurityException("PACKAGE_PLATFORM_CONTEXT_REQUIRED");
        // ActivityThread's exact system Context performs framework package queries
        // before an Activity exists. It still receives the same logical package world.
        if(context==frameworkContext)return manager(context,engine.resources);
        String pkg=context.getPackageName();if(!pkg.equals(engine.packageName)&&!pkg.equals(infrastructurePackage))throw new SecurityException("PACKAGE_CONTEXT_IDENTITY_DENIED");
        Resources view=pkg.equals(engine.packageName)?context.getResources():engine.resources;
        if(view.getAssets()!=engine.resources.getAssets())throw new SecurityException("PACKAGE_CONTEXT_RESOURCE_ORIGIN_DENIED");
        return manager(context,view);
    }
    private boolean infrastructure(String pkg){
        if(engine.host.getPackageName().equals(pkg))return true;
        if(!engine.packageName.equals(pkg))throw new UnsupportedOperationException("OTHER_PACKAGE_QUERY_UNSUPPORTED:"+pkg);
        return false;
    }
    private void user(Object value){if(!(value instanceof Integer)||((Integer)value)!=RuntimePackageBridge.userId())throw new SecurityException("PACKAGE_USER_UNSUPPORTED");}
    @Override public Object invoke(Object proxy,Method method,Object[] input)throws Throwable{
        Object[] a=input==null?new Object[0]:input;
        if(method.getDeclaringClass()==Object.class){switch(method.getName()){case "toString":return "TripoderPackageEndpoint";case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==a[0];default:throw new AssertionError(method);}}
        String key=method.getName()+Arrays.toString(method.getParameterTypes());
        switch(key){
            case "asBinder[]":return binder();
            case "getPackageInfo[class java.lang.String, long, int]":{
                user(a[2]);String pkg=(String)a[0];long flags=(Long)a[1];
                if(infrastructure(pkg))return RuntimePackageBridge.physicalPackageInfo(pkg,flags,(Integer)a[2]);
                return metadata.getPackageInfo(pkg,PackageManager.PackageInfoFlags.of(flags));
            }
            case "getPackageInfoVersioned[class android.content.pm.VersionedPackage, long, int]":{
                user(a[2]);VersionedPackage selected=(VersionedPackage)a[0];if(selected==null)throw new IllegalArgumentException("VERSIONED_PACKAGE_REQUIRED");
                if(infrastructure(selected.getPackageName()))return RuntimePackageBridge.physicalPackageInfo(selected.getPackageName(),(Long)a[1],(Integer)a[2]);
                return metadata.getPackageInfo(selected,PackageManager.PackageInfoFlags.of((Long)a[1]));
            }
            case "getApplicationInfo[class java.lang.String, long, int]":{
                user(a[2]);String pkg=(String)a[0];if(infrastructure(pkg))return RuntimePackageBridge.physicalApplicationInfo(pkg,(Long)a[1],(Integer)a[2]);
                return metadata.getApplicationInfo(pkg,PackageManager.ApplicationInfoFlags.of((Long)a[1]));
            }
            case "getServiceInfo[class android.content.ComponentName, long, int]":
            case "getProviderInfo[class android.content.ComponentName, long, int]":
            case "getActivityInfo[class android.content.ComponentName, long, int]":{
                user(a[2]);ComponentName component=(ComponentName)a[0];if(component==null)throw new IllegalArgumentException("COMPONENT_REQUIRED");
                if(infrastructure(component.getPackageName()))return RuntimePackageBridge.physicalComponent(method.getName(),component,(Long)a[1],(Integer)a[2]);
                try{
                    if(method.getName().equals("getServiceInfo"))return metadata.getServiceInfo(component,PackageManager.ComponentInfoFlags.of((Long)a[1]));
                    if(method.getName().equals("getProviderInfo"))return metadata.getProviderInfo(component,PackageManager.ComponentInfoFlags.of((Long)a[1]));
                    return metadata.getActivityInfo(component,PackageManager.ComponentInfoFlags.of((Long)a[1]));
                }catch(PackageManager.NameNotFoundException missing){return null;}
            }
            case "resolveContentProvider[class java.lang.String, long, int]":user(a[2]);return metadata.resolveContentProvider((String)a[0],PackageManager.ComponentInfoFlags.of((Long)a[1]));
            case "hasSystemFeature[class java.lang.String, int]":return RuntimePackageBridge.physicalFeature((String)a[0],(Integer)a[1]);
            case "getSdkSandboxPackageName[]":return RuntimePackageBridge.physicalSdkSandboxPackageName();
            case "isInstantApp[class java.lang.String, int]":user(a[1]);infrastructure((String)a[0]);return false;
            case "getPackagesForUid[int]":
                if(((Integer)a[0])!=android.os.Process.myUid())throw new UnsupportedOperationException("PACKAGE_UID_UNSUPPORTED");
                return new String[]{engine.packageName,engine.host.getPackageName()};
            default:throw new UnsupportedOperationException("PACKAGE_ENDPOINT_UNSUPPORTED:"+key);
        }
    }
    synchronized Resources resources(PackageManager manager,String pkg,ApplicationInfo supplied){
        if(supplied!=null&&!Objects.equals(pkg,supplied.packageName))throw new SecurityException("XML_APPLICATION_IDENTITY_MISMATCH");
        if(infrastructure(pkg))return infrastructureResources;
        Resources view=resources.get(manager);
        if(view==null)throw new SecurityException("PACKAGE_RESOURCE_FACADE_UNREGISTERED");return view;
    }
    XmlResourceParser xml(PackageManager manager,String pkg,int id,ApplicationInfo info){try{return resources(manager,pkg,info).getXml(id);}catch(Resources.NotFoundException absent){return null;}}
    CharSequence text(PackageManager manager,String pkg,int id,ApplicationInfo info){try{return resources(manager,pkg,info).getText(id);}catch(Resources.NotFoundException absent){return null;}}
    android.graphics.drawable.Drawable drawable(PackageManager manager,String pkg,int id,ApplicationInfo info){try{return resources(manager,pkg,info).getDrawable(id,null);}catch(Resources.NotFoundException absent){return null;}}
    private static final class Endpoint extends Binder {
        Endpoint(IInterface root){attachInterface(root,"android.content.pm.IPackageManager");}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString("android.content.pm.IPackageManager");return true;}
            throw new UnsupportedOperationException("PACKAGE_RAW_TRANSACTION_UNSUPPORTED:"+code);
        }
    }
}
