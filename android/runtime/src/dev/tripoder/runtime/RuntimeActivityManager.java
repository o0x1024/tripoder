package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.Context;
import java.util.List;

/** Explicit read capabilities on a separately constructed guest-owned framework facade. */
final class RuntimeActivityManager {
    private final RuntimeEngine engine;
    private volatile ActivityManager facade;
    RuntimeActivityManager(RuntimeEngine engine){this.engine=engine;}
    synchronized ActivityManager facade(){
        if(facade==null){facade=RuntimeBridge.createActivityManager(engine.host);if(facade==null||facade==platform())throw new SecurityException("ACTIVITY_MANAGER_FACADE_IDENTITY_INVALID");}
        return facade;
    }
    boolean owns(ActivityManager value){return value!=null&&value==facade;}
    private ActivityManager platform(){ActivityManager value=(ActivityManager)engine.host.getSystemService(Context.ACTIVITY_SERVICE);if(value==null)throw new IllegalStateException("PLATFORM_ACTIVITY_MANAGER_UNAVAILABLE");return value;}
    int memoryClass(boolean large){return large?platform().getLargeMemoryClass():platform().getMemoryClass();}
    boolean lowRam(){return platform().isLowRamDevice();}
    void memoryInfo(ActivityManager.MemoryInfo output){
        if(output==null)throw new IllegalArgumentException("MEMORY_INFO_REQUIRED");
        ActivityManager.MemoryInfo observed=new ActivityManager.MemoryInfo();platform().getMemoryInfo(observed);
        output.advertisedMem=observed.advertisedMem;output.availMem=observed.availMem;output.freeMem=observed.freeMem;
        output.lowMemory=observed.lowMemory;output.threshold=observed.threshold;output.totalMem=observed.totalMem;
    }
    List<ActivityManager.RunningAppProcessInfo> processes()throws Exception{
        var result=engine.brokerCall(RuntimeWire.PROCESS_VIEW,engine.auth()).getParcelableArrayList("processes",ActivityManager.RunningAppProcessInfo.class);
        if(result==null)throw new IllegalStateException("PROCESS_OBSERVATION_UNAVAILABLE");return result;
    }
    java.util.ArrayList<android.os.Bundle> history(int max)throws Exception{
        android.os.Bundle request=engine.auth();request.putInt("maxNum",max);
        var result=engine.brokerCall(RuntimeWire.APPLICATION_START_HISTORY,request).getParcelableArrayList("starts",android.os.Bundle.class);
        if(result==null)throw new IllegalStateException("APPLICATION_START_HISTORY_UNAVAILABLE");return result;
    }
}
