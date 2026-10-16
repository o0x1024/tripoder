package dev.tripoder.runtime;

import android.app.usage.NetworkStatsManager;

/** Historical queries are not granted; live physical UID/device observations are separate. */
final class RuntimeNetworkStats {
    private volatile NetworkStatsManager facade;
    synchronized NetworkStatsManager facade(){if(facade==null){facade=RuntimeBridge.createNetworkStatsManager();if(facade==null)throw new IllegalStateException("NETWORK_STATS_FACADE_REQUIRED");}return facade;}
    boolean owns(NetworkStatsManager value){return value!=null&&value==facade;}
    static void uid(int uid){if(uid!=android.os.Process.myUid())throw new SecurityException("TRAFFIC_PHYSICAL_UID_NOT_GRANTED:"+uid);}
}
