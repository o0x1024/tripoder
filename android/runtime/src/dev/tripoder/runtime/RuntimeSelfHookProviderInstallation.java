package dev.tripoder.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import dev.tripoder.selfhook.ApplicationAdapter;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import dev.tripoder.selfhook.PhysicalApplication;
import java.lang.reflect.Executable;
import java.util.*;

/** The private framework batch entry shares the existing authenticated provider publications. */
final class RuntimeSelfHookProviderInstallation {
    static void install(RuntimeManagedInstaller installer)throws Exception{
        Class<?> contextImpl=Class.forName("android.app.ContextImpl",false,null);
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable method:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.PROVIDER_INSTALLATION))callbacks.put(method,(receiver,args,original)->{
            if(!RuntimePackageBridge.active())return original.call(args);
            Context source=(Context)args[0];Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            while(source!=null&&source.getClass()!=contextImpl&&seen.size()<16&&seen.add(source)&&source instanceof ContextWrapper)source=((ContextWrapper)source).getBaseContext();
            if(source==null||source.getClass()!=contextImpl)throw new SecurityException("PROVIDER_INSTALL_CONTEXT_REQUIRED");
            RuntimeEngine engine=RuntimeEngine.current;
            if(!engine.packageName.equals(source.getPackageName()))return original.call(args);
            if(receiver!=PhysicalApplication.thread())throw new SecurityException("PROVIDER_INSTALL_THREAD_MISMATCH");
            ApplicationAdapter.requireLogicalContext(source);
            if(!(args[1] instanceof List<?>))throw new IllegalArgumentException("PROVIDER_INSTALL_BATCH_REQUIRED");
            engine.providers.installFromFramework((List<?>)args[1]);return null;
        });
        installer.install(callbacks);
    }
    private RuntimeSelfHookProviderInstallation(){}
}
