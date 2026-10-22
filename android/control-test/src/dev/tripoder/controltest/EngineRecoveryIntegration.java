package dev.tripoder.controltest;

import android.content.*;
import android.os.*;
import dev.tripoder.control.*;
import java.io.File;
import org.json.*;

/** Production supervisor, actual Binder process deaths and SQLite reopen, isolated QA package only. */
public final class EngineRecoveryIntegration extends ControlTestBase {
    IBinder supervisor;String operation;
    Bundle recovery()throws Exception{return ControlWire.call(supervisor,RecoveryWire.STATUS,new Bundle());}
    Bundle waitReady(int minimumAttempts)throws Exception{
        long end=SystemClock.elapsedRealtime()+65000;Bundle value;
        do{value=recovery();if(value.getString("state").equals("READY")&&value.getBinder("entry")!=null&&value.getInt("totalAttempts")>=minimumAttempts)return value;
            if(value.getString("state").equals("OPEN")||value.getString("state").equals("BLOCKED"))throw new IllegalStateException("RECOVERY_UNEXPECTED:"+json(value));SystemClock.sleep(50);
        }while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("RECOVERY_READY_TIMEOUT:"+json(value));
    }
    IBinder fault()throws Exception{return bind(enginePackage(),"dev.tripoder.runtime.RecoveryFaultService");}
    int authorityPid()throws Exception{return execute(fault(),"identity").getInt("pid");}
    void dropFaultBindings(){for(Link link:links)if(link.bound){getTargetContext().unbindService(link);link.bound=false;}}
    void killAuthority(int pid)throws Exception{android.os.Process.killProcess(pid);SystemClock.sleep(100);dropFaultBindings();}
    Bundle effect(IBinder fault,String id,String space,boolean crash)throws Exception{Bundle input=ControlWire.request("effect","operation",id,"space",space);input.putBoolean("crash",crash);return ControlWire.call(fault,ControlWire.EXECUTE,input);}
    @Override void test()throws Exception{
        if(enginePackage().equals("dev.tripoder.engine"))throw new IllegalStateException("SEPARATE_QA_PACKAGE_REQUIRED");
        startThroughWorkbench();supervisor=bind(enginePackage(),RecoveryWire.SERVICE);Bundle first=waitReady(1);
        check("initial-attempt-persisted",first.getInt("totalAttempts")==1&&first.getInt("automaticAttempts")==0,json(first));
        IBinder initialCommands=connect(first.getBinder("entry"),life()).getBinder("session");
        execute(initialCommands,"createSpace","operation",java.util.UUID.randomUUID().toString(),"space",run);
        new ControlInstallChecks(this,initialCommands).run(run);
        Bundle guest=execute(initialCommands,"prepareLaunch","operation",java.util.UUID.randomUUID().toString(),"project",report.getString("project"),"bindingOperation",report.getString("bindingOperation"),"generation",report.getString("installGeneration"),"component","dev.tripoder.p0.a.AActivity");
        check("guest-cold-start-without-automatic-prewarm",guest.getString("state").equals("RUNNING")&&guest.getString("mode").equals("thin"),json(guest));
        execute(initialCommands,"stopApplication","operation",java.util.UUID.randomUUID().toString(),"space",run,"package","dev.tripoder.p0.a","generation",report.getString("installGeneration"));
        execute(initialCommands,"disconnect");
        IBinder owned=fault();int originalPid=execute(owned,"identity").getInt("pid");operation="recovery-effect-"+run;
        try{effect(owned,operation,"recovery-qa",true);throw new IllegalStateException("crash unexpectedly replied");}catch(DeadObjectException expected){}
        dropFaultBindings(); // Remove Android's automatic recreation request from the fault fixture.
        supervisor=bind(enginePackage(),RecoveryWire.SERVICE);Bundle restored=waitReady(2);
        check("authority-recovered-once",restored.getInt("automaticAttempts")==1,json(restored));
        Bundle connected=connect(restored.getBinder("entry"),life());IBinder commands=connected.getBinder("session");
        check("incomplete-effect-stays-unknown",execute(commands,"query","operation",operation).getString("state").equals("UNKNOWN"),operation);
        owned=fault();int newPid=execute(owned,"identity").getInt("pid");check("real-authority-process-replaced",newPid!=originalPid,newPid);
        IBinder selected=owned;
        check("same-id-cannot-replay",rejected(()->effect(selected,operation,"recovery-qa",false),IllegalStateException.class,"ORIGINAL_OPERATION_QUERY_REQUIRED"),operation);
        check("new-id-conflicting-scope-rejected",rejected(()->effect(selected,operation+"-duplicate","recovery-qa",false),IllegalStateException.class,"RECOVERY_SCOPE_UNKNOWN"),operation);
        check("unrelated-scope-still-usable",effect(owned,operation+"-unrelated","another-space",false).getInt("count")==1,"another-space");
        File counter=new File(getTargetContext().getExternalFilesDir(null),"recovery-effect-"+operation);
        check("uncertain-effect-dispatched-exactly-once",java.nio.file.Files.readString(counter.toPath()).equals("1"),java.nio.file.Files.readString(counter.toPath()));
        execute(commands,"disconnect");dropFaultBindings();supervisor=bind(enginePackage(),RecoveryWire.SERVICE);
        for(int attempt=2;attempt<=3;attempt++){
            int pid=authorityPid();killAuthority(pid);supervisor=bind(enginePackage(),RecoveryWire.SERVICE);Bundle ready=waitReady(attempt+1);
            check("automatic-budget-"+attempt,ready.getInt("automaticAttempts")==attempt,json(ready));
        }
        killAuthority(authorityPid());supervisor=bind(enginePackage(),RecoveryWire.SERVICE);long end=SystemClock.elapsedRealtime()+5000;Bundle opened;
        do{opened=recovery();if(opened.getString("state").equals("OPEN"))break;SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<end);
        check("fourth-death-opens-circuit",opened.getString("state").equals("OPEN")&&opened.getInt("totalAttempts")==4,json(opened));
        long quietEnd=SystemClock.elapsedRealtime()+35000;int observed=0;
        while(SystemClock.elapsedRealtime()<quietEnd){Bundle quiet=recovery();if(!quiet.getString("state").equals("OPEN")||quiet.getInt("totalAttempts")!=4)throw new IllegalStateException("CIRCUIT_RESTARTED");observed++;SystemClock.sleep(500);}
        check("no-restarts-past-longest-backoff",observed>=60,json(recovery()));
        IBinder cold=engine();IBinder coldCommands=connect(cold,life()).getBinder("session");
        check("system-bind-cannot-bypass-circuit",rejected(()->execute(coldCommands,"status"),IllegalStateException.class,"RECOVERY_SUPERVISOR_UNAVAILABLE"),json(recovery()));
        execute(coldCommands,"disconnect");dropFaultBindings();supervisor=bind(enginePackage(),RecoveryWire.SERVICE);
        Bundle persisted=recovery();int supervisorPid=persisted.getInt("supervisorPid");android.os.Process.killProcess(supervisorPid);SystemClock.sleep(100);dropFaultBindings();
        supervisor=bind(enginePackage(),RecoveryWire.SERVICE);Bundle reopened=recovery();
        check("budget-survives-supervisor-process-death",reopened.getString("state").equals("OPEN")&&reopened.getInt("automaticAttempts")==3&&reopened.getInt("totalAttempts")==4,json(reopened));
        ControlWire.call(supervisor,RecoveryWire.ACTIVATE,new Bundle());check("ordinary-connect-does-not-reset-circuit",recovery().getString("state").equals("OPEN"),json(recovery()));
        ControlWire.call(supervisor,RecoveryWire.RETRY_ONCE,new Bundle());Bundle manual=waitReady(5);
        check("explicit-retry-preserves-automatic-budget",manual.getInt("automaticAttempts")==3,json(manual));
        killAuthority(authorityPid());supervisor=bind(enginePackage(),RecoveryWire.SERVICE);SystemClock.sleep(1000);
        check("manual-retry-does-not-chain",recovery().getString("state").equals("OPEN")&&recovery().getInt("totalAttempts")==5,json(recovery()));
        ControlWire.call(supervisor,RecoveryWire.STOP,new Bundle());check("explicit-stop-persisted",recovery().getString("state").equals("STOPPED"),json(recovery()));
        shell("am start -n "+enginePackage()+"/dev.tripoder.hooklab.WorkbenchActivity");SystemClock.sleep(500);
        check("ordinary-page-reopen-keeps-user-stop",recovery().getString("state").equals("STOPPED")&&recovery().getInt("totalAttempts")==5,json(recovery()));
        report.put("scope","real-device-engine-crash-recovery").put("initialFault","SIGABRT-after-fsynced-effect-before-terminal-receipt").put("effectDispatchCount",1).put("originalOperation",operation).put("finalRecovery",json(recovery()));
    }
}
