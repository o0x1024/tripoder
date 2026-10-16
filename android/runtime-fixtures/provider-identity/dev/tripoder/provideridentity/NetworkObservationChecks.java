package dev.tripoder.provideridentity;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.TrafficStats;
import org.json.*;

final class NetworkObservationChecks {
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        NetworkStatsManager manager=context.getSystemService(NetworkStatsManager.class);
        check(checks,"network-stats-manager-stable",manager!=null&&manager==context.getSystemService(Context.NETWORK_STATS_SERVICE));
        int uid=android.os.Process.myUid();long rx=TrafficStats.getUidRxBytes(uid),tx=TrafficStats.getUidTxBytes(uid);
        long[] counters={rx,tx,TrafficStats.getUidRxPackets(uid),TrafficStats.getUidTxPackets(uid),TrafficStats.getMobileRxBytes(),TrafficStats.getMobileTxBytes(),TrafficStats.getMobileRxPackets(),TrafficStats.getMobileTxPackets(),TrafficStats.getTotalRxBytes(),TrafficStats.getTotalTxBytes(),TrafficStats.getTotalRxPackets(),TrafficStats.getTotalTxPackets(),TrafficStats.getRxBytes("lo"),TrafficStats.getTxBytes("lo"),TrafficStats.getRxPackets("lo"),TrafficStats.getTxPackets("lo")};
        for(long counter:counters)check(checks,"traffic-counter-preserves-real-value-or-unavailable",counter>=0||counter==TrafficStats.UNSUPPORTED);
        if(!baseline){
            boolean historical=false,foreign=false,mutation=false;
            try(var ignored=manager.querySummary(1,null,0,System.currentTimeMillis())){}catch(SecurityException expected){historical=expected.getMessage().contains("NETWORK_HISTORY_CAPABILITY_NOT_GRANTED");}
            try{TrafficStats.getUidRxBytes(0);}catch(SecurityException expected){foreign=expected.getMessage().contains("TRAFFIC_PHYSICAL_UID_NOT_GRANTED");}
            try{TrafficStats.setThreadStatsUid(uid+1);}catch(SecurityException expected){mutation=expected.getMessage().contains("TRAFFIC_CAPABILITY_NOT_GRANTED");}
            check(checks,"network-history-explicitly-denied-without-fake-empty-result",historical);
            check(checks,"traffic-other-uid-and-attribution-mutation-denied",foreign&&mutation);
        }
        return new JSONObject().put("physicalUid",uid).put("sharedExecutionUid",!baseline).put("isolatedGuestAccounting",false).put("rxBytes",rx).put("txBytes",tx).put("observations",new JSONArray(counters));
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private NetworkObservationChecks(){}
}
