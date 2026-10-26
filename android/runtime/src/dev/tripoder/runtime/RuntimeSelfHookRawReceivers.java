package dev.tripoder.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import dev.tripoder.selfhook.ApplicationAdapter;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Raw logical ContextImpl entries share the existing receiver owner and ledger. */
final class RuntimeSelfHookRawReceivers {
    static void install(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.CONTEXT_RECEIVERS)){
            Method method=(Method)target;
            callbacks.put(method,(receiver,args,original)->{
                if(!RuntimePackageBridge.active())return original.call(args);
                RuntimeEngine engine=RuntimeEngine.current;Context source=(Context)receiver;
                // Physical system/provider Contexts belong to the platform route.
                if(!engine.packageName.equals(source.getPackageName()))return original.call(args);
                ApplicationAdapter.requireLogicalContext(source);
                BroadcastReceiver listener=(BroadcastReceiver)args[0];
                if(method.getName().equals("unregisterReceiver")){engine.receivers.unregister(listener);return null;}
                if(!method.getName().equals("registerReceiver"))throw new IllegalStateException("RAW_RECEIVER_ENTRY_INVALID");
                String permission=args.length>=4?(String)args[2]:null;
                Handler scheduler=args.length>=4?(Handler)args[3]:null;
                int flags=args.length==3?(Integer)args[2]:args.length==5?(Integer)args[4]:0;
                return engine.receivers.register(listener,(IntentFilter)args[1],permission,scheduler,flags);
            });
        }
        installer.install(callbacks);
    }
    private RuntimeSelfHookRawReceivers(){}
}
