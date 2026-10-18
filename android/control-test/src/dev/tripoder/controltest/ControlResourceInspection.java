package dev.tripoder.controltest;

import android.os.*;
import java.lang.reflect.Field;
import java.util.*;
import org.json.*;

/** Production resource inspection and authenticated empty-task reclamation. */
public final class ControlResourceInspection extends ControlTestBase {
    @Override void test()throws Exception{
        if("true".equals(args.getString("inspectionOnly"))){inspectPending();return;}
        startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();Object engine=field(app,"engine");Bundle resource=execute((IBinder)field(engine,"session"),"resources");report.put("resources",json(resource));
        Bundle status=execute((IBinder)field(engine,"session"),"status");report.put("workerUid",status.getInt("engineUid"));Bundle reclaimed=execute((IBinder)field(engine,"session"),"reclaimEmptyTasks");report.put("afterReclaim",json(reclaimed));check("resource-capacity-is-accounted",resource.getInt("allocated")+resource.getInt("retained")+resource.getInt("available")==resource.getInt("total"),json(resource));
        JSONArray changes=new JSONArray(reclaimed.getString("reclaimed")),before=new JSONArray(resource.getString("tasks")),after=new JSONArray(reclaimed.getString("tasks"));
        for(int i=0;i<changes.length();i++){
            JSONObject change=changes.getJSONObject(i);if(!"TASK_RESOURCES_RELEASED".equals(change.getString("state")))continue;
            int task=change.getInt("taskId");boolean empty=false,absent=true;
            for(int j=0;j<before.length();j++){JSONObject prior=before.getJSONObject(j);if(task==prior.getInt("taskId"))empty=prior.getInt("numActivities")==0&&prior.getString("baseActivity").isEmpty()&&prior.getString("topActivity").isEmpty();}
            for(int j=0;j<after.length();j++)if(task==after.getJSONObject(j).getInt("taskId"))absent=false;
            if(change.has("rootTicket"))check("reclaimed-task-was-observed-empty-"+task,empty,change);
            check("reclaimed-task-is-absent-from-android-"+task,absent,change);
        }
        check("unconfirmed-ticket-inventory-is-complete",!resource.getBoolean("unresolvedTruncated")&&!reclaimed.getBoolean("unresolvedTruncated"),true);
        java.util.Set<String> retired=new java.util.HashSet<>();
        for(int i=0;i<changes.length();i++){JSONArray ids=changes.getJSONObject(i).optJSONArray("tickets");if(ids!=null)for(int j=0;j<ids.length();j++)retired.add(ids.getString(j));}
        JSONArray oldUnknown=new JSONArray(resource.getString("unresolvedActivities")),newUnknown=new JSONArray(reclaimed.getString("unresolvedActivities"));
        java.util.Map<String,String> retained=new java.util.HashMap<>();for(int i=0;i<newUnknown.length();i++){JSONObject row=newUnknown.getJSONObject(i);retained.put(row.getString("ticket"),row.toString());}
        int preserved=0;for(int i=0;i<oldUnknown.length();i++){JSONObject row=oldUnknown.getJSONObject(i);String ticket=row.getString("ticket");if(!retired.contains(ticket)){if(!row.toString().equals(retained.get(ticket)))throw new IllegalStateException("UNRELATED_UNCONFIRMED_TICKET_CHANGED:"+ticket);preserved++;}}
        check("unrelated-unconfirmed-activity-tickets-retained",true,preserved);
    }
    private void inspectPending()throws Exception{
        startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();IBinder session=(IBinder)field(field(app,"engine"),"session");
        report.put("scope","read-only-existing-pending-launch").put("workerUid",execute(session,"status").getInt("engineUid"));
        android.os.PowerManager power=getTargetContext().getSystemService(android.os.PowerManager.class);
        report.put("sustainedPerformanceSupported",power.isSustainedPerformanceModeSupported()).put("powerSaveMode",power.isPowerSaveMode()).put("thermalStatus",power.getCurrentThermalStatus());
        List<String> journal=pendingRecords();String pending=journal.isEmpty()?"":journal.get(0);report.put("pendingOperation",pending).put("pendingOperations",new JSONArray(journal));
        if(!pending.isEmpty()){
            report.put("pendingReceipt",json(execute(session,"query","operation",pending)));
            Bundle preparation=null;
            try{preparation=execute(session,"launchRecovery","operation",pending);report.put("preparationRecovery",json(preparation));}
            catch(RuntimeException rejected){report.put("preparationRecoveryRejection",String.valueOf(rejected.getMessage()));}
            if(preparation!=null){
                check("preparation-proof-preserves-unknown-and-retires-no-resources","UNKNOWN".equals(preparation.getString("state"))&&preparation.getBoolean("entryInert")&&preparation.getBoolean("canAcknowledge")&&!preparation.getBoolean("entryIssued")&&!preparation.getBoolean("resourcesRetired"),json(preparation));
                check("original-operation-still-unknown-after-inspection","UNKNOWN".equals(execute(session,"query","operation",pending).getString("state")),pending);
            }
            JSONArray projects=new JSONArray(execute(session,"catalog").getString("projects")),matches=new JSONArray(),rejections=new JSONArray();
            for(int i=0;i<projects.length();i++){
                JSONObject target=projects.getJSONObject(i);String project=target.getString("project");
                try{matches.put(json(execute(session,"launchRecovery","project",project,"operation",pending)));}
                catch(RuntimeException rejected){rejections.put(new JSONObject().put("project",project).put("reason",String.valueOf(rejected.getMessage())));}
            }
            report.put("recoveryMatches",matches).put("recoveryRejections",rejections);
        }
        check("inspection-did-not-change-pending-journal",journal.equals(pendingRecords()),new JSONArray(journal));
    }
    /** The workbench journal keeps one "op-<id>" record per unconfirmed operation. */
    private List<String> pendingRecords(){List<String> ids=new ArrayList<>();for(String key:getTargetContext().getSharedPreferences("operations",android.content.Context.MODE_PRIVATE).getAll().keySet())if(key.startsWith("op-"))ids.add(key.substring(3));Collections.sort(ids);return ids;}
    private static Object field(Object target,String name)throws Exception{Field value=target.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(target);}
}
