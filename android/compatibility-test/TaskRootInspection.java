package dev.tripoder.hooklab;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;

/** Read-only structural evidence for one self-owned retained task, before and after explicit stop. */
final class TaskRootInspection {
    static JSONObject inspect(Context host,String space,String pkg,int task)throws Exception{
        if(!host.getPackageName().startsWith("dev.tripoder.compat")||!space.equals("paired-dev-tripoder-isolatedservicecontract")||!pkg.equals("dev.tripoder.isolatedservicecontract")||task<=0)throw new SecurityException("OWNED_TASK_ROOT_REQUIRED");
        JSONArray rows=new JSONArray(),platform=new JSONArray();
        try(var db=SQLiteDatabase.openDatabase(host.getDatabasePath("tripoder-runtime-v2.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);
            var cursor=db.rawQuery("SELECT t.ticket,t.state,t.generation,t.instance,t.stub,t.component,t.componentLease,t.resourceProof,s.state AS sessionState,l.id AS delivery,l.kind,l.state AS launchState,l.receiver,l.instance AS launchInstance,l.generation AS launchGeneration,l.callerKind,l.sourceTicket,l.sourceInstance,l.callerComponent,l.expectsResult,l.operation,o.caller AS operationCaller,o.state AS operationState,o.result AS operationResult,o.detail AS operationDetail,o.updated AS operationUpdated,ac.stub AS leasedStub,ac.component AS leasedComponent,ac.profile FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation LEFT JOIN activity_launches l ON l.candidate=t.ticket LEFT JOIN operations o ON o.id=l.operation LEFT JOIN activity_components ac ON ac.lease=t.componentLease WHERE t.space=? AND t.package=? AND t.taskId=?",new String[]{space,pkg,Integer.toString(task)})){
            while(cursor.moveToNext()){
                if(rows.length()>=32)throw new SecurityException("OWNED_TASK_ROOT_CAPACITY");JSONObject row=new JSONObject();for(String column:cursor.getColumnNames()){int n=cursor.getColumnIndexOrThrow(column);row.put(column,cursor.isNull(n)?JSONObject.NULL:cursor.getString(n));}rows.put(row);
            }
        }
        for(var owned:host.getSystemService(android.app.ActivityManager.class).getAppTasks()){
            var info=owned.getTaskInfo();if(info.taskId!=task)continue;var intent=info.baseIntent;if(intent==null||intent.getComponent()==null||!intent.getComponent().getPackageName().equals(host.getPackageName()))throw new SecurityException("OWNED_TASK_PLATFORM_ROOT_CHANGED");JSONObject extras=new JSONObject();for(String key:new String[]{"tripoder.ticket","tripoder.component","tripoder.delivery","tripoder.generation"})extras.put(key,intent.hasExtra(key));
            platform.put(new JSONObject().put("taskId",task).put("numActivities",info.numActivities).put("root",intent.getComponent().flattenToString()).put("base",info.baseActivity==null?JSONObject.NULL:info.baseActivity.flattenToString()).put("top",info.topActivity==null?JSONObject.NULL:info.topActivity.flattenToString()).put("tripoderExtrasPresent",extras));
        }
        return new JSONObject().put("readOnly",true).put("taskId",task).put("rows",rows).put("platform",platform).put("installation",JobMutationInspection.installation(host,space,pkg)).put("accountDataRead",false).put("dataCleared",false);
    }
    private TaskRootInspection(){}
}
