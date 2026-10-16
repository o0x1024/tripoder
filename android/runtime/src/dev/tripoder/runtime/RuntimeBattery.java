package dev.tripoder.runtime;

import android.os.BatteryManager;
import java.util.Objects;

/** Device-wide, read-only battery observations. No serial number or charging control. */
final class RuntimeBattery {
    private final RuntimeEngine engine;
    private volatile BatteryManager facade;
    RuntimeBattery(RuntimeEngine engine){this.engine=engine;}
    synchronized BatteryManager facade(){
        if(facade==null){facade=RuntimeBridge.createBatteryManager();if(facade==null||facade==platform())throw new SecurityException("BATTERY_FACADE_IDENTITY_INVALID");}
        return facade;
    }
    boolean owns(BatteryManager value){return value!=null&&value==facade;}
    BatteryManager platform(){return Objects.requireNonNull(engine.host.getSystemService(BatteryManager.class),"PLATFORM_BATTERY_UNAVAILABLE");}
    int intProperty(int id){property(id);long value=platform().getLongProperty(id);return value==Long.MIN_VALUE&&engine.info.targetSdkVersion>=28?Integer.MIN_VALUE:(int)value;}
    static void property(int id){
        if(id<BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER||id>BatteryManager.BATTERY_PROPERTY_STATUS)throw new UnsupportedOperationException("BATTERY_PROPERTY_NOT_GRANTED:"+id);
    }
}
