package dev.tripoder.runtime;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Guest location facade. Without a sandbox mock fix every call is forwarded to the real platform manager.
 * With one, last-known/current/updates all report the mock position; real fixes are no longer delivered.
 * Only listener based updates are supported (no PendingIntent/GNSS callbacks).
 */
final class RuntimeLocation {
    private static final long TICK_MS=1000;
    private final RuntimeEngine engine;
    private volatile LocationManager facade;
    private final List<Registration> registrations=new ArrayList<>();
    private final Handler ticker=new Handler(Looper.getMainLooper());
    private boolean ticking;
    RuntimeLocation(RuntimeEngine engine){this.engine=engine;}
    synchronized LocationManager facade(){
        if(facade==null){facade=RuntimeBridge.createLocationManager(engine.host);if(facade==null||facade==platform())throw new SecurityException("LOCATION_FACADE_IDENTITY_INVALID");}
        return facade;
    }
    boolean owns(LocationManager value){return value!=null&&value==facade;}
    LocationManager platform(){return Objects.requireNonNull(engine.host.getSystemService(LocationManager.class),"PLATFORM_LOCATION_UNAVAILABLE");}

    static Object dispatch(String key,Object[] args)throws Throwable{return RuntimeEngine.current.location.handle(key,args);}
    /** Called after the persona fix changes so already-registered listeners learn about it without waiting for a new request. */
    static void personaChanged(){RuntimeEngine engine=RuntimeEngine.current;if(engine!=null)engine.location.schedule(0);}

    private static Location mock(RuntimeSandboxPersona.Fix fix,String provider){
        Location out=new Location(provider);out.setLatitude(fix.latitude());out.setLongitude(fix.longitude());out.setAccuracy(fix.accuracy());
        if(fix.altitude()!=null)out.setAltitude(fix.altitude());
        out.setSpeed(fix.speed());out.setBearing(fix.bearing());
        out.setTime(RuntimeTimeWarp.currentTimeMillis());out.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return out;
    }

    private Object handle(String key,Object[] a)throws Throwable{
        RuntimeSandboxPersona.Fix fix=RuntimeSandboxPersona.locationFix();
        LocationManager real=platform();
        switch(key){
            case "getLastKnownLocation(java.lang.String)":return fix!=null?mock(fix,(String)a[0]):real.getLastKnownLocation((String)a[0]);
            case "isProviderEnabled(java.lang.String)":return fix!=null?Boolean.TRUE:real.isProviderEnabled((String)a[0]);
            case "isLocationEnabled()":return fix!=null?Boolean.TRUE:Boolean.valueOf(real.isLocationEnabled());
            case "getCurrentLocation(java.lang.String,android.os.CancellationSignal,java.util.concurrent.Executor,java.util.function.Consumer)":{
                @SuppressWarnings("unchecked") Consumer<Location> consumer=(Consumer<Location>)a[3];Executor executor=(Executor)a[2];
                if(fix==null){real.getCurrentLocation((String)a[0],(CancellationSignal)a[1],executor,consumer);return null;}
                Location value=mock(fix,(String)a[0]);executor.execute(()->consumer.accept(value));return null;
            }
            case "requestLocationUpdates(java.lang.String,long,float,android.location.LocationListener)":return register((String)a[0],(Long)a[1],(Float)a[2],(LocationListener)a[3],null,null);
            case "requestLocationUpdates(java.lang.String,long,float,android.location.LocationListener,android.os.Looper)":return register((String)a[0],(Long)a[1],(Float)a[2],(LocationListener)a[3],(Looper)a[4],null);
            case "requestLocationUpdates(java.lang.String,long,float,java.util.concurrent.Executor,android.location.LocationListener)":return register((String)a[0],(Long)a[1],(Float)a[2],(LocationListener)a[4],null,(Executor)a[3]);
            case "removeUpdates(android.location.LocationListener)":return unregister((LocationListener)a[0]);
            default:throw new UnsupportedOperationException("LOCATION_CAPABILITY_UNSUPPORTED:"+key);
        }
    }

