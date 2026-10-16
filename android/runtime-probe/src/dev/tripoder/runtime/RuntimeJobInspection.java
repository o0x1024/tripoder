package dev.tripoder.runtime;

import android.app.job.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Probe-only, read-only observation. System queries execute outside the authority monitor. */
public final class RuntimeJobInspection {
    public static Bundle read(RuntimeBroker broker,Bundle input)throws Exception{
        if(Thread.holdsLock(broker))throw new IllegalStateException("JOB_INSPECTION_INSIDE_BROKER_LOCK");
        Bundle out=new Bundle();JobLedger.Version version=null;File data;int pid,uid;
        synchronized(broker){
            var session=broker.contract.getSession(RuntimeWire.required(input,"generation"));if(!session.identity.packageName.equals("dev.tripoder.jobsfixture"))throw new SecurityException("JOBS_FIXTURE_REQUIRED");
            var install=broker.apks.get(session.identity.spaceId,session.identity.packageName);data=install.data;pid=session.pid;uid=session.uid;
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT generation,pid FROM sessions WHERE space=? AND package=? AND processName=? AND state='RUNNING'",new String[]{session.identity.spaceId,session.identity.packageName,session.identity.packageName+":jobs"})){if(c.moveToFirst()){out.putString("jobGeneration",c.getString(0));out.putInt("jobPid",c.getInt(1));}}
            JSONArray events=new JSONArray();try(var c=broker.store.getReadableDatabase().rawQuery("SELECT a.attempt,a.generation,v.jobId,e.event,e.detail,e.created FROM job_attempt_events e JOIN job_attempts a ON a.attempt=e.attempt JOIN job_versions v ON v.version=a.version WHERE v.installGeneration=? ORDER BY e.id",new String[]{install.generation})){while(c.moveToNext()){
                events.put(new JSONObject().put("attempt",c.getString(0)).put("generation",c.getString(1)).put("jobId",c.getInt(2)).put("event",c.getString(3)).put("detail",c.getString(4)).put("at",c.getLong(5)));
                if(c.getInt(2)==44&&c.getString(3).equals("FINISH_RPC_RETURNED")&&c.getString(4).equals("true"))out.putBoolean("retryRpcReturned",true);
            }}out.putString("events",events.toString());
            for(var row:broker.jobs.versions(install.generation))if(row.jobId==44)version=row;
        }
        File report=new File(data,"files/jobs-contract.json");if(report.exists()){
            if(report.length()>32768)throw new SecurityException("JOBS_REPORT_TOO_LARGE");String content=Files.readString(report.toPath());JSONObject parsed=new JSONObject(content);if(parsed.getInt("pid")!=pid||parsed.getInt("uid")!=uid)throw new SecurityException("JOBS_REPORT_OWNER_MISMATCH");out.putString("result",content);
        }
        File ready=new File(data,"files/jobs-recovery-ready.json");if(ready.exists())out.putInt("recoveryPid",new JSONObject(Files.readString(ready.toPath())).getInt("pid"));
        if(version!=null){
            long began=SystemClock.elapsedRealtime();JobScheduler scheduler=broker.host.getSystemService(JobScheduler.class).forNamespace(version.physicalNamespace);
            JobInfo pending=scheduler.getPendingJob(version.jobId);int[] reasons=scheduler.getPendingJobReasons(version.jobId);
            boolean exact=pending!=null&&RuntimeJobBroker.matches(version,version.physicalNamespace,pending),executing=false,invalid=false;
            JSONArray why=new JSONArray();for(int reason:reasons){why.put(reason);executing|=reason==JobScheduler.PENDING_JOB_REASON_EXECUTING;invalid|=reason==JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID;}
            JSONObject system=new JSONObject().put("queryBeganElapsed",began).put("queryEndedElapsed",SystemClock.elapsedRealtime()).put("namespace",version.physicalNamespace).put("jobId",version.jobId).put("version",version.version).put("ledgerState",version.state).put("fenced",version.fenced).put("pending",pending!=null).put("exactVersionAndParcel",exact).put("reasons",why).put("retryRpcReturned",out.getBoolean("retryRpcReturned"));
            if(pending!=null)system.put("observedVersion",pending.getExtras().getString(RuntimeJobInfo.VERSION)).put("service",pending.getService().flattenToString()).put("minLatency",pending.getMinLatencyMillis()).put("maxDelay",pending.getMaxExecutionDelayMillis()).put("backoff",pending.getInitialBackoffMillis());
            out.putString("system",system.toString());out.putBoolean("pendingRetryConfirmed",out.getBoolean("retryRpcReturned")&&exact&&!executing&&!invalid);
        }return out;
    }
    private RuntimeJobInspection(){}
}
