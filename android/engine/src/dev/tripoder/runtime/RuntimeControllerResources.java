package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.os.Bundle;
import org.json.*;

/** Bounded resource facts for the trusted workbench, never an arbitrary database interface. */
final class RuntimeControllerResources {
    static Bundle inspect(RuntimeBroker broker)throws Exception{
        Bundle result=broker.processes.capacity();JSONArray tasks=new JSONArray();
        JSONArray slots=new JSONArray();for(Bundle row:result.<Bundle>getParcelableArrayList("slots")){JSONObject slot=new JSONObject();for(String key:row.keySet())slot.put(key,row.get(key));slots.put(slot);}result.putString("slots",slots.toString());
        var manager=broker.host.getSystemService(ActivityManager.class);var owned=manager.getAppTasks();
        if(owned==null||owned.size()>256)throw new IllegalStateException("RESOURCE_TASK_OBSERVATION_INCOMPLETE");
        for(var task:owned){var info=task.getTaskInfo();if(info==null)throw new IllegalStateException("RESOURCE_TASK_OBSERVATION_INCOMPLETE");
            JSONObject row=new JSONObject().put("taskId",info.taskId).put("numActivities",info.numActivities).put("baseActivity",name(info.baseActivity)).put("topActivity",name(info.topActivity)).put("origActivity",name(info.origActivity));
            if(info.baseIntent!=null){row.put("baseComponent",name(info.baseIntent.getComponent()));String ticket=info.baseIntent.getStringExtra("tripoder.ticket");if(ticket!=null){synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT t.space,t.package,t.generation,l.operation,l.state,l.callerKind FROM activity_tickets t JOIN activity_launches l ON l.candidate=t.ticket WHERE t.ticket=?",new String[]{ticket})){if(c.moveToFirst())row.put("space",c.getString(0)).put("package",c.getString(1)).put("processGeneration",c.getString(2)).put("launchOperation",c.getString(3)).put("launchState",c.getString(4)).put("callerKind",c.getString(5)).put("ticket",ticket);}}}}
            tasks.put(row);
        }
        // Bounded ownership facts explain retained process slots and admission
        // blockers without exposing job payloads or other guest file contents.
        java.util.Set<String> platformJobs=new java.util.HashSet<>();
        var scheduled=broker.host.getSystemService(android.app.job.JobScheduler.class).getPendingJobsInAllNamespaces();
        if(scheduled==null)throw new IllegalStateException("RESOURCE_JOB_OBSERVATION_INCOMPLETE");
        for(var namespace:scheduled.entrySet())for(var job:namespace.getValue())platformJobs.add(namespace.getKey()+"/"+job.getId()+"/"+job.getService().getClassName()+"/"+job.getExtras().getString(RuntimeJobInfo.VERSION));
        JSONArray jobs=new JSONArray(),unknown=new JSONArray();synchronized(broker){
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT jc.id,jc.space,jc.package,jc.installGeneration,jc.processName,jc.processStub,jc.stub,jc.retired,v.version,v.jobId,v.state,v.fenced,v.physicalNamespace FROM job_components jc LEFT JOIN job_versions v ON v.lease=jc.id ORDER BY jc.retired,jc.id,v.version LIMIT 129",null)){
                while(c.moveToNext())jobs.put(new JSONObject().put("lease",c.getString(0)).put("space",c.getString(1)).put("package",c.getString(2)).put("installGeneration",c.getString(3)).put("processName",c.getString(4)).put("processStub",c.getString(5)).put("stub",c.getString(6)).put("retired",c.getInt(7)==1).put("version",c.getString(8)).put("jobId",c.getInt(9)).put("versionState",c.getString(10)).put("fenced",c.getInt(11)==1).put("physicalNamespace",c.getString(12)).put("platformPending",platformJobs.contains(c.getString(12)+"/"+c.getInt(9)+"/"+c.getString(6)+"/"+c.getString(8))));
            }
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT o.id,o.caller,s.scope,s.command,s.dispatch,SUBSTR(o.detail,1,INSTR(o.detail||':',':')-1),o.created,o.updated FROM operations o LEFT JOIN operation_scopes s ON s.operation=o.id WHERE o.state='UNKNOWN' ORDER BY o.updated DESC,o.id LIMIT 129",null)){
                while(c.moveToNext())unknown.put(new JSONObject().put("operation",c.getString(0)).put("caller",c.getString(1)).put("scope",c.getString(2)).put("command",c.getString(3)).put("dispatch",c.getString(4)).put("reason",c.getString(5)).put("created",c.getLong(6)).put("updated",c.getLong(7)));
            }
        }
        RuntimeServiceResources.append(broker,result);
        boolean jobsTruncated=jobs.length()>128,unknownTruncated=unknown.length()>128;if(jobsTruncated)jobs.remove(128);if(unknownTruncated)unknown.remove(128);
        result.putString("jobReferences",jobs.toString());result.putBoolean("jobsTruncated",jobsTruncated);result.putString("unknownOperations",unknown.toString());result.putBoolean("unknownTruncated",unknownTruncated);
        JSONArray components=new JSONArray();synchronized(broker){
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT ac.processStub,ac.space,ac.package,ac.component,ac.generation,t.ticket,t.state,t.taskId,t.destroyedConfirmed,t.resourceProof,t.entryFenceOperation FROM activity_components ac LEFT JOIN activity_tickets t ON t.componentLease=ac.lease ORDER BY ac.processStub,t.ticket LIMIT 129",null)){
                while(c.moveToNext())components.put(new JSONObject().put("processStub",c.getString(0)).put("space",c.getString(1)).put("package",c.getString(2)).put("component",c.getString(3)).put("generation",c.getString(4)).put("ticket",c.getString(5)).put("state",c.getString(6)).put("taskId",c.getInt(7)).put("destroyedConfirmed",c.getInt(8)==1).put("resourceProof",c.getString(9)).put("entryFenceOperation",c.getString(10)));
            }
        }
        boolean truncated=components.length()>128;if(truncated)components.remove(128);result.putString("tasks",tasks.toString());result.putString("activityReferences",components.toString());result.putBoolean("referencesTruncated",truncated);
        JSONArray unresolved=new JSONArray();synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT t.ticket,t.processStub,t.space,t.package,t.state,t.taskId,t.generation,s.state,ac.lease FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation LEFT JOIN activity_components ac ON ac.lease=t.componentLease WHERE t.state NOT IN ('FINISHED','REVOKED','RETIRED') ORDER BY t.processStub LIMIT 129",null)){
            while(c.moveToNext())unresolved.put(new JSONObject().put("ticket",c.getString(0)).put("processStub",c.getString(1)).put("space",c.getString(2)).put("package",c.getString(3)).put("state",c.getString(4)).put("taskId",c.getInt(5)).put("generation",c.getString(6)).put("sessionState",c.getString(7)).put("componentLeasePresent",!c.isNull(8)));
        }}boolean unresolvedTruncated=unresolved.length()>128;if(unresolvedTruncated)unresolved.remove(128);result.putString("unresolvedActivities",unresolved.toString());result.putBoolean("unresolvedTruncated",unresolvedTruncated);return result;
    }
    private static String name(ComponentName name){return name==null?"":name.flattenToString();}
    private RuntimeControllerResources(){}
}
