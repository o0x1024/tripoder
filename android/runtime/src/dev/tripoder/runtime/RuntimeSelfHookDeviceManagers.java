package dev.tripoder.runtime;

import android.os.PowerManager;
import java.util.concurrent.Executor;

/** Battery and thermal device observations; callbacks remain owned by existing Runtime facades. */
final class RuntimeSelfHookDeviceManagers {
    static Object battery(String key,Object[] args) {
        switch(key) {
            case "isCharging()":{Boolean o=RuntimeSandboxPersona.batteryChargingOverride();return o!=null?o:RuntimeBridge.batteryPlatform().isCharging();}
            case "computeChargeTimeRemaining()":return RuntimeBridge.batteryPlatform().computeChargeTimeRemaining();
            case "getIntProperty(int)":{Integer level=RuntimeSandboxPersona.batteryLevelOverride();if(level!=null&&(Integer)args[0]==android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)return level;return RuntimeBridge.batteryIntProperty((Integer)args[0]);}
            case "getLongProperty(int)":RuntimeBridge.batteryProperty((Integer)args[0]);return RuntimeBridge.batteryPlatform().getLongProperty((Integer)args[0]);
            default:throw new UnsupportedOperationException("BATTERY_CAPABILITY_UNSUPPORTED:"+key);
        }
    }
    @SuppressWarnings("deprecation") static Object power(String key,Object[] args) {
        switch(key) {
            case "getCurrentThermalStatus()":return RuntimeBridge.powerPlatform().getCurrentThermalStatus();
            case "getThermalHeadroom(int)":return RuntimeBridge.powerPlatform().getThermalHeadroom((Integer)args[0]);
            case "getThermalHeadroomThresholds()":return RuntimeBridge.powerThresholds();
            case "getBatteryDischargePrediction()":return RuntimeBridge.powerPlatform().getBatteryDischargePrediction();
            case "isBatteryDischargePredictionPersonalized()":return RuntimeBridge.powerPlatform().isBatteryDischargePredictionPersonalized();
            case "isInteractive()":return RuntimeBridge.powerPlatform().isInteractive();
            case "isScreenOn()":return RuntimeBridge.powerPlatform().isScreenOn();
            case "isPowerSaveMode()":return RuntimeBridge.powerPlatform().isPowerSaveMode();
            case "getLocationPowerSaveMode()":return RuntimeBridge.powerPlatform().getLocationPowerSaveMode();
            case "isDeviceIdleMode()":return RuntimeBridge.powerPlatform().isDeviceIdleMode();
            case "isDeviceLightIdleMode()":return RuntimeBridge.powerPlatform().isDeviceLightIdleMode();
            case "isLowPowerStandbyEnabled()":return RuntimeBridge.powerPlatform().isLowPowerStandbyEnabled();
            case "isExemptFromLowPowerStandby()":return RuntimeBridge.powerPlatform().isExemptFromLowPowerStandby();
            case "isAllowedInLowPowerStandby(int)":return RuntimeBridge.powerPlatform().isAllowedInLowPowerStandby((Integer)args[0]);
            case "isAllowedInLowPowerStandby(java.lang.String)":return RuntimeBridge.powerPlatform().isAllowedInLowPowerStandby((String)args[0]);
            case "isRebootingUserspaceSupported()":return RuntimeBridge.powerPlatform().isRebootingUserspaceSupported();
            case "isSustainedPerformanceModeSupported()":return RuntimeBridge.powerPlatform().isSustainedPerformanceModeSupported();
            case "isWakeLockLevelSupported(int)":return RuntimeBridge.powerPlatform().isWakeLockLevelSupported((Integer)args[0]);
            case "newWakeLock(int,java.lang.String)":return RuntimeBridge.powerPlatform().newWakeLock((Integer)args[0],(String)args[1]);
            case "isIgnoringBatteryOptimizations(java.lang.String)":return RuntimeBridge.powerIgnoring((String)args[0]);
            case "addThermalStatusListener(android.os.PowerManager$OnThermalStatusChangedListener)":
                RuntimeBridge.powerStatusAdd(RuntimeBridge.powerDefaultExecutor(),(PowerManager.OnThermalStatusChangedListener)args[0]);return null;
            case "addThermalStatusListener(java.util.concurrent.Executor,android.os.PowerManager$OnThermalStatusChangedListener)":
                RuntimeBridge.powerStatusAdd((Executor)args[0],(PowerManager.OnThermalStatusChangedListener)args[1]);return null;
            case "removeThermalStatusListener(android.os.PowerManager$OnThermalStatusChangedListener)":RuntimeBridge.powerStatusRemove((PowerManager.OnThermalStatusChangedListener)args[0]);return null;
            case "addThermalHeadroomListener(android.os.PowerManager$OnThermalHeadroomChangedListener)":
                RuntimeBridge.powerHeadroomAdd(RuntimeBridge.powerDefaultExecutor(),(PowerManager.OnThermalHeadroomChangedListener)args[0]);return null;
            case "addThermalHeadroomListener(java.util.concurrent.Executor,android.os.PowerManager$OnThermalHeadroomChangedListener)":
                RuntimeBridge.powerHeadroomAdd((Executor)args[0],(PowerManager.OnThermalHeadroomChangedListener)args[1]);return null;
            case "removeThermalHeadroomListener(android.os.PowerManager$OnThermalHeadroomChangedListener)":RuntimeBridge.powerHeadroomRemove((PowerManager.OnThermalHeadroomChangedListener)args[0]);return null;
            default:throw new UnsupportedOperationException("POWER_CAPABILITY_UNSUPPORTED:"+key);
        }
    }
    private RuntimeSelfHookDeviceManagers(){}
}
