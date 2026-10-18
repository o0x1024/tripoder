package dev.tripoder.runtime;

import android.Manifest;
import android.content.*;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;
import java.util.*;

/** Real client channels, without granting discovery, peer identity, or radio mutations. */
final class RuntimeWifiP2p {
    private final RuntimeEngine engine;
    private volatile WifiP2pManager facade,physical;
    private final Map<WifiP2pManager.Channel,Registration> channels=new IdentityHashMap<>();
    private int pending;
    RuntimeWifiP2p(RuntimeEngine engine){this.engine=engine;}
    synchronized WifiP2pManager facade(){if(facade==null){facade=RuntimeBridge.createWifiP2pManager(null);if(facade==null)throw new IllegalStateException("WIFI_P2P_FACADE_REQUIRED");}return facade;}
    boolean owns(WifiP2pManager value){return value!=null&&value==facade;}
    synchronized boolean owns(WifiP2pManager.Channel value){return value!=null&&channels.containsKey(value);}
    synchronized WifiP2pManager platform(){if(physical==null){physical=RuntimeBridge.createWifiP2pManager(engine.host);if(physical==null||physical==facade)throw new IllegalStateException("PLATFORM_WIFI_P2P_REQUIRED");}return physical;}
    private void context(Context candidate){
        Set<Context> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while(candidate!=null&&seen.add(candidate)&&seen.size()<=32){
            if(candidate==engine.application||candidate instanceof GuestContext&&((GuestContext)candidate).engine==engine)return;
            candidate=candidate instanceof ContextWrapper?((ContextWrapper)candidate).getBaseContext():null;
        }
        throw new SecurityException("WIFI_P2P_CONTEXT_OWNER_INVALID");
    }
    private void permissions(){for(String permission:List.of(Manifest.permission.ACCESS_WIFI_STATE,Manifest.permission.CHANGE_WIFI_STATE))if(!engine.requestedPermissions.contains(permission))throw new SecurityException("GUEST_PERMISSION_NOT_DECLARED:"+permission);}
    WifiP2pManager.Channel initialize(Context context,Looper looper,WifiP2pManager.ChannelListener listener){
        context(context);permissions();Objects.requireNonNull(looper,"WIFI_P2P_LOOPER_REQUIRED");
        if(!looper.getThread().isAlive())throw new IllegalStateException("WIFI_P2P_LOOPER_CLOSED");
        if(listener!=null&&!engine.ownsReceiverLoader(listener.getClass().getClassLoader()))throw new SecurityException("WIFI_P2P_CALLBACK_OWNER_INVALID");
        Registration registration=new Registration(listener);
        synchronized(this){if(channels.size()+pending>=16)throw new IllegalStateException("WIFI_P2P_CHANNEL_LIMIT");pending++;}
        try{
            WifiP2pManager.Channel channel=platform().initialize(engine.host,looper,listener==null?null:registration);
            synchronized(this){if(channel==null)registration.active=false;else channels.put(channel,registration);}
            return channel;
        }catch(RuntimeException|Error error){synchronized(this){registration.active=false;}throw error;}
        finally{synchronized(this){pending--;}}
    }
    synchronized void beginClose(WifiP2pManager.Channel channel){
        Registration registration=channels.get(channel);if(registration==null)throw new SecurityException("WIFI_P2P_CHANNEL_OWNER_INVALID");
        if(registration.closing)throw new IllegalStateException("WIFI_P2P_CHANNEL_CLOSE_IN_PROGRESS");registration.active=false;registration.closing=true;
    }
    synchronized void endClose(WifiP2pManager.Channel channel,boolean confirmed){
        Registration registration=channels.get(channel);if(registration==null||!registration.closing)throw new IllegalStateException("WIFI_P2P_CHANNEL_CLOSE_NOT_STARTED");
        if(confirmed)channels.remove(channel);else registration.closing=false;
    }
    private final class Registration implements WifiP2pManager.ChannelListener {
        final WifiP2pManager.ChannelListener guest;boolean active=true,closing;
        Registration(WifiP2pManager.ChannelListener guest){this.guest=guest;}
        @Override public void onChannelDisconnected(){synchronized(RuntimeWifiP2p.this){if(!active)return;active=false;}guest.onChannelDisconnected();}
    }
}
