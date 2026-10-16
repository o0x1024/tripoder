package android.os;
/** Owned host stub: only the boundary's precision and validation ordering are exercised. */
public final class BatteryManager {
    public long getLongProperty(int id){return dev.tripoder.runtime.RuntimeBridge.batteryValue;}
    public int getIntProperty(int id){return 0;}
    public long computeChargeTimeRemaining(){return dev.tripoder.runtime.RuntimeBridge.batteryValue;}
    public boolean isCharging(){return true;}
    public void deniedControl(){}
}
