package dev.tripoder.provideridentity;

import android.content.*;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.*;
import java.lang.reflect.Proxy;
import org.json.*;

final class WifiP2pChecks {
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        WifiP2pManager manager=context.getSystemService(WifiP2pManager.class);
        check(checks,"wifi-p2p-manager-stable",manager!=null&&manager==context.getSystemService(Context.WIFI_P2P_SERVICE));
        HandlerThread thread=new HandlerThread("owned-wifi-p2p-observer");thread.start();WifiP2pManager.Channel channel=null;
        try{
            stage("initialize");channel=manager.initialize(context,thread.getLooper(),null);check(checks,"wifi-p2p-real-channel-created",channel!=null);stage("initialized");
            JSONObject capabilities=new JSONObject().put("constrainedDiscovery",manager.isChannelConstrainedDiscoverySupported()).put("clientRemoval",manager.isGroupClientRemovalSupported()).put("ipv6LinkLocal",manager.isGroupOwnerIPv6LinkLocalAddressProvided()).put("pccMode",manager.isPccModeSupported()).put("vendorElements",manager.isSetVendorElementsSupported()).put("directR2",manager.isWiFiDirectR2Supported());
            if(!baseline){
                boolean scan=false,group=false,identity=false,fake=false,callback=false,nullLooper=false;
                try{manager.discoverPeers(channel,null);}catch(UnsupportedOperationException expected){scan=expected.getMessage().contains("WIFI_P2P_CAPABILITY_UNSUPPORTED");}
                try{manager.removeGroup(channel,null);}catch(UnsupportedOperationException expected){group=expected.getMessage().contains("WIFI_P2P_CAPABILITY_UNSUPPORTED");}
                try{manager.requestDeviceInfo(channel,device->{});}catch(UnsupportedOperationException expected){identity=expected.getMessage().contains("WIFI_P2P_CAPABILITY_UNSUPPORTED");}
                try{manager.initialize(new ContextWrapper(null){@Override public String getPackageName(){return context.getPackageName();}},thread.getLooper(),null);}catch(SecurityException expected){fake=expected.getMessage().contains("WIFI_P2P_CONTEXT_OWNER_INVALID");}
                WifiP2pManager.ChannelListener foreign=(WifiP2pManager.ChannelListener)Proxy.newProxyInstance(null,new Class<?>[]{WifiP2pManager.ChannelListener.class},(p,m,args)->{if(m.getName().equals("toString"))return "Owned bootstrap callback probe";if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==args[0];return null;});
                try{manager.initialize(context,thread.getLooper(),foreign);}catch(SecurityException expected){callback=expected.getMessage().contains("WIFI_P2P_CALLBACK_OWNER_INVALID");}
                try{manager.initialize(context,null,null);}catch(NullPointerException expected){nullLooper=expected.getMessage().contains("WIFI_P2P_LOOPER_REQUIRED");}
                check(checks,"wifi-p2p-radio-and-device-identity-not-granted",scan&&group&&identity);
                check(checks,"wifi-p2p-context-and-callback-owner-required",fake&&callback);
                check(checks,"wifi-p2p-null-looper-not-replaced",nullLooper);
            }
            stage("close-first");channel.close();stage("close-repeat");channel.close();channel=null;check(checks,"wifi-p2p-real-close-and-repeat-return",true);stage("close-complete");
            // Sequential churn crosses the per-worker bound without retaining closed channels.
            for(int i=0;i<18;i++){stage("churn-"+i);WifiP2pManager.Channel next=manager.initialize(context,thread.getLooper(),()->{});if(next==null)throw new AssertionError("wifi-p2p-churn-real-channel");next.close();}
            check(checks,"wifi-p2p-closed-channels-release-owned-capacity",true);
            return new JSONObject().put("realChannelsCreated",19).put("realCloseCalls",20).put("capabilities",capabilities).put("radioMutationPerformed",false).put("disconnectCallbackObserved",false);
        }finally{if(channel!=null)channel.close();thread.quitSafely();thread.join(3000);if(thread.isAlive())throw new IllegalStateException("WIFI_P2P_TEST_LOOPER_DID_NOT_STOP");}
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private static void stage(String value){android.util.Log.i("TripoderWifiFixture",value);}
    private WifiP2pChecks(){}
}
