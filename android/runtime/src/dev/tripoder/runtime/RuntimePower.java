package dev.tripoder.runtime;

import android.os.PowerManager;
import java.util.*;
import java.util.concurrent.Executor;

/** Read-only shared device state and real thermal observations, never power control. */
final class RuntimePower {
    private final RuntimeEngine engine;
    private volatile PowerManager facade;
    private final Map<Object,Registration> status=new IdentityHashMap<>(),headroom=new IdentityHashMap<>();
    RuntimePower(RuntimeEngine engine){this.engine=engine;}
    synchronized PowerManager facade(){if(facade==null){facade=RuntimeBridge.createPowerManager(engine.host);if(facade==null||facade==platform())throw new SecurityException("POWER_FACADE_IDENTITY_INVALID");}return facade;}
    boolean owns(PowerManager value){return value!=null&&value==facade;}
    PowerManager platform(){return Objects.requireNonNull(engine.host.getSystemService(PowerManager.class),"PLATFORM_POWER_UNAVAILABLE");}
    boolean ignoring(String packageName){if(!engine.packageName.equals(packageName))throw new SecurityException("POWER_PACKAGE_NOT_GRANTED");return platform().isIgnoringBatteryOptimizations(engine.host.getPackageName());}
    Map<Integer,Float> thresholds(){return new HashMap<>(platform().getThermalHeadroomThresholds());}
    void add(Object listener,Executor executor,boolean isHeadroom){
        if(listener==null||!engine.ownsReceiverLoader(listener.getClass().getClassLoader()))throw new SecurityException("THERMAL_LISTENER_OWNER_INVALID");
        Objects.requireNonNull(executor,"THERMAL_EXECUTOR_REQUIRED");Map<Object,Registration> records=isHeadroom?headroom:status;Registration registration=new Registration(listener,executor,records);
        synchronized(this){if(records.containsKey(listener))throw new IllegalArgumentException("THERMAL_LISTENER_ALREADY_REGISTERED");if(status.size()+headroom.size()>=32)throw new IllegalStateException("THERMAL_LISTENER_LIMIT");records.put(listener,registration);}
        try{
            if(isHeadroom)platform().addThermalHeadroomListener(Runnable::run,registration);else platform().addThermalStatusListener(Runnable::run,registration);
            List<Runnable> pending;
            synchronized(this){if(registration.overflow)throw new IllegalStateException("THERMAL_INITIAL_EVENTS_LIMIT");registration.phase="ACTIVE";pending=new ArrayList<>(registration.pending);registration.pending.clear();}
            for(Runnable event:pending)registration.submit(event);
        }catch(RuntimeException|Error error){synchronized(this){registration.phase="FAILED";registration.pending.clear();}throw error;}
    }
    void remove(Object listener,boolean isHeadroom){
        Map<Object,Registration> records=isHeadroom?headroom:status;Registration registration;
        synchronized(this){registration=records.get(listener);if(registration==null)throw new IllegalArgumentException("THERMAL_LISTENER_NOT_REGISTERED");if(registration.phase.equals("REGISTERING")||registration.phase.equals("REMOVING"))throw new IllegalStateException("THERMAL_LISTENER_OPERATION_IN_PROGRESS");registration.phase="REMOVING";registration.pending.clear();}
        try{if(isHeadroom)platform().removeThermalHeadroomListener(registration);else platform().removeThermalStatusListener(registration);synchronized(this){records.remove(listener);}}
        catch(RuntimeException|Error error){synchronized(this){registration.phase="FAILED";}throw error;}
    }
    Executor defaultExecutor(){return engine.host.getMainExecutor();}
    private final class Registration implements PowerManager.OnThermalStatusChangedListener,PowerManager.OnThermalHeadroomChangedListener {
        final Object guest;final Executor executor;final Map<Object,Registration> records;
        final List<Runnable> pending=new ArrayList<>();String phase="REGISTERING";boolean overflow;
        Registration(Object guest,Executor executor,Map<Object,Registration> records){this.guest=guest;this.executor=executor;this.records=records;}
        void record(Runnable event){synchronized(RuntimePower.this){if(phase.equals("REGISTERING")){if(pending.size()>=32)overflow=true;else pending.add(event);return;}if(!phase.equals("ACTIVE"))return;}submit(event);}
        void submit(Runnable event){executor.execute(()->{synchronized(RuntimePower.this){if(!phase.equals("ACTIVE")||records.get(guest)!=this)return;}event.run();});}
        @Override public void onThermalStatusChanged(int value){record(()->((PowerManager.OnThermalStatusChangedListener)guest).onThermalStatusChanged(value));}
        @Override public void onThermalHeadroomChanged(float current,float forecast,int seconds,Map<Integer,Float> thresholds){Map<Integer,Float> copy=Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(thresholds,"THERMAL_THRESHOLDS_REQUIRED")));record(()->((PowerManager.OnThermalHeadroomChangedListener)guest).onThermalHeadroomChanged(current,forecast,seconds,copy));}
    }
}