    /** One real platform registration per guest listener; its events are forwarded only while no mock fix is active. */
    private final class Registration implements LocationListener {
        final LocationListener guest;final String provider;final long minTimeMs;final Executor executor;long lastDeliveredMs;
        Registration(LocationListener guest,String provider,long minTimeMs,Executor executor){this.guest=guest;this.provider=provider;this.minTimeMs=Math.max(0,minTimeMs);this.executor=executor;}
        void post(Runnable work){executor.execute(work);}
        @Override public void onLocationChanged(Location location){if(RuntimeSandboxPersona.locationFix()==null)post(()->guest.onLocationChanged(location));}
        @Override public void onLocationChanged(List<Location> locations){if(RuntimeSandboxPersona.locationFix()==null)post(()->guest.onLocationChanged(locations));}
        @Override public void onProviderEnabled(String name){post(()->guest.onProviderEnabled(name));}
        @Override public void onProviderDisabled(String name){if(RuntimeSandboxPersona.locationFix()==null)post(()->guest.onProviderDisabled(name));}
    }

    private Object register(String provider,long minTime,float minDistance,LocationListener guest,Looper looper,Executor executor)throws Throwable{
        Objects.requireNonNull(guest,"LOCATION_LISTENER_REQUIRED");Objects.requireNonNull(provider,"LOCATION_PROVIDER_REQUIRED");
        Executor target=executor!=null?executor:new Handler(looper!=null?looper:Looper.myLooper()!=null?Looper.myLooper():Looper.getMainLooper())::post;
        Registration registration=new Registration(guest,provider,minTime,target);
        synchronized(this){for(Registration existing:registrations)if(existing.guest==guest){registrations.remove(existing);try{platform().removeUpdates(existing);}catch(RuntimeException ignored){}break;}registrations.add(registration);}
        try{platform().requestLocationUpdates(provider,minTime,minDistance,Runnable::run,registration);}
        catch(RuntimeException error){
            // A sandbox without real location access can still serve the mock position.
            if(RuntimeSandboxPersona.locationFix()==null){synchronized(this){registrations.remove(registration);}throw error;}
            android.util.Log.w("TripoderRuntime","LOCATION_REAL_REGISTRATION_SKIPPED:"+error);
        }
        schedule(0);
        return null;
    }

    private Object unregister(LocationListener guest){
        Registration found=null;
        synchronized(this){for(Registration existing:registrations)if(existing.guest==guest){found=existing;break;}if(found!=null)registrations.remove(found);}
        platform().removeUpdates(found==null?guest:found);
        return null;
    }

    /** Starts the delivery loop if needed and always pushes the current fix once, immediately (fix just changed or listener just added). */
    private synchronized void schedule(long delayMs){
        ticker.postDelayed(()->deliver(true),delayMs);
        if(!ticking){ticking=true;ticker.postDelayed(this::tick,TICK_MS);}
    }

    private void tick(){
        synchronized(this){if(RuntimeSandboxPersona.locationFix()==null||registrations.isEmpty()){ticking=false;return;}}
        deliver(false);
        ticker.postDelayed(this::tick,TICK_MS);
    }

    private void deliver(boolean force){
        RuntimeSandboxPersona.Fix fix=RuntimeSandboxPersona.locationFix();if(fix==null)return;
        List<Registration> snapshot;synchronized(this){snapshot=new ArrayList<>(registrations);}
        long now=SystemClock.elapsedRealtime();
        for(Registration registration:snapshot){
            if(!force&&registration.lastDeliveredMs!=0&&now-registration.lastDeliveredMs<registration.minTimeMs)continue;
            registration.lastDeliveredMs=now;Location value=mock(fix,registration.provider);
            registration.post(()->registration.guest.onLocationChanged(value));
        }
    }
}
