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
    private final java.util.concurrent.atomic.AtomicBoolean resourceOriginReported=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean heldResourceOriginReported=new java.util.concurrent.atomic.AtomicBoolean();
    private final RuntimePackageDiagnostics diagnostics;
    RuntimePackages(RuntimeEngine engine){this.engine=engine;diagnostics=new RuntimePackageDiagnostics(engine);}
    boolean active(){return active;}
    boolean suspended(String pkg)throws PackageManager.NameNotFoundException{return metadata.isPackageSuspended(pkg);}
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
        traceEndpoints("PACKAGE_ACTIVATED");
    }
    synchronized PackageManager manager(Context context,Resources view){
        if(!active)throw new IllegalStateException("PACKAGE_WORLD_NOT_ACTIVE");
        // The package world is this worker's captured authority. A guest may
        // wrap the ambient SDK singleton; that does not select our backend.
        Object argument=root;
        PackageManager facade=Objects.requireNonNull(RuntimePackageBridge.construct(context,argument),"PACKAGE_FACADE_CONSTRUCTION_RETURNED_NULL");
        resources.put(facade,view);traceEndpoint("FACADE_CONSTRUCTED",context,facade,argument);return facade;
    }

    void requireInfrastructure(String kind,String pkg,ComponentName component,int user){
        if(infrastructurePackage==null||!infrastructurePackage.equals(pkg)||user!=infrastructureUser)throw new SecurityException("PHYSICAL_PACKAGE_CAPABILITY_DENIED");
        if(component!=null){
            Set<ComponentName> allowed=infrastructureComponents.get(kind);
            if(allowed==null||!allowed.contains(component))throw new PackageAbsent(component.flattenToShortString());
        }
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
        if(context==null||!context.getClass().getName().equals("android.app.ContextImpl"))throw new SecurityException("PACKAGE_PLATFORM_CONTEXT_REQUIRED");
        if(context==frameworkContext)return contextualManager(context,engine.resources);
        if(engine.webView.owns(context.getPackageName()))return contextualManager(context,engine.webView.contextResources(context));
        String pkg=context.getPackageName();if(!pkg.equals(engine.packageName)&&!pkg.equals(infrastructurePackage))throw new SecurityException("PACKAGE_CONTEXT_IDENTITY_DENIED");
        Resources view=pkg.equals(engine.packageName)?context.getResources():engine.resources;
        if(pkg.equals(engine.packageName))try{
            dev.tripoder.selfhook.ApplicationAdapter.requireLogicalContext(context);
            engine.webView.requireGuestResources(view);
        }catch(SecurityException denied){resourceOriginDenied(context,view);throw denied;}
        return contextualManager(context,view);
    }
    private synchronized PackageManager contextualManager(Context context,Resources view){
        PackageManager cached=dev.tripoder.selfhook.PackageFrameworkAccess.cached(context);
        if(cached!=null){
            dev.tripoder.selfhook.PackageFrameworkAccess.requireContext(cached,context);
            if(resources.containsKey(cached)){
                if(dev.tripoder.selfhook.PackageFrameworkAccess.endpoint(cached)!=root){traceEndpoint("CACHED_ENDPOINT_CHANGED",context,cached,null);throw new SecurityException("PACKAGE_CACHED_ENDPOINT_CHANGED");}
                resources.put(cached,view);return cached;
            }
        }
        return manager(context,view);
    }
    synchronized void traceEndpoints(String phase){
        if(!engine.traceStartup||root==null||frameworkContext==null)return;
        try{traceEndpoint(phase,frameworkContext,dev.tripoder.selfhook.PackageFrameworkAccess.cached(frameworkContext),null);}
        catch(RuntimeException error){android.util.Log.w("TripoderPackageRelations","STAGE_OBSERVATION_FAILED:"+error.getClass().getName());}
    }
    private void traceEndpoint(String phase,Context context,PackageManager manager,Object argument){
        diagnostics.record(phase,context,manager,root,argument,binder,manager!=null&&resources.containsKey(manager),context==frameworkContext);
    }
    private void resourceOriginDenied(Context context,Resources view){
        if(!engine.traceStartup||!resourceOriginReported.compareAndSet(false,true))return;
        try{
            var relations=dev.tripoder.selfhook.ApplicationAdapter.resourceRelations(context,view,engine.resources);
            relations.put("frozenAssets",engine.resourceAssets.relations(view));
            PackageManager cached=dev.tripoder.selfhook.PackageFrameworkAccess.cached(context);relations.put("cachedManagerPresent",cached!=null);relations.put("cachedManagerRegistered",cached!=null&&registered(cached));
            android.util.Log.i("TripoderResourceOrigin",new org.json.JSONObject(relations).toString());
        }catch(Exception diagnostic){android.util.Log.w("TripoderResourceOrigin","RELATION_DIAGNOSTIC_FAILED:"+diagnostic.getClass().getName());}
    }
    /** Not this guest, the host, or a WebView provider: invisible to the caller, so every query about it answers "absent" in Binder's own terms (null, false, zero). */
    static final class PackageAbsent extends RuntimeException{PackageAbsent(String pkg){super("PACKAGE_ABSENT:"+pkg,null,false,false);}}
    /**
     * The single decision that defines which package world answers a query. The guest's own package is served by the
     * verified archive (its real ApplicationInfo, components and metadata); the host package is physical
     * infrastructure; every other name is absent. A name list must never be consulted before this rule: the guest's
     * own identity cannot depend on which applications happen to be installed on the device, and shadowing it makes
     * the guest read null for its own manifest metadata.
     */
    private boolean externalMetadata(String pkg){return !Objects.equals(pkg,infrastructurePackage)&&!engine.webView.owns(pkg)&&engine.packageVisibility.admits(pkg);}
    private boolean infrastructure(String pkg){
        if(engine.host.getPackageName().equals(pkg))return true;
        if(!engine.packageName.equals(pkg))throw new PackageAbsent(pkg);
        return false;
    }
    private void user(Object value){if(!(value instanceof Integer)||((Integer)value)!=RuntimePackageBridge.userId())throw new SecurityException("PACKAGE_USER_UNSUPPORTED");}
    /** One implementation for both package paths: see GuestIdentity.installerPackageName for the plane rule. */
    String installer(String pkg){return GuestIdentity.installerPackageName(engine,pkg);}
    /**
     * Self plane: a guest asking which packages own this uid is asking who it is. The kernel uid belongs to the
     * container, so the platform's answer names the container - the one answer that contradicts getPackageName and
     * getNameForUid. Attribution (AppOps, storage, media provider) asks the same question of {@link #dispatch} at the
     * system boundary and is answered there with the uid owner; the two answers differ because the askers differ.
     */
    String[] packagesForUid(int uid){
        if(uid!=android.os.Process.myUid())return null;
        return new String[]{engine.packageName};
    }
    int componentEnabled(ComponentName component,int user){
        if(component==null)throw new IllegalArgumentException("COMPONENT_REQUIRED");
        if(engine.webView.owns(component.getPackageName()))return engine.webView.componentEnabled(component,user);
        if(infrastructurePackage!=null&&infrastructurePackage.equals(component.getPackageName()))return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if(!engine.packageName.equals(component.getPackageName()))throw new PackageAbsent(component.flattenToShortString());
        if(user!=RuntimePackageBridge.userId())throw new SecurityException("PACKAGE_USER_UNSUPPORTED");
        try{android.os.Bundle request=engine.auth();request.putString("kind","get");request.putString("component",component.getClassName());return engine.brokerCall(RuntimeWire.COMPONENT_STATE,request).getInt("state");}
        catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("COMPONENT_SETTINGS_QUERY_FAILED",error);}
    }
    void setComponentEnabled(ComponentName component,int newState,int flags){
        if(component==null||!engine.packageName.equals(component.getPackageName()))throw new SecurityException("COMPONENT_STATE_CHANGE_DENIED:"+component);
        // Only DONT_KILL_APP changes what happens to a guest component; SYNCHRONOUS and similar delivery hints are dropped.
        if((flags&~PackageManager.DONT_KILL_APP)!=0){RuntimeFailThrough.note("component-state-flags","0x"+Integer.toHexString(flags));flags&=PackageManager.DONT_KILL_APP;}
        if(newState<0||newState>2)throw new IllegalArgumentException("COMPONENT_STATE_INVALID:"+newState);
        try{android.os.Bundle request=engine.auth();request.putString("kind","set");request.putString("component",component.getClassName());request.putInt("state",newState);request.putInt("flags",flags);request.putString("operation",java.util.UUID.randomUUID().toString());engine.brokerCall(RuntimeWire.COMPONENT_STATE,request);}
        catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("COMPONENT_SETTINGS_WRITE_FAILED",error);}
    }
    @Override public Object invoke(Object proxy,Method method,Object[] input)throws Throwable{
        try{Object result=dispatch(proxy,method,input);diagnostics.query(method,input,result);return result;}
        catch(PackageAbsent|PackageManager.NameNotFoundException absent){
            Class<?> type=method.getReturnType();
            if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;
            return null;
        }
        catch(Throwable t){
            android.util.Log.w("TripoderPackages", "IPM call threw unexpected exception method=" + method.getName(), t);
            throw t;
        }
    }
        /** The interface method set is fixed, so each dispatch key is built once instead of per call. */
    private static final java.util.concurrent.ConcurrentHashMap<Method,String> KEYS=new java.util.concurrent.ConcurrentHashMap<>();
    private Object dispatch(Object proxy,Method method,Object[] input)throws Throwable{
        Object[] a=input==null?new Object[0]:input;
        if(method.getDeclaringClass()==Object.class){switch(method.getName()){case "toString":return "TripoderPackageEndpoint";case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==a[0];default:throw new AssertionError(method);}}
        String key=KEYS.computeIfAbsent(method,m->m.getName()+Arrays.toString(m.getParameterTypes()));
        if(android.util.Log.isLoggable("TripoderPackages",android.util.Log.DEBUG))android.util.Log.d("TripoderPackages","IPM call key="+key);
        switch(key){
            case "asBinder[]":return binder();
            case "getPackageInfo[class java.lang.String, long, int]":{
                user(a[2]);String pkg=(String)a[0];long flags=(Long)a[1];
                if(engine.webView.owns(pkg))return engine.webView.packageInfo(pkg,flags,(Integer)a[2]);
                if(externalMetadata(pkg))return dev.tripoder.selfhook.PackageFrameworkAccess.physical("package",pkg,flags,(Integer)a[2]);
                if(infrastructure(pkg))return RuntimePackageBridge.physicalPackageInfo(pkg,flags,(Integer)a[2]);
                return metadata.getPackageInfo(pkg,PackageManager.PackageInfoFlags.of(flags));
            }
            case "getPackageInfoVersioned[class android.content.pm.VersionedPackage, long, int]":{
                user(a[2]);VersionedPackage selected=(VersionedPackage)a[0];if(selected==null)throw new IllegalArgumentException("VERSIONED_PACKAGE_REQUIRED");
                if(externalMetadata(selected.getPackageName()))return dev.tripoder.selfhook.PackageFrameworkAccess.physical("package",selected.getPackageName(),(Long)a[1],(Integer)a[2]);
                if(infrastructure(selected.getPackageName()))return RuntimePackageBridge.physicalPackageInfo(selected.getPackageName(),(Long)a[1],(Integer)a[2]);
                return metadata.getPackageInfo(selected,PackageManager.PackageInfoFlags.of((Long)a[1]));
            }
            case "getApplicationInfo[class java.lang.String, long, int]":{
                user(a[2]);String pkg=(String)a[0];if(engine.webView.owns(pkg))return engine.webView.applicationInfo(pkg,(Long)a[1],(Integer)a[2]);if(externalMetadata(pkg))return dev.tripoder.selfhook.PackageFrameworkAccess.physical("application",pkg,(Long)a[1],(Integer)a[2]);if(infrastructure(pkg))return RuntimePackageBridge.physicalApplicationInfo(pkg,(Long)a[1],(Integer)a[2]);
                try {
                    ApplicationInfo ai = metadata.getApplicationInfo(pkg,PackageManager.ApplicationInfoFlags.of((Long)a[1]));
                    return ai;
                } catch (Throwable t) {
                    android.util.Log.w("TripoderPackages", "getApplicationInfo threw", t);
                    throw t;
                }
            }
            case "getServiceInfo[class android.content.ComponentName, long, int]":
            case "getProviderInfo[class android.content.ComponentName, long, int]":
            case "getActivityInfo[class android.content.ComponentName, long, int]":{
                user(a[2]);ComponentName component=(ComponentName)a[0];if(component==null)throw new IllegalArgumentException("COMPONENT_REQUIRED");
                if(method.getName().equals("getServiceInfo")&&engine.webView.owns(component.getPackageName()))return engine.webView.serviceInfo(component,(Long)a[1],(Integer)a[2]);
                if(externalMetadata(component.getPackageName()))return dev.tripoder.selfhook.PackageFrameworkAccess.physical(method.getName(),component,(Long)a[1],(Integer)a[2]);
                if(infrastructure(component.getPackageName()))return RuntimePackageBridge.physicalComponent(method.getName(),component,(Long)a[1],(Integer)a[2]);
                try{
                    if(method.getName().equals("getServiceInfo"))return metadata.getServiceInfo(component,PackageManager.ComponentInfoFlags.of((Long)a[1]));
                    if(method.getName().equals("getProviderInfo"))return metadata.getProviderInfo(component,PackageManager.ComponentInfoFlags.of((Long)a[1]));
                    return metadata.getActivityInfo(component,PackageManager.ComponentInfoFlags.of((Long)a[1]));
                }catch(PackageManager.NameNotFoundException missing){return null;}
            }
            case "resolveContentProvider[class java.lang.String, long, int]":user(a[2]);return metadata.resolveContentProvider((String)a[0],PackageManager.ComponentInfoFlags.of((Long)a[1]));
            case "hasSystemFeature[class java.lang.String, int]":return RuntimePackageBridge.physicalFeature((String)a[0],(Integer)a[1]);
            case "getSystemAvailableFeatures[]":return RuntimePackageBridge.physicalFeatures();
            case "getSystemSharedLibraryNames[]":return RuntimePackageBridge.physicalSystemSharedLibraryNames();
            case "getSdkSandboxPackageName[]":return RuntimePackageBridge.physicalSdkSandboxPackageName();
            case "getPermissionControllerPackageName[]":return engine.brokerCall(RuntimeWire.PERMISSION_CONTROLLER,engine.auth()).getString("package");
            case "notifyPackageUse[class java.lang.String, int]":RuntimePackageBridge.notifyWebViewUse((String)a[0],(Integer)a[1]);return null;
            case "notifyDexLoad[class java.lang.String, interface java.util.Map, class java.lang.String]":RuntimePackageBridge.notifyDexLoad((String)a[0],(Map<?,?>)a[1],(String)a[2]);return null;
            case "notifyPackagesReplacedReceived[class [Ljava.lang.String;]":RuntimePackageBridge.notifyPackagesReplacedReceived((String[])a[0]);return null;
            case "getComponentEnabledSetting[class android.content.ComponentName, int]":user(a[1]);return componentEnabled((ComponentName)a[0],(Integer)a[1]);
            case "setComponentEnabledSetting[class android.content.ComponentName, int, int, int, class java.lang.String]":
            case "setComponentEnabledSetting[class android.content.ComponentName, int, int, int]":{
                user(a[3]);setComponentEnabled((ComponentName)a[0],(Integer)a[1],(Integer)a[2]);return null;
            }
            case "setComponentEnabledSettings[interface java.util.List, int, class java.lang.String]":
            case "setComponentEnabledSettings[interface java.util.List, int]":{
                user(a[1]);
                java.util.List<?> list=(java.util.List<?>)a[0];
                if(list!=null)for(Object item:list){
                    if(item instanceof PackageManager.ComponentEnabledSetting setting)
                        setComponentEnabled(setting.getComponentName(),setting.getEnabledState(),setting.getEnabledFlags());
                }
                return null;
            }
            case "isInstantApp[class java.lang.String, int]":user(a[1]);infrastructure((String)a[0]);return false;
            case "getPropertyAsUser[class java.lang.String, class java.lang.String, class java.lang.String, int]":user(a[3]);return null;
            case "queryProperty[class java.lang.String, int]":return null;
            case "getPackagesForUid[int]":
                android.util.Log.w("TripoderProvenance","getPackagesForUid "+a[0]);
                if(((Integer)a[0])!=android.os.Process.myUid()){RuntimeFailThrough.note("package-endpoint","getPackagesForUid:other");return dev.tripoder.selfhook.PackageFrameworkAccess.physicalCall(method,a);}
                // Self plane: this endpoint is only reachable from inside this process, so the asker is the guest
                // asking about the uid it shares with the container. Answering the container's package name is the
                // leak measured on 2026-11-12 (uidPackages=[dev.tripoder.q6]): the answer contradicted getPackageName
                // in the same snapshot. Attribution questions (AppOps, storage, media provider) are resolved by the
                // system server from its own records and never reach this in-process endpoint.
                return new String[]{engine.packageName};
            // Provenance planes, answered here because the SDK facade reaches this endpoint: falling through to the
            // platform would report the real device's store attribution for the guest's own package, which is the
            // second leak measured on 2026-11-12 (installSource.initiating=com.android.shell).
            case "getInstallSourceInfo[class java.lang.String, int]":android.util.Log.w("TripoderProvenance","getInstallSourceInfo "+a[0]);user(a[1]);return GuestIdentity.installSourceInfo(engine,(String)a[0]);
            case "getInstallerPackageName[class java.lang.String]":android.util.Log.w("TripoderProvenance","getInstallerPackageName "+a[0]);return installer((String)a[0]);
            // Not translated yet: the platform's endpoint answers for the host package and enforces what an ordinary app may do.
            default:{
                Object selfAnswer=selfUidAnswer(key,a);
                if(selfAnswer!=null)return selfAnswer;
                RuntimeFailThrough.note("package-endpoint",key);
                return dev.tripoder.selfhook.PackageFrameworkAccess.physicalCall(method,RuntimeFailThrough.hostIdentity(a));
            }
        }
    }
    /**
     * uid <-> package for the guest's own archive must answer {@link android.os.Process#myUid()}, the same value the
     * kernel reports for this process. Falling through to the platform gives one process two identities for one
     * package name: getPackageUid(guest) would report the real device's uid for that archive, and getNameForUid(shared
     * uid) would report the container's package name. Both directions are "who am I" questions from the guest's side,
     * so they belong to the Self plane (identity-provenance-contract.md §3). Matched by prefix rather than by exact
     * key because the endpoint key embeds the parameter types and the platform ships several shapes of the same ask.
     *
     * @return the Self answer, or null when the question is about another package and the platform must answer.
     */
    private Object selfUidAnswer(String key,Object[] a){
        if(key.startsWith("getNameForUid[")){
            if(!(a[0] instanceof Integer))return null;
            int uid=(Integer)a[0];
            if(uid!=android.os.Process.myUid())return null;
            android.util.Log.w("TripoderProvenance","getNameForUid self uid="+uid);
            return engine.packageName;
        }
        if(!key.startsWith("getPackageUid["))return null;
        if(!(a[0] instanceof String))return null;
        String pkg=(String)a[0];
        if(!engine.packageName.equals(pkg)&&!engine.host.getPackageName().equals(pkg))return null;
        android.util.Log.w("TripoderProvenance","getPackageUid self pkg="+pkg+" uid="+android.os.Process.myUid());
        return android.os.Process.myUid();
    }
    synchronized Resources resources(PackageManager manager,String pkg,ApplicationInfo supplied){
        if(supplied!=null&&!Objects.equals(pkg,supplied.packageName))throw new SecurityException("XML_APPLICATION_IDENTITY_MISMATCH");
        if(engine.webView.owns(pkg))return engine.webView.resources(pkg,supplied);
        try{if(infrastructure(pkg))return infrastructureResources;}catch(PackageAbsent absent){throw new Resources.NotFoundException(absent.getMessage());}
        Resources view=resources.get(manager);
        if(view==null)throw new SecurityException("PACKAGE_RESOURCE_FACADE_UNREGISTERED");
        // A caller may retain a facade while its Resources gains a loader. Each
        // resource read must prove the actual current assets, independently of getPM.
        try{engine.webView.requireGuestResources(view);}
        catch(SecurityException denied){heldResourceOriginDenied(manager,view);throw denied;}
        return view;
    }
    private void heldResourceOriginDenied(PackageManager manager,Resources view){
        if(!engine.traceStartup||!heldResourceOriginReported.compareAndSet(false,true))return;
        try{
            var relations=dev.tripoder.selfhook.PackageFrameworkAccess.resourceRelations(manager,view,engine.resources,engine.resourceAssets);
            relations.put("boundary","HELD_MANAGER_RESOURCE_READ");relations.put("registered",registered(manager));
            relations.put("endpointCurrent",dev.tripoder.selfhook.PackageFrameworkAccess.endpoint(manager)==root);
            relations.put("frozenAssets",engine.resourceAssets.relations(view));relations.put("providerAssets",engine.webView.diagnosticAssetRelations(view));
            relations.put("nativeLabelComparisons",engine.resourceAssets.nativeLabelRelations(view,engine.applicationInfo.sourceDir,engine.info.sourceDir,engine.host.getApplicationInfo().sourceDir));
            android.util.Log.i("TripoderResourceOrigin",new org.json.JSONObject(relations).toString());
        }catch(Exception diagnostic){android.util.Log.w("TripoderResourceOrigin","HELD_RELATION_DIAGNOSTIC_FAILED:"+diagnostic.getClass().getName());}
    }
    XmlResourceParser xml(PackageManager manager,String pkg,int id,ApplicationInfo info){try{return resources(manager,pkg,info).getXml(id);}catch(Resources.NotFoundException absent){return null;}}
    CharSequence text(PackageManager manager,String pkg,int id,ApplicationInfo info){try{return resources(manager,pkg,info).getText(id);}catch(Resources.NotFoundException absent){return null;}}
    android.graphics.drawable.Drawable drawable(PackageManager manager,String pkg,int id,ApplicationInfo info){try{return resources(manager,pkg,info).getDrawable(id,null);}catch(Resources.NotFoundException absent){return null;}}
    private static int rawTransactionCode(String name,int fallback){
        try{return Class.forName("android.content.pm.IPackageManager$Stub").getDeclaredField(name).getInt(null);}
        catch(Throwable unavailable){return fallback;}
    }
    private final class Endpoint extends Binder {
        private final int rawGetPackageInfo=rawTransactionCode("TRANSACTION_getPackageInfo",1);
        private final int rawGetApplicationInfo=rawTransactionCode("TRANSACTION_getApplicationInfo",3);
        Endpoint(IInterface root){attachInterface(root,"android.content.pm.IPackageManager");}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString("android.content.pm.IPackageManager");return true;}
            if(code==rawGetPackageInfo){
                data.enforceInterface("android.content.pm.IPackageManager");
                String pkg=data.readString();long packageFlags=data.readLong();int userId=data.readInt();user(userId);
                PackageInfo result=null;
                try{
                    if(engine.webView.owns(pkg))result=engine.webView.packageInfo(pkg,packageFlags,userId);
                    else if(externalMetadata(pkg))result=(PackageInfo)dev.tripoder.selfhook.PackageFrameworkAccess.physical("package",pkg,packageFlags,userId);
                    else if(infrastructure(pkg))result=RuntimePackageBridge.physicalPackageInfo(pkg,packageFlags,userId);
                    else result=metadata.getPackageInfo(pkg,PackageManager.PackageInfoFlags.of(packageFlags));
                }catch(PackageManager.NameNotFoundException absent){}
                reply.writeNoException();
                if(result==null)reply.writeInt(0);
                else{reply.writeInt(1);result.writeToParcel(reply,0);}
                if(result!=null&&result.applicationInfo!=null)android.util.Log.i("TripoderPackages","RAW getPackageInfo pkg="+pkg+" uid="+result.applicationInfo.uid);
                return true;
            }
            if(code==rawGetApplicationInfo){
                data.enforceInterface("android.content.pm.IPackageManager");
                String pkg=data.readString();long applicationFlags=data.readLong();int userId=data.readInt();user(userId);
                ApplicationInfo result=null;
                try{
                    if(engine.webView.owns(pkg))result=engine.webView.applicationInfo(pkg,applicationFlags,userId);
                    else if(externalMetadata(pkg))result=(ApplicationInfo)dev.tripoder.selfhook.PackageFrameworkAccess.physical("application",pkg,applicationFlags,userId);
                    else if(infrastructure(pkg))result=RuntimePackageBridge.physicalApplicationInfo(pkg,applicationFlags,userId);
                    else result=metadata.getApplicationInfo(pkg,PackageManager.ApplicationInfoFlags.of(applicationFlags));
                }catch(PackageManager.NameNotFoundException absent){}
                reply.writeNoException();
                if(result==null)reply.writeInt(0);
                else{reply.writeInt(1);result.writeToParcel(reply,0);}
                if(result!=null)android.util.Log.i("TripoderPackages","RAW getApplicationInfo pkg="+pkg+" uid="+result.uid+" procUid="+android.os.Process.myUid()+" sourceDir="+result.sourceDir+" dataDir="+result.dataDir);
                return true;
            }
            // Unknown raw parcels retain the platform's own capability checks.
            RuntimeFailThrough.note("package-raw-transaction",Integer.toString(code));
            return dev.tripoder.selfhook.PackageFrameworkAccess.physicalBinder().transact(code,data,reply,flags);
        }
    }
}
