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
        if(facade==null){
            RuntimeSelfHookManagers.ensureInstalled(RuntimeSelfHookManagerPolicy.Family.ACTIVITY_MANAGER);
            facade=RuntimeBridge.createActivityManager(engine.host);
            if(facade==null||facade==platform())throw new SecurityException("ACTIVITY_MANAGER_FACADE_IDENTITY_INVALID");
        }
        return facade;
    }
    boolean owns(ActivityManager value){return value!=null&&value==facade;}
    ActivityManager platform(){ActivityManager value=(ActivityManager)engine.host.getSystemService(Context.ACTIVITY_SERVICE);if(value==null)throw new IllegalStateException("PLATFORM_ACTIVITY_MANAGER_UNAVAILABLE");return value;}
    int memoryClass(boolean large){try{return large?platform().getLargeMemoryClass():platform().getMemoryClass();}catch(RuntimeException error){return RuntimeFacadeContract.nothing("ACTIVITY_MANAGER_UNAVAILABLE",error,(int)(Runtime.getRuntime().maxMemory()/(1024*1024)));}}
    boolean lowRam(){try{return platform().isLowRamDevice();}catch(RuntimeException error){return RuntimeFacadeContract.nothing("ACTIVITY_MANAGER_UNAVAILABLE",error,false);}}
    void memoryInfo(ActivityManager.MemoryInfo output){
        if(output==null)throw new IllegalArgumentException("MEMORY_INFO_REQUIRED");
        ActivityManager.MemoryInfo observed=new ActivityManager.MemoryInfo();
        try{platform().getMemoryInfo(observed);}catch(RuntimeException error){RuntimeFacadeContract.drop("MEMORY_INFO_UNAVAILABLE",error);return;}
        output.advertisedMem=observed.advertisedMem;output.availMem=observed.availMem;output.freeMem=observed.freeMem;
        output.lowMemory=observed.lowMemory;output.threshold=observed.threshold;output.totalMem=observed.totalMem;
    }
    List<ActivityManager.RunningAppProcessInfo> processes()throws Exception{
        var result=engine.brokerCall(RuntimeWire.PROCESS_VIEW,engine.auth()).getParcelableArrayList("processes",ActivityManager.RunningAppProcessInfo.class);
        if(result==null)return RuntimeFacadeContract.nothing("PROCESS_OBSERVATION_UNAVAILABLE",null,java.util.Collections.emptyList());return result;
    }
    android.os.Debug.MemoryInfo[] processMemory(int[] requested)throws Exception{
        if(requested==null)throw new IllegalArgumentException("PROCESS_MEMORY_PIDS_REQUIRED");
        int[] pids=requested.clone();if(pids.length>32)return RuntimeFacadeContract.nothing("PROCESS_MEMORY_PID_BUDGET",null,new android.os.Debug.MemoryInfo[0]);
        var before=processes();
        for(int pid:pids)if(pid<=0||before.stream().noneMatch(p->p.pid==pid))return RuntimeFacadeContract.nothing("PROCESS_MEMORY_PID_OUTSIDE_INSTALL",null,new android.os.Debug.MemoryInfo[0]);
        android.os.Debug.MemoryInfo[] observed=platform().getProcessMemoryInfo(pids);
        if(observed==null||observed.length!=pids.length||java.util.Arrays.stream(observed).anyMatch(java.util.Objects::isNull))return RuntimeFacadeContract.nothing("PROCESS_MEMORY_OBSERVATION_UNAVAILABLE",null,new android.os.Debug.MemoryInfo[0]);
        var after=processes();
        for(int pid:pids){var prior=before.stream().filter(p->p.pid==pid).findFirst().orElseThrow();
            if(after.stream().noneMatch(p->p.pid==pid&&p.uid==prior.uid&&p.processName.equals(prior.processName)))return RuntimeFacadeContract.nothing("PROCESS_MEMORY_IDENTITY_CHANGED",null,new android.os.Debug.MemoryInfo[0]);}
        return observed;
    }
    java.util.ArrayList<android.os.Bundle> history(int max)throws Exception{
        android.os.Bundle request=engine.auth();request.putInt("maxNum",max);
        var result=engine.brokerCall(RuntimeWire.APPLICATION_START_HISTORY,request).getParcelableArrayList("starts",android.os.Bundle.class);
        if(result==null)return RuntimeFacadeContract.nothing("APPLICATION_START_HISTORY_UNAVAILABLE",null,new java.util.ArrayList<android.os.Bundle>());return result;
    }
    List<android.app.ApplicationExitInfo> exits(String pkg,int pid,int max)throws Exception{
        android.os.Bundle request=engine.auth();request.putString("package",pkg);request.putInt("pid",pid);request.putInt("maxNum",max);
        var rows=engine.brokerCall(RuntimeWire.EXIT_HISTORY,request).getParcelableArrayList("exits",android.os.Bundle.class);
        if(rows==null)return RuntimeFacadeContract.nothing("EXIT_HISTORY_RESPONSE_REQUIRED",null,new java.util.ArrayList<android.app.ApplicationExitInfo>());
        List<android.app.ApplicationExitInfo> result=new java.util.ArrayList<>();for(var row:rows)result.add(dev.tripoder.selfhook.ExitInfoFrameworkAccess.view(row.getParcelable("info",android.app.ApplicationExitInfo.class),RuntimeWire.required(row,"processName")));return result;
    }
}
