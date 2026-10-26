package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.Context;

/** Workbench and engine share one package and UID. Guests share that UID too, so the controller is
 *  identified by process: only the main (UI) process may open the control lease. This is not a
 *  security boundary against guests; it keeps guest code from reaching the lease by accident. */
final class ControllerTrust {
    /** The workbench starts guests as an outside caller would: only exported Activities, recorded with this caller package. A product rule, not trust. */
    static String workbenchCaller(Context host){return host.getPackageName();}
    /** Operation owner for everything the workbench submits; agent sessions own their operations under their own subject. */
    static final String OPERATIONS=RuntimeControllerOperationIdentity.CALLER;
    static void require(Context host,int pid,int uid){
        if(pid<=0||uid!=android.os.Process.myUid())throw new SecurityException("CONTROL_CALLER_REJECTED");
        var processes=((ActivityManager)host.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
        if(processes!=null)for(var process:processes)if(process.pid==pid&&process.uid==uid&&host.getPackageName().equals(process.processName))return;
        throw new SecurityException("CONTROL_CALLER_PROCESS_REJECTED");
    }
    private ControllerTrust(){}
}
