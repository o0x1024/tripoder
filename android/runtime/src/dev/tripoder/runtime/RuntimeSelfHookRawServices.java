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
                ServiceConnection connection=(ServiceConnection)args[method.getName().equals("bindServiceCommon")?1:0];
                switch(method.getName()){
                    case "unbindService":
                        if(engine.webView.services.owns(connection))engine.webView.services.unbind(connection);else engine.services.unbind(connection);return null;
                    case "rebindService":
                        if(engine.webView.services.owns(connection))engine.webView.services.rebind(connection,(Context.BindServiceFlags)args[1]);else engine.services.rebind(connection,(Context.BindServiceFlags)args[1]);return null;
                    case "updateServiceGroup":
                        if(engine.webView.services.owns(connection))engine.webView.services.updateGroup(connection,(Integer)args[1],(Integer)args[2]);else engine.services.updateGroup(connection,(Integer)args[1],(Integer)args[2]);return null;
                    case "bindServiceCommon":break;
                    default:throw new IllegalStateException("RAW_SERVICE_ENTRY_INVALID");
                }
                Intent intent=(Intent)args[0];long flags=(Long)args[2];String instance=(String)args[3];
                if((flags&Integer.toUnsignedLong(Context.BIND_EXTERNAL_SERVICE))!=0)flags=(flags&~Integer.toUnsignedLong(Context.BIND_EXTERNAL_SERVICE))|Context.BIND_EXTERNAL_SERVICE_LONG;
                Handler handler=(Handler)args[4];Executor executor=(Executor)args[5];UserHandle user=(UserHandle)args[6];
                if(user==null||!user.equals(android.os.Process.myUserHandle()))throw new SecurityException("Requires INTERACT_ACROSS_USERS permission to bind as another user");
                if(handler!=null&&executor!=null)throw new IllegalArgumentException("Handler and Executor both supplied");
                if(handler!=null)executor=handler::post;
                // Route on the installed physical Context. Reusing source here
                // would both recurse and submit a logical package to AMS.
                if(engine.webView.services.targets(intent))return engine.webView.services.bind(intent,connection,flags,executor,instance);
                if(flags<0||flags>Integer.MAX_VALUE)throw new UnsupportedOperationException("SERVICE_BIND_FLAGS_UNSUPPORTED");
                try{return instance==null?engine.services.bind(engine.host,intent,connection,(int)flags,executor):engine.services.bindIsolated(engine.host,intent,connection,(int)flags,executor,instance);}
                catch(RuntimeException error){throw error;}
                catch(Exception error){throw new IllegalStateException("SERVICE_BIND_FAILED",error);}

            });
        }
        installer.install(callbacks);
    }
    private RuntimeSelfHookRawServices(){}
}
