package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.app.job.JobParameters;
import android.content.Context;
import android.net.Uri;
import android.os.*;
import java.util.Set;

/** Resolve immutable job credentials before constructing any guest Application or service. */
final class RuntimeJobBootstrap {
    static Bundle call(Context host,Bundle request,int pid,int uid)throws Exception{
        if(uid!=android.os.Process.myUid()||request==null||request.hasFileDescriptors()||!request.keySet().equals(Set.of("stub","version","jobId","peer")))throw new SecurityException("JOB_BOOTSTRAP_SHAPE");
        String stub=RuntimeWire.required(request,"stub"),version=RuntimeWire.required(request,"version");int index=RuntimeJobSlots.index(stub);
        boolean observed=false;var running=host.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if(running!=null)for(var process:running)if(process.pid==pid&&process.uid==uid&&process.processName.equals(host.getPackageName()+":slot"+index))observed=true;
        if(!observed||request.getBinder("peer")==null)throw new SecurityException("JOB_BOOTSTRAP_PROCESS_MISMATCH");
        RuntimeBroker broker=RuntimeAuthorityOwner.get(host);
        synchronized(broker){
            JobLedger.Version v;
            try{v=broker.jobs.get(version);}catch(SecurityException absent){return rejected("JOB_VERSION_UNKNOWN");}
            if(v.fenced||v.state.equals("REJECTED"))return rejected("JOB_VERSION_FENCED");
            var lease=broker.jobs.components.get(v.lease);
            if(!lease.stub.equals(stub)||v.jobId!=request.getInt("jobId"))return rejected("JOB_BOOTSTRAP_VERSION_MISMATCH");
            var install=broker.apks.get(lease.space,lease.pkg);if(!install.generation.equals(lease.install))return rejected("JOB_INSTALL_STALE");
            RuntimeBroker.Slot slot=broker.findProcess(lease.space,lease.pkg,lease.process);
            if(v.hookGeneration!=null&&(slot==null||!v.hookGeneration.equals(slot.allocation.session.processGeneration)))return rejected("JOB_HOOK_RECOVERY_REQUIRES_RESCHEDULE");
            if(slot==null)slot=broker.reserve(install,lease.process,lease.processStub);
            requireVersion(broker,v,lease,slot);
            Bundle bootstrap=broker.processes.claimComponent(slot,request.getBinder("peer"));bootstrap.putInt("authorityPid",android.os.Process.myPid());return bootstrap;
        }
    }
    static void requireVersion(RuntimeBroker broker,JobLedger.Version version,JobComponentLedger.Lease lease,RuntimeBroker.Slot slot){
        var identity=slot.allocation.session.identity;
        if(version.fenced||version.state.equals("REJECTED")||!lease.space.equals(identity.spaceId)||!lease.pkg.equals(identity.packageName)||!lease.install.equals(identity.installGeneration)||!lease.process.equals(identity.processName)||!lease.processStub.equals(slot.activityStub))throw new SecurityException("JOB_VERSION_OWNER_REJECTED");
        if(version.hookGeneration!=null&&!version.hookGeneration.equals(slot.allocation.session.processGeneration))throw new SecurityException("JOB_HOOK_RECOVERY_REQUIRES_RESCHEDULE");
    }
    static boolean load(RuntimeJobGateway gateway,JobParameters parameters)throws Exception{
        String version=parameters.getExtras().getString(RuntimeJobInfo.VERSION);
        if(version==null||!version.matches("[a-f0-9]{64}"))throw new SecurityException("JOB_ROUTE_CREDENTIAL_INVALID");
        var host=RuntimeApplication.host;if(host==null)throw new IllegalStateException("JOB_HOST_APPLICATION_MISSING");
        var control=RuntimeProcessControl.get(host);Bundle request=new Bundle();request.putString("stub",gateway.getClass().getName());request.putString("version",version);request.putInt("jobId",parameters.getJobId());request.putBinder("peer",control);
        android.util.Log.i("TripoderJobs","BOOTSTRAP_CALL");
        Bundle bootstrap=host.getContentResolver().call(Uri.parse("content://"+host.getPackageName()+".runtime.bootstrap"),"restoreJob",null,request);
        android.util.Log.i("TripoderJobs","BOOTSTRAP_RETURN");
        if(bootstrap==null)throw new IllegalStateException("JOB_BOOTSTRAP_RESPONSE_MISSING");
        if(bootstrap.containsKey("rejectedJob")){android.util.Log.i("TripoderJobs","REJECTED:"+version+":"+bootstrap.getString("rejectedJob"));if(RuntimeRecoveryGate.productEnabled())control.retireUnclaimed();return false;}
        if(bootstrap.getBoolean("awaitExisting"))RuntimeEngine.awaitConstructed();
        else{control.configureAbort(bootstrap.getString("bootstrap"));control.claim(bootstrap.getInt("authorityPid"));android.util.Log.i("TripoderJobs","ENGINE_CONSTRUCT");new RuntimeEngine(host,bootstrap);}
        return true;
    }
    private static Bundle rejected(String reason){Bundle out=new Bundle();out.putString("rejectedJob",reason);return out;}
    private RuntimeJobBootstrap(){}
}
