package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.*;
import android.content.res.loader.*;
import android.os.*;
import dev.tripoder.selfhook.*;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.*;

/** FD-defined guest application and service, constructed once on this isolated main thread. */
final class RuntimeIsolatedApplication {
    final Service service;private final Bundle input;private final List<ResourcesProvider> providers=new ArrayList<>();
    private final ResourceFrameworkAccess.AssetSnapshot assets;private final Resources resources;private final ClassLoader loader;
    private final RuntimePublishedNativeCode.Verified nativeCode;
    RuntimeIsolatedApplication(Service physical,Bundle input,Intent original)throws Exception{
        if(!android.os.Process.isIsolated()||Looper.myLooper()!=Looper.getMainLooper())throw new SecurityException("ISOLATED_MAIN_THREAD_REQUIRED");
        this.input=input;try{RuntimePlatform.requireValidated();System.loadLibrary("tripoderselfhook");
        var members=RuntimeFixedPreparation.framework();RuntimeFixedPreparation.process();
        var host=Objects.requireNonNull(RuntimeApplication.host);PhysicalApplication.prepare(host,members);PhysicalApplication.check(host);ApplicationAdapter.prepareOwnership(host,members);ApplicationAdapter.beginView();
        String process=RuntimeWire.required(input,"processName");ApplicationInfo info=input.getParcelable("info",ApplicationInfo.class);
        if(info==null||info.uid!=input.getInt("ownerUid")||info.uid!=host.getApplicationInfo().uid||info.packageName.equals(host.getPackageName()))throw new SecurityException("ISOLATED_APPLICATION_OWNER_MISMATCH");
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        callbacks.put((Method)members.methods[1],(r,a,o)->ApplicationAdapter.current());callbacks.put(FrameworkAccess.activityThreadApplicationMethod(),(r,a,o)->ApplicationAdapter.current());
        callbacks.put(FrameworkAccess.processNameMethod(),(r,a,o)->process);callbacks.put((Method)members.methods[FrameworkAccess.CURRENT_PACKAGE_NAME],(r,a,o)->info.packageName);
        var installer=new RuntimeManagedInstaller(Map.of(),true);installer.install(callbacks);installer.publishPrepared();ManagedHook.completeBootstrap();
        Bundle publication=input.getBundle("nativeCode");nativeCode=publication==null?null:RuntimePublishedNativeCode.verify(publication,RuntimeWire.required(input,"space"),info.packageName,RuntimeWire.required(input,"installGeneration"),RuntimeWire.required(input,"descriptorSha"));
        loader=RuntimeIsolatedCode.loader(input,nativeCode);Thread.currentThread().setContextClassLoader(loader);
        var apk=input.getParcelableArrayList("apks",ParcelFileDescriptor.class);var hashes=input.getStringArrayList("apkHashes");
        if(apk==null||hashes==null||apk.isEmpty()||apk.size()!=hashes.size()||apk.size()>ArtifactSet.MAX_PARTS)throw new SecurityException("ISOLATED_RESOURCE_INPUT_INVALID");
        Resources system=Resources.getSystem();Resources root=new Resources(ResourceFrameworkAccess.independentSystemAssets(system),system.getDisplayMetrics(),new Configuration(system.getConfiguration()));ResourcesLoader loaded=new ResourcesLoader();
        String[] paths=new String[apk.size()];
        for(int i=0;i<apk.size();i++){RuntimeIsolatedCode.verifyApk(apk.get(i),hashes.get(i));paths[i]="/proc/self/fd/"+apk.get(i).getFd();var provider=ResourcesProvider.loadFromApk(apk.get(i));providers.add(provider);loaded.addProvider(provider);}
        root.addLoaders(loaded);resources=ResourceFrameworkAccess.forLoader(root,loader);assets=ResourceFrameworkAccess.systemPlusLoaders(resources,system,providers.size());
        info.sourceDir=paths[0];info.publicSourceDir=paths[0];info.splitSourceDirs=paths.length>1?Arrays.copyOfRange(paths,1,paths.length):null;info.splitPublicSourceDirs=info.splitSourceDirs;
        if(nativeCode!=null)nativeCode.metadata(info);
        ApplicationAdapter.prepareIsolatedPackage(host,info,loader,resources,process);AppComponentFactory factory=ApplicationAdapter.createFactory(info,loader);ApplicationAdapter.publishFactory(factory);
        if(factory.instantiateClassLoader(loader,new ApplicationInfo(info))!=loader)throw new UnsupportedOperationException("ISOLATED_REPLACEMENT_LOADER_NOT_IMPLEMENTED");ApplicationAdapter.publishLoader(loader);
        String appName=info.className==null?Application.class.getName():info.className;Application application=factory.instantiateApplication(loader,appName);
        if(application==null||(!appName.equals(Application.class.getName())&&application.getClass().getClassLoader()!=loader))throw new SecurityException("ISOLATED_APPLICATION_LOADER_MISMATCH");
        Context logical=ApplicationAdapter.createIsolated(host,application,info,loader,factory,resources);Context context=new RuntimeIsolatedContext(logical,info,loader,resources,application);ApplicationAdapter.attach(context);application.onCreate();assets.requireEquivalent(resources);ApplicationAdapter.check();
        String component=RuntimeWire.required(input,"component");
        service=factory.instantiateService(loader,component,null);if(service==null||service.getClass().getClassLoader()!=loader)throw new SecurityException("ISOLATED_SERVICE_LOADER_MISMATCH");
        ServiceInstanceAccess.attach(physical,service,context,application);service.onCreate();assets.requireEquivalent(resources);ApplicationAdapter.check();
        }catch(Exception|Error error){throw error;}
    }
    Intent intent(Intent physical){return RuntimeServiceIntent.delivery(physical,new ComponentName(service.getPackageName(),RuntimeWire.required(input,"component")),loader);}
    void check(){assets.requireEquivalent(resources);ApplicationAdapter.check();}
    // ResourcesProvider owns native references still used by guest/background
    // frames. They live until this single-owner process actually exits.
    void close(){RuntimeIsolatedCode.closeDescriptors(input);}
}
