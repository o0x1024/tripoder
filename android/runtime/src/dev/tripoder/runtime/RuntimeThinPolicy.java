package dev.tripoder.runtime;

import android.content.Context;
import dev.tripoder.selfhook.FixedRuntimeHooks;
import java.util.Set;

/** Thin guests use Android's real device services under the physical host UID.
 * Component, package, resource and storage translation remains in the runtime. */
final class RuntimeThinPolicy {
    static boolean active(){return RuntimeEngine.current!=null&&RuntimeEngine.current.mode==RuntimeMode.THIN;}
    static boolean platformManager(RuntimeSelfHookManagerPolicy.Family family){
        return switch(family){
            case CONNECTIVITY,TELEPHONY,AUDIO,BATTERY,POWER,WIFI_P2P,NETWORK_STATS->true;
            default->false;
        };
    }
    private static final Set<String> SERVICES=Set.of(Context.CONNECTIVITY_SERVICE,Context.TELEPHONY_SERVICE,
        Context.AUDIO_SERVICE,Context.BATTERY_SERVICE,Context.POWER_SERVICE,Context.WIFI_SERVICE,Context.WIFI_P2P_SERVICE,Context.NETWORK_STATS_SERVICE);
    static boolean platformService(String name){return name!=null&&SERVICES.contains(name);}
    static boolean platformHooks(FixedRuntimeHooks.Group group){
        return group==FixedRuntimeHooks.Group.TRAFFIC||group==FixedRuntimeHooks.Group.WIFI_CHANNEL
            ||group==FixedRuntimeHooks.Group.UI||group==FixedRuntimeHooks.Group.FAILURES;
    }
    private RuntimeThinPolicy(){}
}
