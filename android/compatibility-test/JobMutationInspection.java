package dev.tripoder.hooklab;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;

/** Fixed, read-only QA evidence for one blocked mutation; no database mutation API. */
final class JobMutationInspection {
    static JSONObject activityReferences(Context host,String space,String pkg)throws Exception{
        JSONObject result=new JSONObject().put("readOnly",true).put("space",space).put("package",pkg);
        JSONArray components=new JSONArray(),tickets=new JSONArray(),tasks=new JSONArray();
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            try(var c=db.rawQuery("SELECT stub,component,installGeneration,generation,lease FROM activity_components WHERE space=? AND package=? ORDER BY stub",new String[]{space,pkg})){
                while(c.moveToNext())components.put(new JSONObject().put("stub",c.getString(0)).put("component",c.getString(1)).put("installGeneration",c.getString(2)).put("generation",c.getString(3)).put("lease",c.getString(4)));
            }
            try(var c=db.rawQuery("SELECT t.ticket,t.stub,t.component,t.generation,t.state,t.instance,t.taskId,t.destroyedConfirmed,t.resourceProof,s.state,l.operation,l.state,l.kind,l.callerKind FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation LEFT JOIN activity_launches l ON l.candidate=t.ticket WHERE t.space=? AND t.package=? AND EXISTS(SELECT 1 FROM activity_components ac WHERE ac.lease=t.componentLease) ORDER BY t.rowid LIMIT 4097",new String[]{space,pkg})){
                while(c.moveToNext()){
                    if(tickets.length()>=4096)throw new IllegalStateException("TASK_REFERENCE_QUERY_CAPACITY");
                    tickets.put(new JSONObject().put("ticket",c.getString(0)).put("stub",c.getString(1)).put("component",c.getString(2)).put("generation",c.getString(3)).put("state",c.getString(4)).put("instance",c.getString(5)).put("taskId",c.getInt(6)).put("destroyedConfirmed",c.getInt(7)!=0).put("resourceProof",c.getString(8)).put("sessionState",c.getString(9)).put("operation",c.getString(10)).put("launchState",c.getString(11)).put("kind",c.getString(12)).put("callerKind",c.getString(13)));
                }
            }
            for(var task:host.getSystemService(android.app.ActivityManager.class).getAppTasks()){
                var info=task.getTaskInfo();if(info.baseIntent==null)continue;String root=info.baseIntent.getStringExtra("tripoder.ticket");if(root==null)continue;
                try(var c=db.rawQuery("SELECT 1 FROM activity_tickets WHERE ticket=? AND space=? AND package=?",new String[]{root,space,pkg})){
                    if(c.moveToFirst())tasks.put(new JSONObject().put("taskId",info.taskId).put("numActivities",info.numActivities).put("base",info.baseActivity==null?JSONObject.NULL:info.baseActivity.flattenToShortString()).put("top",info.topActivity==null?JSONObject.NULL:info.topActivity.flattenToShortString()).put("rootTicket",root).put("rootComponent",info.baseIntent.getComponent()==null?JSONObject.NULL:info.baseIntent.getComponent().flattenToShortString()).put("rootDelivery",info.baseIntent.getStringExtra("tripoder.delivery")));
                }
            }
        }
        return result.put("components",components).put("tickets",tickets).put("tasks",tasks).put("installation",installation(host,space,pkg));
    }
    static JSONObject installation(Context host,String space,String pkg)throws Exception{
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY);
            var c=db.rawQuery("SELECT generation,dataId,state,reason,version,signer,sha256 FROM installs WHERE space=? AND package=?",new String[]{space,pkg})){
            if(!c.moveToFirst())throw new IllegalStateException("QA_INSTALLATION_MISSING");
            return new JSONObject().put("generation",c.getString(0)).put("dataId",c.getString(1)).put("state",c.getString(2)).put("reason",c.getString(3)).put("version",c.getLong(4)).put("signer",c.getString(5)).put("baseApkSha256",c.getString(6));
        }
    }
    static JSONObject componentRestarts(Context host,String space,String pkg)throws Exception{
        JSONObject result=new JSONObject().put("readOnly",true).put("space",space).put("package",pkg);JSONArray operations=new JSONArray();
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            try(var c=db.rawQuery("SELECT DISTINCT o.id,o.state,o.result,o.detail,o.evidence FROM sessions s JOIN operations o ON o.id=s.componentRestartOperation WHERE s.space=? AND s.package=? ORDER BY o.created LIMIT 513",new String[]{space,pkg})){
                while(c.moveToNext()){
                    if(operations.length()>=512)throw new IllegalStateException("RESTART_QUERY_CAPACITY");String operation=c.getString(0);JSONArray targets=new JSONArray();
                    try(var sessions=db.rawQuery("SELECT s.generation,s.installGeneration,s.processName,s.pid,s.state,s.closeRequested,EXISTS(SELECT 1 FROM recovery_paused p WHERE p.generation=s.generation) FROM sessions s WHERE s.componentRestartOperation=? ORDER BY s.generation",new String[]{operation})){
                        while(sessions.moveToNext())targets.put(new JSONObject().put("generation",sessions.getString(0)).put("installGeneration",sessions.getString(1)).put("processName",sessions.getString(2)).put("pid",sessions.getInt(3)).put("state",sessions.getString(4)).put("closeRequested",sessions.getInt(5)!=0).put("ordinaryRecoveryPaused",sessions.getInt(6)!=0));
                    }
                    operations.put(new JSONObject().put("operation",operation).put("state",c.getString(1)).put("result",c.getString(2)).put("detail",c.getString(3)).put("evidence",c.getString(4)).put("targets",targets));
                }
            }
        }
        return result.put("operations",operations);
    }
    static JSONObject broadcast(Context host,String operation)throws Exception{
        if(!operation.matches("system-receiver-[0-9a-f-]{36}"))throw new IllegalArgumentException("SYSTEM_RECEIVER_OPERATION_REQUIRED");
        JSONObject result=new JSONObject().put("operation",operation).put("readOnly",true);JSONArray deliveries=new JSONArray();String caller;
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            try(var c=db.rawQuery("SELECT o.state,o.caller,o.detail,o.evidence,s.scope,s.command,s.dispatch FROM operations o LEFT JOIN operation_scopes s ON s.operation=o.id WHERE o.id=?",new String[]{operation})){
                if(!c.moveToFirst())throw new IllegalArgumentException("SYSTEM_RECEIVER_OPERATION_MISSING");caller=c.getString(1);
                result.put("state",c.getString(0)).put("caller",caller).put("detail",c.getString(2)).put("evidence",c.getString(3)).put("scope",c.getString(4)).put("command",c.getString(5)).put("dispatch",c.getString(6));
            }
            try(var c=db.rawQuery("SELECT ticket,state,source,target,registration,instance,resourceRetired,resourceProof FROM broadcast_deliveries WHERE operation=?",new String[]{operation})){
                while(c.moveToNext())deliveries.put(new JSONObject().put("ticket",c.getString(0)).put("state",c.getString(1)).put("source",c.getString(2)).put("target",c.getString(3)).put("registration",c.getString(4)).put("instance",c.getString(5)).put("resourceRetired",c.getInt(6)!=0).put("resourceProof",c.getString(7)));
            }
            String[] parts=new String[5];int at=0;
            for(int i=0;i<5;i++){int colon=caller.indexOf(':',at),count=Integer.parseInt(caller.substring(at,colon));if(count<0||count>4096)throw new IllegalArgumentException("CALLER_FRAME_INVALID");at=colon+1;parts[i]=caller.substring(at,at+count);at+=count;}
            if(at!=caller.length())throw new IllegalArgumentException("CALLER_FRAME_TRAILING");
            try(var c=db.rawQuery("SELECT space,package,installGeneration,processName,state,authorityGeneration,closeRequested FROM sessions WHERE generation=?",new String[]{parts[4]})){
                if(c.moveToFirst())result.put("ownerSession",new JSONObject().put("generation",parts[4]).put("space",c.getString(0)).put("package",c.getString(1)).put("installGeneration",c.getString(2)).put("processName",c.getString(3)).put("state",c.getString(4)).put("authorityGeneration",c.getString(5)).put("closeRequested",c.getInt(6)!=0));
            }
            JSONObject registrations=new JSONObject();try(var c=db.rawQuery("SELECT state,COUNT(*) FROM broadcast_registrations WHERE generation=? GROUP BY state",new String[]{parts[4]})){while(c.moveToNext())registrations.put(c.getString(0),c.getInt(1));}result.put("registrationStates",registrations);
        }
        return result.put("deliveries",deliveries);
    }
    static JSONObject inspect(Context host,String operation)throws Exception{
        if(!operation.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("OWNED_OPERATION_ID_REQUIRED");
        JSONObject result=new JSONObject().put("operation",operation).put("readOnly",true);JSONArray targets=new JSONArray();
        var pending=host.getSystemService(android.app.job.JobScheduler.class).getPendingJobsInAllNamespaces();
        if(pending==null)throw new IllegalStateException("REAL_JOB_OBSERVATION_MISSING");
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY)){
            try(var c=db.rawQuery("SELECT m.kind,m.closed,m.installGeneration,o.state,o.result,o.detail,o.evidence FROM job_mutations m JOIN operations o ON o.id=m.operation WHERE m.operation=?",new String[]{operation})){
                if(!c.moveToFirst())throw new IllegalArgumentException("JOB_MUTATION_NOT_FOUND");
                result.put("kind",c.getString(0)).put("closed",c.getInt(1)!=0).put("installGeneration",c.getString(2)).put("operationState",c.getString(3)).put("result",c.getString(4)).put("detail",c.getString(5)).put("evidence",c.getString(6));
            }
            try(var c=db.rawQuery("SELECT v.version,v.lease,v.jobId,v.state,v.fenced,v.physicalNamespace,j.stub,j.retired,(SELECT COUNT(*) FROM job_attempts a WHERE a.version=v.version) FROM job_mutation_targets t JOIN job_versions v ON v.version=t.version JOIN job_components j ON j.id=v.lease WHERE t.operation=? ORDER BY v.rowid",new String[]{operation})){
                while(c.moveToNext()){
                    boolean present=false;var jobs=pending.get(c.getString(5));
                    if(jobs!=null)for(var job:jobs)if(job.getId()==c.getInt(2)&&job.getService().getClassName().equals(c.getString(6))&&c.getString(0).equals(job.getExtras().getString("dev.tripoder.job.version")))present=true;
                    targets.put(new JSONObject().put("version",c.getString(0)).put("lease",c.getString(1)).put("jobId",c.getInt(2)).put("state",c.getString(3)).put("fenced",c.getInt(4)!=0).put("physicalNamespace",c.getString(5)).put("stub",c.getString(6)).put("retired",c.getInt(7)!=0).put("callbackAttempts",c.getInt(8)).put("platformPending",present));
                }
            }
        }
        return result.put("targets",targets);
    }
    private JobMutationInspection(){}
}
