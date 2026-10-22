package dev.tripoder.runtime;
import android.net.wifi.p2p.WifiP2pManager;
import dev.tripoder.selfhook.ActivityFrameworkAccess;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.lang.reflect.*;
import java.util.*;
/** Small process boundaries around the existing Runtime capabilities. */
final class RuntimeSelfHookSystemEdges {
    private static final Set<String> UID=Set.of("getUidRxBytes(int)","getUidTxBytes(int)","getUidRxPackets(int)","getUidTxPackets(int)","getUidStats(int,int)");
    private static final Set<String> DEVICE=Set.of("getMobileRxBytes()","getMobileTxBytes()","getMobileRxPackets()","getMobileTxPackets()",
        "getTotalRxBytes()","getTotalTxBytes()","getTotalRxPackets()","getTotalTxPackets()","getRxBytes(java.lang.String)","getTxBytes(java.lang.String)","getRxPackets(java.lang.String)","getTxPackets(java.lang.String)");
    private static final Set<String> TAGGING=Set.of("setThreadStatsTag(int)","getThreadStatsTag()","clearThreadStatsTag()",
        "getAndSetThreadStatsTag(int)","getThreadStatsUid()","tagSocket(java.net.Socket)","untagSocket(java.net.Socket)",
        "tagDatagramSocket(java.net.DatagramSocket)","untagDatagramSocket(java.net.DatagramSocket)",
        "tagFileDescriptor(java.io.FileDescriptor)","untagFileDescriptor(java.io.FileDescriptor)",
        "incrementOperationCount(int)","incrementOperationCount(int,int)");
    private RuntimeSelfHookSystemEdges(){}
    static void traffic(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.TRAFFIC)){
            String key=RuntimeSelfHookManagerPolicy.signature((Method)target);seen.add(key);
            if(RuntimeFixedPassthrough.trafficMethod(key))continue;
            callbacks.put(target,(receiver,args,original)->{
                if(key.equals("getUidStats(int,int)")||key.equals("getIfaceStats(java.lang.String,int)"))RuntimeBridge.trafficKind((Integer)args[1]);
                if(key.equals("getTotalStats(int)"))RuntimeBridge.trafficKind((Integer)args[0]);
                if(UID.contains(key)){RuntimeBridge.trafficUid((Integer)args[0]);return original.call(args);}
                if(DEVICE.contains(key)||key.equals("getTotalStats(int)")||key.equals("getIfaceStats(java.lang.String,int)"))return original.call(args);
                // Tags label this UID's traffic. Attribution UID setters are a different
                // capability and must never enter through a method-name wildcard.
                if(TAGGING.contains(key))return original.call(args);
                throw new SecurityException("TRAFFIC_CAPABILITY_NOT_GRANTED:"+key);
            });
        }
        Set<String> required=new HashSet<>(UID);required.addAll(DEVICE);required.addAll(Set.of("getTotalStats(int)","getIfaceStats(java.lang.String,int)"));
        if(!seen.containsAll(required))throw new IllegalStateException("TRAFFIC_FIXED_SIGNATURES_INCOMPLETE");installer.install(callbacks);
    }
    static void wifiChannel(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.WIFI_CHANNEL))callbacks.put(target,(receiver,args,original)->{
            WifiP2pManager.Channel channel=(WifiP2pManager.Channel)receiver;if(!RuntimeBridge.ownsWifiP2pChannel(channel))return original.call(args);
            RuntimeBridge.wifiP2pBeginClose(channel);boolean confirmed=false;Throwable failure=null;
            try{Object result=original.call(args);confirmed=true;return result;}catch(Throwable error){failure=error;throw error;}
            finally{try{RuntimeBridge.wifiP2pEndClose(channel,confirmed);}catch(Throwable acknowledgement){if(failure==null)throw acknowledgement;if(acknowledgement!=failure)failure.addSuppressed(acknowledgement);}}
        });installer.install(callbacks);
    }
    static void ui(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.UI)){
            switch(target.getName()){
                case "playSoundEffect":callbacks.put(target,(receiver,args,original)->{RuntimeBridge.playUiSound(ActivityFrameworkAccess.view(receiver),(Integer)args[0]);return null;});break;
                case "autofillClientGetComponentName":callbacks.put(target,(receiver,args,original)->{
                    android.app.Activity activity=ActivityFrameworkAccess.autofillActivity(receiver);
                    if(!RuntimeBridge.owns(activity))return original.call(args);
                    // Autofill validates the component against the caller UID and real Activity
                    // token. Use this instance's admitted window, while Activity keeps its
                    // guest-visible component for application code.
                    return RuntimeEngine.current.physicalActivityComponent(activity);
                });break;
                default:throw new IllegalStateException("UI_FIXED_BINDING_UNHANDLED:"+target);
            }
        }
        installer.install(callbacks);
        RuntimeBiometrics.install(installer);
    }
    static void failures(RuntimeManagedInstaller installer)throws Exception{
        Map<Executable,RuntimeManagedInstaller.Callback> callbacks=new LinkedHashMap<>();
        for(Executable target:FixedRuntimeHooks.methods(FixedRuntimeHooks.Group.FAILURES))callbacks.put(target,(receiver,args,original)->{
            String name=target.getName(),stage;Throwable error;
            if(name.equals("dispatchUncaughtException")){stage="uncaught";error=(Throwable)args[0];}
            else if(name.equals("uncaughtException")){stage="thread-group-uncaught";error=(Throwable)args[1];}
            else{stage=name+":"+args[0];error=new Throwable();}
            try{RuntimeBridge.failureDiagnostic(stage,error);}finally{return original.call(args);}
        });installer.install(callbacks);
    }
}
