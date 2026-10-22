package dev.tripoder.runtime;

import android.app.Activity;
import android.content.*;
import dev.tripoder.selfhook.BiometricFrameworkAccess;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.lang.reflect.*;
import java.util.*;

/** Projects only an admitted guest prompt's system-facing identity. */
final class RuntimeBiometrics {
    private RuntimeBiometrics(){}
    static void install(RuntimeManagedInstaller installer)throws Exception{
        BiometricFrameworkAccess.prepare();
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.BIOMETRICS)){
            if(target instanceof Constructor<?>)callbacks.put(target,(receiver,args,original)->{
                Object[] projected=args.clone();projected[0]=project((Context)args[0]);return original.call(projected);
            });
            else callbacks.put(target,(receiver,args,original)->{
                Context context=BiometricFrameworkAccess.context(receiver);
                if(context instanceof PromptContext)((PromptContext)context).requirePermission();
                return original.call(args);
            });
        }
        installer.install(callbacks);
    }
    private static Context project(Context context){
        RuntimeEngine engine=Objects.requireNonNull(RuntimeEngine.current,"BIOMETRIC_ENGINE_REQUIRED");
        Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Context current=context;current!=null&&seen.size()<16&&seen.add(current);){
            if(current instanceof Activity&&!engine.ownsActivity((Activity)current))
                throw new SecurityException("BIOMETRIC_ACTIVITY_NOT_ADMITTED");
            if(current instanceof GuestContext){
                if(((GuestContext)current).engine!=engine)throw new SecurityException("BIOMETRIC_CONTEXT_ENGINE_MISMATCH");
                return new PromptContext(context,engine);
            }
            current=current instanceof ContextWrapper?((ContextWrapper)current).getBaseContext():null;
        }
        // A platform-owned prompt retains its original context and Android's own checks.
        return context;
    }
    private static final class PromptContext extends ContextWrapper {
        private final RuntimeEngine owner;
        PromptContext(Context logical,RuntimeEngine owner){super(logical);this.owner=owner;}
        void requirePermission(){
            if(owner!=RuntimeEngine.current)throw new SecurityException("BIOMETRIC_CONTEXT_ENGINE_MISMATCH");
            if(!owner.requestedPermissions.contains("android.permission.USE_BIOMETRIC")&&
                !owner.requestedPermissions.contains("android.permission.USE_FINGERPRINT"))
                throw new SecurityException("BIOMETRIC_PERMISSION_NOT_DECLARED");
        }
        // BiometricPrompt uses getPackageName for both authenticate and cancel,
        // rather than getOpPackageName. The app's original Context stays logical.
        @Override public String getPackageName(){return owner.host.getPackageName();}
        @Override public String getOpPackageName(){return owner.host.getOpPackageName();}
        @Override public String getAttributionTag(){return owner.host.getAttributionTag();}
        @Override public AttributionSource getAttributionSource(){return owner.host.getAttributionSource();}
        @Override public Context getApplicationContext(){return this;}
    }
}
