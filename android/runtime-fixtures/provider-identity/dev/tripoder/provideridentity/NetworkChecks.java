package dev.tripoder.provideridentity;

import android.content.Context;
import android.net.*;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

final class NetworkChecks {
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        Network active=manager.getActiveNetwork();Network[] networks=manager.getAllNetworks();
        check(checks,"network-real-default-present",active!=null&&networks.length>0);
        NetworkCapabilities capabilities=manager.getNetworkCapabilities(active);LinkProperties links=manager.getLinkProperties(active);
        check(checks,"network-real-capabilities-and-link",capabilities!=null&&links!=null&&links.getInterfaceName()!=null);
        check(checks,"network-state-legacy-queries",manager.getActiveNetworkInfo()!=null&&manager.getAllNetworkInfo()!=null&&manager.getNetworkInfo(active)!=null);
        manager.getDefaultProxy();manager.isActiveNetworkMetered();manager.getRestrictBackgroundStatus();
        CountDownLatch received=new CountDownLatch(1);AtomicReference<Network> callbackNetwork=new AtomicReference<>();AtomicReference<String> thread=new AtomicReference<>();
        ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network network){callbackNetwork.set(network);thread.set(Thread.currentThread().getName());received.countDown();}};
        manager.registerDefaultNetworkCallback(callback);
        try{
            check(checks,"network-real-default-callback",received.await(10,TimeUnit.SECONDS)&&callbackNetwork.get()!=null);
            if(!baseline){
                check(checks,"network-contexts-share-owned-facade",manager==context.getApplicationContext().getSystemService(ConnectivityManager.class));
                boolean duplicate=false;try{manager.registerDefaultNetworkCallback(callback);}catch(IllegalStateException expected){duplicate=expected.getMessage().contains("ALREADY_REGISTERED");}
                check(checks,"network-duplicate-registration-rejected",duplicate);
                // Binding a process to a network is process-local, which is exactly what the guest process is; unbinding is always allowed.
                boolean mutation=false;try{manager.bindProcessToNetwork(null);mutation=true;}catch(UnsupportedOperationException expected){mutation=false;}
                check(checks,"network-process-binding-is-process-local",mutation);
                boolean location=false;try{manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO){});}catch(UnsupportedOperationException expected){location=expected.getMessage().contains("NETWORK_LOCATION_CALLBACK_UNSUPPORTED");}
                check(checks,"network-location-callback-rejected",location);
            }
        }finally{manager.unregisterNetworkCallback(callback);}
        if(!baseline){
            boolean unregistered=false;try{manager.unregisterNetworkCallback(callback);}catch(IllegalArgumentException expected){unregistered=expected.getMessage().contains("NETWORK_CALLBACK_NOT_REGISTERED");}
            check(checks,"network-repeat-unregister-rejected",unregistered);
            HandlerThread blocked=new HandlerThread("owned-network-delivery");blocked.start();Handler handler=new Handler(blocked.getLooper());CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),drained=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
            handler.post(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            ConnectivityManager.NetworkCallback cancelled=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network value){calls.incrementAndGet();}};
            try{
                if(!entered.await(5,TimeUnit.SECONDS))throw new IllegalStateException("NETWORK_CALLBACK_BARRIER_FAILED");
                manager.registerDefaultNetworkCallback(cancelled,handler);manager.unregisterNetworkCallback(cancelled);release.countDown();handler.post(drained::countDown);
                check(checks,"network-cancelled-queued-callback-not-delivered",drained.await(5,TimeUnit.SECONDS)&&calls.get()==0);
            }finally{release.countDown();blocked.quitSafely();blocked.join(5000);}
        }
        return new JSONObject().put("activeHandle",active.getNetworkHandle()).put("callbackHandle",callbackNetwork.get().getNetworkHandle()).put("callbackThread",thread.get())
            .put("pid",android.os.Process.myPid()).put("networkCount",networks.length).put("metered",manager.isActiveNetworkMetered());
    }
    private NetworkChecks(){}
}
