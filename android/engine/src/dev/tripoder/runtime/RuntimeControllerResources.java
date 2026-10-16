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
            if(info.baseIntent!=null){row.put("baseComponent",name(info.baseIntent.getComponent()));String operation=info.baseIntent.getStringExtra("operation");if(operation!=null){synchronized(broker){var entry=new ControllerLaunchLedger(broker).get(operation);if(entry!=null)row.put("project",entry.project).put("space",entry.space).put("package",entry.pkg).put("processGeneration",entry.process).put("launchState",entry.state).put("ticket",entry.ticket);}}}
            tasks.put(row);
        }
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
