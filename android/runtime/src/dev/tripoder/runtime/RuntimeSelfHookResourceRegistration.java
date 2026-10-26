package dev.tripoder.runtime;

import android.content.pm.ApplicationInfo;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.lang.reflect.Executable;
import java.util.LinkedHashMap;
import java.util.Map;

/** One selected, precompiled WebView resource dependency enters through its real framework registration. */
final class RuntimeSelfHookResourceRegistration {
    static void install(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.RESOURCE_REGISTRATION))callbacks.put(target,(receiver,args,original)->{
            if(!RuntimePackageBridge.active())return original.call(args);
            return RuntimeEngine.current.webView.registerResources(receiver,(String)args[0],(ApplicationInfo)args[1],original);
        });
        installer.install(callbacks);
    }
    private RuntimeSelfHookResourceRegistration(){}
}
