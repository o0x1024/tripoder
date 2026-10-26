package dev.tripoder.runtime;

import android.Manifest;
import android.content.Context;
import android.net.*;
import android.os.Handler;
import java.util.*;

/** Platform network observations and requests, with scoped callback ownership and real permissions. */
final class RuntimeConnectivity {
    private final RuntimeEngine engine;
    private volatile ConnectivityManager facade;
    private final Map<ConnectivityManager.NetworkCallback,Registration> callbacks=new IdentityHashMap<>();
    RuntimeConnectivity(RuntimeEngine engine){this.engine=engine;}
    synchronized ConnectivityManager facade(){
        if(facade==null){facade=RuntimeBridge.createConnectivityManager(engine.host);if(facade==null||facade==physical())throw new SecurityException("CONNECTIVITY_FACADE_IDENTITY_INVALID");}
        return facade;
    }
    boolean owns(ConnectivityManager manager){return manager!=null&&manager==facade;}
    private ConnectivityManager physical(){ConnectivityManager manager=engine.host.getSystemService(ConnectivityManager.class);if(manager==null)throw new IllegalStateException("PLATFORM_CONNECTIVITY_UNAVAILABLE");return manager;}
    ConnectivityManager platform(){
        if(!engine.requestedPermissions.contains(Manifest.permission.ACCESS_NETWORK_STATE))throw new SecurityException("GUEST_PERMISSION_NOT_DECLARED:android.permission.ACCESS_NETWORK_STATE");
        return physical();
    }
    synchronized void register(NetworkRequest request,ConnectivityManager.NetworkCallback callback,Handler handler,boolean defaults){
        ConnectivityManager platform=platform();
        if(callback==null||!engine.ownsReceiverLoader(callback.getClass().getClassLoader()))throw new SecurityException("NETWORK_CALLBACK_OWNER_INVALID");
        if(RuntimeBridge.networkCallbackFlags(callback)!=0)throw new UnsupportedOperationException("NETWORK_LOCATION_CALLBACK_UNSUPPORTED");
        if(callbacks.containsKey(callback))throw new IllegalStateException("NETWORK_CALLBACK_ALREADY_REGISTERED");
        if(callbacks.size()>=32)throw new IllegalStateException("NETWORK_CALLBACK_LIMIT");
        if(!defaults&&request==null)throw new IllegalArgumentException("NETWORK_REQUEST_REQUIRED");
        Registration registration=new Registration(callback);callbacks.put(callback,registration);
        try{
            if(defaults){if(handler==null)platform.registerDefaultNetworkCallback(registration);else platform.registerDefaultNetworkCallback(registration,handler);}
            else{if(handler==null)platform.registerNetworkCallback(request,registration);else platform.registerNetworkCallback(request,registration,handler);}
            registration.active=true;
        }catch(RuntimeException|Error error){registration.active=false;registration.failed=true;throw error;}
    }
    synchronized void unregister(ConnectivityManager.NetworkCallback callback){
        Registration registration=callbacks.get(callback);if(registration==null)throw new IllegalArgumentException("NETWORK_CALLBACK_NOT_REGISTERED");
        registration.active=false;physical().unregisterNetworkCallback(registration);callbacks.remove(callback);
    }
    synchronized void request(NetworkRequest request,ConnectivityManager.NetworkCallback callback,Handler handler,Integer timeout){
        if(!engine.requestedPermissions.contains(Manifest.permission.CHANGE_NETWORK_STATE)
                &&!(engine.requestedPermissions.contains(Manifest.permission.WRITE_SETTINGS)&&android.provider.Settings.System.canWrite(engine.host)))throw new SecurityException("GUEST_NETWORK_REQUEST_PERMISSION_NOT_GRANTED");
        Objects.requireNonNull(request,"NETWORK_REQUEST_REQUIRED");
        if(callback==null||!engine.ownsReceiverLoader(callback.getClass().getClassLoader()))throw new SecurityException("NETWORK_CALLBACK_OWNER_INVALID");
        if(RuntimeBridge.networkCallbackFlags(callback)!=0)throw new UnsupportedOperationException("NETWORK_LOCATION_CALLBACK_UNSUPPORTED");
        if(callbacks.containsKey(callback))throw new IllegalStateException("NETWORK_CALLBACK_ALREADY_REGISTERED");
        if(callbacks.size()>=32)throw new IllegalStateException("NETWORK_CALLBACK_LIMIT");
        Registration registration=new Registration(callback);registration.active=true;callbacks.put(callback,registration);
        try{
            ConnectivityManager system=physical();
            if(timeout==null){if(handler==null)system.requestNetwork(request,registration);else system.requestNetwork(request,registration,handler);}
            else{if(handler==null)system.requestNetwork(request,registration,timeout);else system.requestNetwork(request,registration,handler,timeout);}
        }catch(RuntimeException|Error failure){registration.active=false;registration.failed=true;callbacks.remove(callback);throw failure;}
    }
    private final class Registration extends ConnectivityManager.NetworkCallback {
        final ConnectivityManager.NetworkCallback guest;boolean active,failed;
        Registration(ConnectivityManager.NetworkCallback guest){this.guest=guest;}
        private void deliver(Runnable action){
            // Admission is serialized with cancel; a callback already admitted may finish.
            synchronized(RuntimeConnectivity.this){if(!active||failed)return;}
            action.run();
        }
        @Override public void onAvailable(Network network){deliver(()->guest.onAvailable(network));}
        @Override public void onLosing(Network network,int millis){deliver(()->guest.onLosing(network,millis));}
        @Override public void onLost(Network network){deliver(()->guest.onLost(network));}
        @Override public void onUnavailable(){deliver(guest::onUnavailable);}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){deliver(()->guest.onCapabilitiesChanged(network,new NetworkCapabilities(capabilities)));}
        @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){deliver(()->{android.os.Parcel parcel=android.os.Parcel.obtain();try{properties.writeToParcel(parcel,0);parcel.setDataPosition(0);guest.onLinkPropertiesChanged(network,LinkProperties.CREATOR.createFromParcel(parcel));}finally{parcel.recycle();}});}
        @Override public void onBlockedStatusChanged(Network network,boolean blocked){deliver(()->guest.onBlockedStatusChanged(network,blocked));}
        @Override public void onReserved(NetworkCapabilities capabilities){deliver(()->guest.onReserved(new NetworkCapabilities(capabilities)));}
    }
}
