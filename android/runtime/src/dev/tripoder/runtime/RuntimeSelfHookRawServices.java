package dev.tripoder.runtime;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.UserHandle;
import dev.tripoder.selfhook.ApplicationAdapter;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Raw logical ContextImpl service binds share the one engine service route. An application that unwraps its
 * ContextWrapper chain and binds the innermost Context (Chrome, for its GPU process) would otherwise bind the
 * real installed package directly and bypass the guest service world. */
final class RuntimeSelfHookRawServices {
    static void install(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.CONTEXT_SERVICES)){
            Method method=(Method)target;
            callbacks.put(method,(receiver,args,original)->{
                if(!RuntimePackageBridge.active())return original.call(args);
                RuntimeEngine engine=RuntimeEngine.current;Context source=(Context)receiver;
                // Physical system/provider Contexts belong to the platform route.
                if(!engine.packageName.equals(source.getPackageName()))return original.call(args);
                ApplicationAdapter.requireLogicalContext(source);
                Intent intent=(Intent)args[0];ServiceConnection connection=(ServiceConnection)args[1];Handler handler=(Handler)args[3];UserHandle user=(UserHandle)args[4];
                if(user==null||!user.equals(android.os.Process.myUserHandle()))throw new SecurityException("Requires INTERACT_ACROSS_USERS permission to bind as another user");
                long flags=args[2] instanceof Integer?(Integer)args[2]:RuntimeBridge.bindServiceFlags((Context.BindServiceFlags)args[2]);
                Executor executor=handler==null?source.getMainExecutor():handler::post;
                if(engine.webView.services.targets(intent))return engine.webView.services.bind(intent,connection,flags,executor,null);
                try{return engine.services.bind(source,intent,connection,Math.toIntExact(flags),executor);}
                catch(RuntimeException error){throw error;}
                catch(Exception error){throw new IllegalStateException("SERVICE_BIND_FAILED",error);}
            });
        }
        installer.install(callbacks);
    }
    private RuntimeSelfHookRawServices(){}
}
