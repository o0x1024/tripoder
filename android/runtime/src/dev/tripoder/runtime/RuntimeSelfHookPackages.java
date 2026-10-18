package dev.tripoder.runtime;
import android.content.*;
import android.content.pm.*;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import dev.tripoder.selfhook.PackageFrameworkAccess;
import java.lang.reflect.*;
import java.util.*;
/** Package interception uses the existing single RuntimePackages world. */
final class RuntimeSelfHookPackages {
    private RuntimeSelfHookPackages(){}
    static void install(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.PACKAGES)){
            Method method=(Method)target;
            if(method.getDeclaringClass().getName().equals("android.app.ContextImpl"))callbacks.put(method,(receiver,args,original)->{
                if(!RuntimePackageBridge.active())return original.call(args);
                Context context=(Context)receiver;PackageManager cached=PackageFrameworkAccess.cached(context);
                if(cached!=null&&RuntimePackageBridge.registered(cached))return cached;
                PackageManager value=RuntimePackageBridge.contextManager(context);PackageFrameworkAccess.cache(context,value);return value;
            });
            else if(method.getDeclaringClass().getName().equals("android.os.ServiceManager"))callbacks.put(method,(receiver,args,original)->{
                if(!RuntimePackageBridge.active()||!"package".equals(args[0]))return original.call(args);
                if(args.length==1&&Set.of("getService","getServiceOrThrow","checkService","waitForService","waitForDeclaredService").contains(method.getName()))return RuntimePackageBridge.localBinder();
                throw new UnsupportedOperationException("PACKAGE_SERVICE_ENTRY_UNSUPPORTED:"+method.getName());
            });
            else callbacks.put(method,(receiver,args,original)->RuntimePackageBridge.active()&&!(method.getName().equals("getSystemAvailableFeatures")&&args.length==0)?route((PackageManager)receiver,method,args,original):original.call(args));
        }
        installer.install(callbacks);
        RuntimeEngine.current.packages.initialize();
        if(!RuntimePackageBridge.active())throw new IllegalStateException("PACKAGE_WORLD_NOT_ACTIVE_AFTER_INSTALL");
    }
    static void activate(Object root,Context host){
        RuntimePackageBridge.beginActivation(root,host,PackageFrameworkAccess.systemContext());
        PackageManager original=host.getPackageManager();PackageFrameworkAccess.publish(root,original);RuntimePackageBridge.registerInfrastructureFacade(original);
    }
    private static long flags(Object value){
        if(value instanceof Integer)return ((Integer)value)&0xffffffffL;
        if(value instanceof PackageManager.PackageInfoFlags)return ((PackageManager.PackageInfoFlags)value).getValue();
        if(value instanceof PackageManager.ApplicationInfoFlags)return ((PackageManager.ApplicationInfoFlags)value).getValue();
        if(value instanceof PackageManager.ComponentInfoFlags)return ((PackageManager.ComponentInfoFlags)value).getValue();
        if(value instanceof PackageManager.ResolveInfoFlags)return ((PackageManager.ResolveInfoFlags)value).getValue();
        throw new IllegalArgumentException("PACKAGE_FLAGS_REQUIRED");
    }
    private static Object found(Object value,Object key)throws PackageManager.NameNotFoundException{if(value==null)throw new PackageManager.NameNotFoundException(String.valueOf(key));return value;}
    private static Object route(PackageManager manager,Method method,Object[] args,RuntimeManagedInstaller.Original original)throws Throwable{
        String name=method.getName(),key=RuntimeSelfHookManagerPolicy.signature(method);int user=RuntimePackageBridge.userId();
        if(Set.of("getPackageInfo","getApplicationInfo","getServiceInfo","getProviderInfo","getActivityInfo","resolveContentProvider").contains(name)&&args.length==2){
            Class<?> selector=method.getParameterTypes()[0];
            if(selector==String.class||selector==ComponentName.class||name.equals("getPackageInfo")&&selector==VersionedPackage.class){
                String query=selector==VersionedPackage.class?"getPackageInfoVersioned":name;
                Object answer=PackageFrameworkAccess.query(manager,query,args[0],flags(args[1]),user);return name.equals("resolveContentProvider")?answer:found(answer,args[0]);
            }
        }
        switch(key){
            case "getDefaultActivityIcon()":return android.content.res.Resources.getSystem().getDrawable(android.R.drawable.sym_def_app_icon,null);
            case "hasSystemFeature(java.lang.String)":return PackageFrameworkAccess.query(manager,"hasSystemFeature",args[0],0);
            case "hasSystemFeature(java.lang.String,int)":return PackageFrameworkAccess.query(manager,"hasSystemFeature",args);
            case "isInstantApp()":return PackageFrameworkAccess.query(manager,"isInstantApp",RuntimePackageBridge.packageName(),user);
            case "isInstantApp(java.lang.String)":return PackageFrameworkAccess.query(manager,"isInstantApp",args[0],user);
            case "getPackagesForUid(int)":return PackageFrameworkAccess.query(manager,"getPackagesForUid",args);
            case "getSdkSandboxPackageName()":return PackageFrameworkAccess.query(manager,"getSdkSandboxPackageName");
            case "getComponentEnabledSetting(android.content.ComponentName)":return PackageFrameworkAccess.query(manager,"getComponentEnabledSetting",args[0],user);
            case "setComponentEnabledSetting(android.content.ComponentName,int,int)":RuntimePackageBridge.setComponentEnabled((ComponentName)args[0],(Integer)args[1],(Integer)args[2]);return null;
            case "getInstallerPackageName(java.lang.String)":return RuntimePackageBridge.installer((String)args[0]);
            case "getResourcesForApplication(java.lang.String)":return RuntimePackageBridge.resources(manager,(String)args[0],null);
            case "getResourcesForApplication(android.content.pm.ApplicationInfo)":{ApplicationInfo info=(ApplicationInfo)Objects.requireNonNull(args[0],"APPLICATION_INFO_REQUIRED");return RuntimePackageBridge.resources(manager,info.packageName,info);}
            case "getXml(java.lang.String,int,android.content.pm.ApplicationInfo)":return RuntimePackageBridge.xml(manager,(String)args[0],(Integer)args[1],(ApplicationInfo)args[2]);
            case "getText(java.lang.String,int,android.content.pm.ApplicationInfo)":return RuntimePackageBridge.text(manager,(String)args[0],(Integer)args[1],(ApplicationInfo)args[2]);
            case "getDrawable(java.lang.String,int,android.content.pm.ApplicationInfo)":return RuntimePackageBridge.drawable(manager,(String)args[0],(Integer)args[1],(ApplicationInfo)args[2]);
            case "getApplicationLabel(android.content.pm.ApplicationInfo)":{ApplicationInfo info=(ApplicationInfo)Objects.requireNonNull(args[0],"APPLICATION_INFO_REQUIRED");RuntimePackageBridge.resources(manager,info.packageName,info);return info.loadLabel(manager);}
            // Resolution is the manifest's own intent-filters applied with Android's matching rule. Other packages are not part of the guest's world.
            case "resolveActivity(android.content.Intent,int)":
            case "resolveActivity(android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags)":{List<ResolveInfo> hits=GuestIntentResolver.query(RuntimeEngine.current,"activity",(Intent)args[0],flags(args[1]));return hits.isEmpty()?null:hits.get(0);}
            case "resolveService(android.content.Intent,int)":
            case "resolveService(android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags)":{List<ResolveInfo> hits=GuestIntentResolver.query(RuntimeEngine.current,"service",(Intent)args[0],flags(args[1]));return hits.isEmpty()?null:hits.get(0);}
            case "queryIntentActivities(android.content.Intent,int)":
            case "queryIntentActivities(android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags)":return GuestIntentResolver.query(RuntimeEngine.current,"activity",(Intent)args[0],flags(args[1]));
            case "queryIntentServices(android.content.Intent,int)":
            case "queryIntentServices(android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags)":return GuestIntentResolver.query(RuntimeEngine.current,"service",(Intent)args[0],flags(args[1]));
            case "queryBroadcastReceivers(android.content.Intent,int)":
            case "queryBroadcastReceivers(android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags)":return GuestIntentResolver.query(RuntimeEngine.current,"receiver",(Intent)args[0],flags(args[1]));
            case "queryIntentContentProviders(android.content.Intent,int)":
            case "queryIntentContentProviders(android.content.Intent,android.content.pm.PackageManager$ResolveInfoFlags)":return Collections.emptyList();
            case "checkPermission(java.lang.String,java.lang.String)":return RuntimeEngine.current.requestedPermissions.contains((String)args[0])?PackageManager.PERMISSION_GRANTED:PackageManager.PERMISSION_DENIED;
            default:return fallback(manager,method,args,original);
        }
    }
    /** The package world a guest sees is itself, the host infrastructure and nothing else. Anything outside it is absent; anything about the platform as a whole is the platform's own answer;
     *  anything that would change another package's state is refused the way Android refuses an unprivileged caller. Nothing here is an "unsupported operation" the app cannot have handled. */
    private static Object fallback(PackageManager manager,Method method,Object[] args,RuntimeManagedInstaller.Original original)throws Throwable{
        String name=method.getName();RuntimeEngine engine=RuntimeEngine.current;int user=RuntimePackageBridge.userId();
        String own=engine.packageName;
        switch(name){
            // Pure functions of a file or of the whole platform: the platform's own implementation is the answer.
            // The permission dialog belongs to the platform's permission controller and is requested for this process's package, which holds the permission on the guest's behalf.
            case "buildRequestPermissionsIntent":case "getPermissionControllerPackageName":case "getPackageArchiveInfo":case "getSystemSharedLibraryNames":case "getSharedLibraries":case "getAllPermissionGroups":case "getPermissionGroupInfo":
            case "queryPermissionsByGroup":case "getPermissionInfo":case "isSafeMode":case "getInstantAppCookieMaxBytes":case "getInstantAppCookie":
                return original.call(args);
            case "canRequestPackageInstalls":return false;
            case "isPermissionRevokedByPolicy":return false;
            case "getChangedPackages":return null;
            case "canonicalToCurrentPackageNames":case "currentToCanonicalPackageNames":return args[0];
            case "getPreferredActivities":return 0;
            case "getPreferredPackages":case "queryInstrumentation":case "queryContentProviders":return new ArrayList<>();
            case "getInstrumentationInfo":throw new PackageManager.NameNotFoundException(String.valueOf(args[0]));
            case "getInstalledPackages":{Object value=PackageFrameworkAccess.query(manager,"getPackageInfo",own,flags(args[0]),user);ArrayList<Object> list=new ArrayList<>();if(value!=null)list.add(value);return list;}
            case "getInstalledApplications":{Object value=PackageFrameworkAccess.query(manager,"getApplicationInfo",own,flags(args[0]),user);ArrayList<Object> list=new ArrayList<>();if(value!=null)list.add(value);return list;}
            case "getPackagesHoldingPermissions":{
                ArrayList<Object> list=new ArrayList<>();
                for(String permission:(String[])args[0])if(engine.requestedPermissions.contains(permission)){Object value=PackageFrameworkAccess.query(manager,"getPackageInfo",own,flags(args[1]),user);if(value!=null)list.add(value);break;}
                return list;
            }
            case "getPackageUid":{String pkg=(String)args[0];if(own.equals(pkg)||engine.host.getPackageName().equals(pkg))return android.os.Process.myUid();throw new PackageManager.NameNotFoundException(pkg);}
            case "getNameForUid":return ((Integer)args[0])==android.os.Process.myUid()?own:null;
            case "getPackageGids":{String pkg=(String)args[0];if(!own.equals(pkg))throw new PackageManager.NameNotFoundException(pkg);Object[] mapped=args.clone();mapped[0]=engine.host.getPackageName();return original.call(mapped);}
            case "checkSignatures":{
                boolean known=args[0] instanceof Integer?((Integer)args[0])==android.os.Process.myUid()&&((Integer)args[1])==android.os.Process.myUid():own.equals(args[0])&&own.equals(args[1]);
                return known?PackageManager.SIGNATURE_MATCH:PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            case "getApplicationEnabledSetting":{String pkg=(String)args[0];if(own.equals(pkg))return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;throw new IllegalArgumentException("Unknown package: "+pkg);}
            case "getLaunchIntentForPackage":case "getLeanbackLaunchIntentForPackage":return RuntimePackageBridge.launchIntent((String)args[0],name.startsWith("getLeanback"));
            case "getApplicationIcon":case "getApplicationLogo":case "getApplicationBanner":{
                Object target=args[0];ApplicationInfo info=target instanceof ApplicationInfo?(ApplicationInfo)target:(ApplicationInfo)found(PackageFrameworkAccess.query(manager,"getApplicationInfo",target,0L,user),target);
                RuntimePackageBridge.resources(manager,info.packageName,info);
                return name.equals("getApplicationIcon")?info.loadIcon(manager):name.equals("getApplicationLogo")?info.loadLogo(manager):info.loadBanner(manager);
            }
            case "getUserBadgedIcon":return args[0];
            case "getUserBadgedLabel":return args[0];
            case "getUserBadgedDrawableForDensity":return args[0];
            case "getResourcesForActivity":{ComponentName component=(ComponentName)args[0];if(!own.equals(component.getPackageName()))throw new PackageManager.NameNotFoundException(component.flattenToString());return RuntimePackageBridge.resources(manager,own,null);}
            case "getReceiverInfo":{
                ComponentName component=(ComponentName)args[0];if(!own.equals(component.getPackageName()))throw new PackageManager.NameNotFoundException(component.flattenToString());
                ActivityInfo declared=RuntimePackageBridge.receiverInfo(component.getClassName(),flags(args[1]));return found(declared,component.flattenToString());
            }
            // Changing what another package is, or what handles what, is not something an ordinary app is allowed to do; Android's answer is a SecurityException.
            case "setApplicationEnabledSetting":case "setInstallerPackageName":case "setApplicationCategoryHint":case "addPreferredActivity":case "clearPackagePreferredActivities":
            case "addPackageToPreferred":case "removePackageFromPreferred":case "extendVerificationTimeout":case "verifyPendingInstall":case "addPermission":case "addPermissionAsync":
            case "removePermission":case "updateInstantAppCookie":case "clearInstantAppCookie":
                throw new SecurityException("PACKAGE_MUTATION_DENIED:"+name);
            default:throw new UnsupportedOperationException("PACKAGE_MANAGER_OPERATION_UNSUPPORTED:"+RuntimeSelfHookManagerPolicy.signature(method));
        }
    }
}
