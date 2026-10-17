package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.Context;

/** Workbench and engine share one package and UID. Guests share that UID too, so the controller is
 *  identified by process: only the main (UI) process may open the control lease. This is not a
 *  security boundary against guests; it keeps guest code from reaching the lease by accident. */
final class ControllerTrust {
    /** Caller package recorded for workbench-initiated guest launches. */
    static final String PACKAGE="dev.tripoder.engine";
    static void require(Context host,int pid,int uid){
        if(pid<=0||uid!=android.os.Process.myUid())throw new SecurityException("CONTROL_CALLER_REJECTED");
        var processes=((ActivityManager)host.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
        if(processes!=null)for(var process:processes)if(process.pid==pid&&process.uid==uid&&host.getPackageName().equals(process.processName))return;
        throw new SecurityException("CONTROL_CALLER_PROCESS_REJECTED");
    }
    private ControllerTrust(){}
}
