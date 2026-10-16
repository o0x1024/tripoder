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
            else callbacks.put(method,(receiver,args,original)->RuntimePackageBridge.active()?route((PackageManager)receiver,method,args):original.call(args));
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
        throw new IllegalArgumentException("PACKAGE_FLAGS_REQUIRED");
    }
    private static Object found(Object value,Object key)throws PackageManager.NameNotFoundException{if(value==null)throw new PackageManager.NameNotFoundException(String.valueOf(key));return value;}
    private static Object route(PackageManager manager,Method method,Object[] args)throws Throwable{
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
            case "getResourcesForApplication(java.lang.String)":return RuntimePackageBridge.resources(manager,(String)args[0],null);
            case "getResourcesForApplication(android.content.pm.ApplicationInfo)":{ApplicationInfo info=(ApplicationInfo)Objects.requireNonNull(args[0],"APPLICATION_INFO_REQUIRED");return RuntimePackageBridge.resources(manager,info.packageName,info);}
            case "getXml(java.lang.String,int,android.content.pm.ApplicationInfo)":return RuntimePackageBridge.xml(manager,(String)args[0],(Integer)args[1],(ApplicationInfo)args[2]);
            case "getText(java.lang.String,int,android.content.pm.ApplicationInfo)":return RuntimePackageBridge.text(manager,(String)args[0],(Integer)args[1],(ApplicationInfo)args[2]);
            case "getDrawable(java.lang.String,int,android.content.pm.ApplicationInfo)":return RuntimePackageBridge.drawable(manager,(String)args[0],(Integer)args[1],(ApplicationInfo)args[2]);
            case "getApplicationLabel(android.content.pm.ApplicationInfo)":{ApplicationInfo info=(ApplicationInfo)Objects.requireNonNull(args[0],"APPLICATION_INFO_REQUIRED");RuntimePackageBridge.resources(manager,info.packageName,info);return info.loadLabel(manager);}
            default:throw new UnsupportedOperationException("PACKAGE_MANAGER_OPERATION_UNSUPPORTED:"+key);
        }
    }
}
