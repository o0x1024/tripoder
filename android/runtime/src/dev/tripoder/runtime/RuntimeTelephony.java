package dev.tripoder.runtime;

import android.telephony.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Device capability queries only; the platform manager keeps its physical identity. */
final class RuntimeTelephony {
    private final RuntimeEngine engine;
    private volatile TelephonyManager facade;
    private final Map<TelephonyCallback,SignalRegistration> callbacks=new IdentityHashMap<>();
    RuntimeTelephony(RuntimeEngine engine){this.engine=engine;}
    synchronized TelephonyManager facade(){
        if(facade==null){facade=RuntimeBridge.createTelephonyManager(engine.host);if(facade==null||facade==platform())throw new SecurityException("TELEPHONY_FACADE_IDENTITY_INVALID");}
        return facade;
    }
    boolean owns(TelephonyManager manager){return manager!=null&&manager==facade;}
    TelephonyManager platform(){TelephonyManager manager=engine.host.getSystemService(TelephonyManager.class);if(manager==null)throw new IllegalStateException("PLATFORM_TELEPHONY_UNAVAILABLE");return manager;}
    synchronized void register(Executor executor,TelephonyCallback callback){
        Objects.requireNonNull(executor,"TELEPHONY_EXECUTOR_REQUIRED");
        if(callback==null||!engine.ownsLoader(callback.getClass().getClassLoader()))throw new SecurityException("TELEPHONY_CALLBACK_OWNER_INVALID");
        Set<String> interfaces=new TreeSet<>();for(Class<?> type=callback.getClass();type!=null&&type!=TelephonyCallback.class;type=type.getSuperclass())for(Class<?> implemented:type.getInterfaces())interfaces(implemented,interfaces);
        if(!interfaces.equals(Set.of(TelephonyCallback.SignalStrengthsListener.class.getName())))throw new UnsupportedOperationException("TELEPHONY_CALLBACK_INTERFACES_UNSUPPORTED:"+String.join(",",interfaces));
        if(callbacks.containsKey(callback))throw new IllegalStateException("TELEPHONY_CALLBACK_ALREADY_REGISTERED");
        if(callbacks.size()>=16)throw new IllegalStateException("TELEPHONY_CALLBACK_LIMIT");
        SignalRegistration registration=new SignalRegistration((TelephonyCallback.SignalStrengthsListener)callback,executor);callbacks.put(callback,registration);
        try{
            // Platform delivery enters our main executor; guest execution uses exactly
            // its supplied executor after admission. No guest callback under this lock.
            platform().registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE,engine.host.getMainExecutor(),registration);
            registration.active=true;
        }catch(RuntimeException|Error error){registration.active=false;throw error;}
    }
    synchronized void unregister(TelephonyCallback callback){
        SignalRegistration registration=callbacks.get(callback);if(registration==null)throw new IllegalArgumentException("TELEPHONY_CALLBACK_NOT_REGISTERED");
        registration.active=false;platform().unregisterTelephonyCallback(registration);callbacks.remove(callback);
    }
    private static void interfaces(Class<?> type,Set<String> result){
        if(type.getName().startsWith("android.telephony.TelephonyCallback$"))result.add(type.getName());
        for(Class<?> parent:type.getInterfaces())interfaces(parent,result);
    }
    private final class SignalRegistration extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener {
        final SignalStrengthsListener guest;final Executor executor;boolean active;
        SignalRegistration(SignalStrengthsListener guest,Executor executor){this.guest=guest;this.executor=executor;}
        @Override public void onSignalStrengthsChanged(SignalStrength signal){
            synchronized(RuntimeTelephony.this){if(!active)return;}
            SignalStrength snapshot=new SignalStrength(Objects.requireNonNull(signal,"PLATFORM_SIGNAL_MISSING"));
            executor.execute(()->{synchronized(RuntimeTelephony.this){if(!active)return;}guest.onSignalStrengthsChanged(snapshot);});
        }
    }
}
