package dev.tripoder.runtime;
import android.app.Application;
import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import dalvik.system.DexClassLoader;
import dev.tripoder.selfhook.*;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Actual permanent installer and native application ownership, before guest DEX execution. */
public final class OwnedEarlyApplicationChecks {
    private static FrameworkAccess.Members members;private static Application host;private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new IllegalStateException(name);}
    public static void prepare(Application physical)throws Exception {
        ManagedHook.initializeProcess();members=FrameworkAccess.prepare();host=physical;
        PhysicalApplication.prepare(host,members);PhysicalApplication.check();ApplicationAdapter.prepareOwnership(host,members);ApplicationAdapter.beginView();
        RuntimeManagedInstaller installer=new RuntimeManagedInstaller(java.util.Map.of());Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        callbacks.put((Method)members.methods[1],(receiver,args,original)->ApplicationAdapter.current());
        callbacks.put(FrameworkAccess.processNameMethod(),(receiver,args,original)->"owned.logical.process");installer.install(callbacks);
        check(installer.installedCount()==2&&ApplicationAdapter.current()==null,"EARLY_TWO_REAL_BOOT_ENTRIES");
    }
    public static JSONObject run()throws Exception {
        check(ApplicationAdapter.current()==null&&((Method)members.methods[1]).invoke(null)==null,"NO_HOST_APPLICATION_BEFORE_FACTORY");
        check(Application.getProcessName().equals("owned.logical.process"),"LOGICAL_PROCESS_NAME_BEFORE_FACTORY");PhysicalApplication.check();
        ApplicationInfo info=new ApplicationInfo(host.getApplicationInfo());info.packageName="dev.tripoder.selfhookprobe.logical";info.processName="owned.logical.process";
        File data=new File(host.getFilesDir(),"early-application");check(data.isDirectory()||data.mkdir(),"EARLY_OWNED_DATA_DIRECTORY");info.dataDir=data.getCanonicalPath();
        ClassLoader loader=new DexClassLoader(info.sourceDir,null,null,ClassLoader.getSystemClassLoader().getParent());
        Class<?> factoryType=Class.forName("dev.tripoder.selfhookprobe.EarlyFactoryFixture",false,loader);
        check(factoryType.getClassLoader()==loader,"SEPARATE_GUEST_LOADER");
        AppComponentFactory factory=(AppComponentFactory)factoryType.getConstructor().newInstance();info.appComponentFactory=factoryType.getName();
        ClassLoader selected=factory.instantiateClassLoader(loader,info);check(selected==loader,"GUEST_FACTORY_LOADER_CALLBACK");
        Application guest=factory.instantiateApplication(selected,"dev.tripoder.selfhookprobe.EarlyApplicationFixture");
        check((Integer)factoryType.getMethod("phases").invoke(null)==6,"ALL_FACTORY_AND_APPLICATION_CONSTRUCTION_PHASES");
        members.fields[0].setAccessible(true);
        check(ApplicationAdapter.current()==null&&members.fields[0].get(((Method)members.methods[0]).invoke(null))==null,"NULL_VIEW_UNTIL_GUEST_PUBLISHED");
        PhysicalApplication.check();Context logical=ApplicationAdapter.create(host,guest,info,selected,selected,factory,host.getResources());
        check(ApplicationAdapter.current()==guest&&((Method)members.methods[1]).invoke(null)==guest,"GUEST_PUBLISHED_ONCE_AFTER_CONSTRUCTION");
        check(logical.getApplicationContext()==guest&&logical.getPackageName().equals(info.packageName),"REAL_LOGICAL_CONTEXT");
        ApplicationAdapter.attach(logical);ApplicationAdapter.check();PhysicalApplication.check();guest.onCreate();ApplicationAdapter.check();PhysicalApplication.check();
        check((Integer)factoryType.getMethod("phases").invoke(null)==8,"ATTACH_AND_ON_CREATE_LOGICAL_IDENTITY");
        System.gc();check(ApplicationAdapter.current()==guest,"PUBLISHED_GUEST_GLOBAL_ROOT_AFTER_GC_REQUEST");
        JSONObject state=new JSONObject(ApplicationAdapter.reportNative());check(state.getInt("constructs")==1&&state.getInt("attaches")==1&&state.getInt("viewRoots")==9,"ONE_LOADED_APK_ATTACH_AND_ROOT_SET");
        ApplicationAdapter.closeProbeView();PhysicalApplication.check();check(ApplicationAdapter.current()==host&&((Method)members.methods[1]).invoke(null)==host,"PROBE_CLOSE_RESTORES_PHYSICAL_VIEW");
        return new JSONObject().put("checks",checks).put("guestPhases",8).put("installedBootEntries",2).put("preparedOffMainThread",true)
            .put("nullBeforeConstruction",true).put("physicalOwnershipPreserved",true).put("native",new JSONObject(ApplicationAdapter.reportNative())).put("productAccepted",false);
    }
}
