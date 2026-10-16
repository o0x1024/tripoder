package dev.tripoder.provideridentity;

import android.app.*;

/** Exercise ActivityManager while Application.onCreate has not returned to the authority. */
public class MetadataApplication extends Application {
    static boolean observedSelfDuringCreate;
    static boolean observedStartupDuringCreate;
    @Override public void onCreate(){super.onCreate();
        var processes=getSystemService(ActivityManager.class).getRunningAppProcesses();
        if(processes==null)throw new IllegalStateException("APPLICATION_PROCESS_OBSERVATION_MISSING");
        for(var process:processes)if(process.pid==android.os.Process.myPid()&&process.uid==android.os.Process.myUid()&&getProcessName().equals(process.processName))observedSelfDuringCreate=true;
        if(!observedSelfDuringCreate)throw new IllegalStateException("APPLICATION_PROCESS_OBSERVATION_MISMATCH");
        for(var start:getSystemService(ActivityManager.class).getHistoricalProcessStartReasons(0)){
            Long launch=start.getStartupTimestamps().get(ApplicationStartInfo.START_TIMESTAMP_LAUNCH),create=start.getStartupTimestamps().get(ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE);
            if(start.getPid()==android.os.Process.myPid()&&start.getRealUid()==android.os.Process.myUid()&&getProcessName().equals(start.getProcessName())&&launch!=null&&create!=null&&create>=launch)observedStartupDuringCreate=true;
        }
        if(getProcessName().equals(getPackageName()+":historyfail"))throw new IllegalStateException("OWNED_APPLICATION_START_FAILURE");
    }
}
