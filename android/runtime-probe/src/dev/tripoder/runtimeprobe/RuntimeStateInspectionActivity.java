package dev.tripoder.runtimeprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.util.UUID;
import org.json.*;
import dev.tripoder.runtime.ApkStore;

/** Test APK only: read the owned database without constructing an authority,
 * recovering a ledger or changing any guest/task state. */
public final class RuntimeStateInspectionActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);String supplied=getIntent().getStringExtra("runId");if(supplied==null){finish();return;}
        final String id;try{id=UUID.fromString(supplied).toString();}catch(IllegalArgumentException invalid){finish();return;}
        new Thread(()->inspect(id),"owned-state-inspection").start();
    }
    private void inspect(String id){
        JSONObject report=new JSONObject();
        try{
            if(!getPackageName().equals("dev.tripoder.runtimeprobe"))throw new SecurityException("OWNED_PROBE_ONLY");
            report.put("runId",id).put("hostApkSha256",ApkStore.digest(new File(getApplicationInfo().sourceDir))).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("readOnly",true);
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
                String[][] queries={
                    {"sessionCounts","SELECT state,COUNT(*) AS count FROM sessions GROUP BY state"},
                    {"sessions","SELECT generation,space,package,installGeneration,pid,state,processName,activityStub,closeRequested FROM sessions WHERE state NOT IN ('DEAD','FAILED') OR generation IN (SELECT generation FROM activity_components UNION SELECT generation FROM activity_tickets WHERE state NOT IN ('FINISHED','REVOKED','RETIRED') UNION SELECT generation FROM service_leases WHERE state!='RELEASED' UNION SELECT target FROM broadcast_deliveries WHERE state!='COMPLETED' AND resourceRetired=0 UNION SELECT source FROM provider_acquisitions WHERE state='OPEN') ORDER BY rowid"},
                    {"components","SELECT stub,space,package,processName,processStub,component,generation,lease,profile FROM activity_components"},
                    {"tickets","SELECT ticket,space,package,generation,processStub,stub,state,instance,taskId,resourceProof,componentLease,destroyedConfirmed FROM activity_tickets WHERE state NOT IN ('FINISHED','REVOKED','RETIRED') OR componentLease IN (SELECT lease FROM activity_components)"},
                    {"services","SELECT id,space,package,processStub,generation,stub,state,instance FROM service_leases WHERE state!='RELEASED'"},
                    {"broadcasts","SELECT ticket,target,state,resourceRetired FROM broadcast_deliveries WHERE state!='COMPLETED' AND resourceRetired=0"},
                    {"pendingIntents","SELECT version,identityKey,space,package,installGeneration,processName,processStub,receiver,requestCode,state,fenced,currentVersion,oneShotConsumed FROM pending_intents WHERE fenced=0"},
                    {"pendingMutations","SELECT m.*,o.state AS operationState,o.detail,o.result,o.created,o.updated,v.space,v.package,v.processStub,v.fenced,v.currentVersion FROM pending_mutations m LEFT JOIN operations o ON o.id=m.operation LEFT JOIN pending_intents v ON v.version=m.version WHERE m.closed=0 OR m.kind='TRANSPORT_RETIREMENT' OR m.installGeneration IN (SELECT generation FROM installs WHERE package='com.tencent.mm')"},
                    {"providers","SELECT source,lease,state FROM provider_acquisitions WHERE state='OPEN'"}
                };
                for(String[] query:queries){JSONArray rows=new JSONArray();try(var c=db.rawQuery(query[1]+" LIMIT 2001",null)){
                    while(c.moveToNext()){if(rows.length()==2000)throw new IllegalStateException("INSPECTION_ROW_LIMIT:"+query[0]);JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++)row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));rows.put(row);}
                }report.put(query[0],rows);}
            }
            JSONArray exits=new JSONArray();
            for(var exit:getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(getPackageName(),0,16)){
                JSONObject item=new JSONObject().put("pid",exit.getPid()).put("reason",exit.getReason()).put("timestamp",exit.getTimestamp()).put("process",exit.getProcessName());
                if(exit.getReason()==android.app.ApplicationExitInfo.REASON_ANR){try(var trace=exit.getTraceInputStream()){if(trace!=null){byte[] bytes=trace.readNBytes(1024*1024);String file="anr-"+id+"-"+exit.getPid()+".txt";ProbeReports.publish(new File(getExternalFilesDir(null),file),new String(bytes,java.nio.charset.StandardCharsets.UTF_8));item.put("trace",file);}}}
                exits.put(item);
            }report.put("exits",exits);
            ActivityManager manager=getSystemService(ActivityManager.class);JSONArray tasks=new JSONArray();
            for(var task:manager.getAppTasks()){var info=task.getTaskInfo();tasks.put(new JSONObject().put("taskId",info.taskId).put("base",String.valueOf(info.baseActivity)).put("top",String.valueOf(info.topActivity)).put("original",String.valueOf(info.origActivity)).put("intent",String.valueOf(info.baseIntent)).put("activities",info.numActivities));}
            report.put("tasks",tasks);JSONArray processes=new JSONArray();for(var process:manager.getRunningAppProcesses())if(process.uid==android.os.Process.myUid())processes.put(new JSONObject().put("pid",process.pid).put("name",process.processName));report.put("processes",processes).put("passed",true);
        }catch(Throwable error){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
        try{ProbeReports.publish(new File(getExternalFilesDir(null),"state-"+id+".json"),report.toString(2));}catch(Exception error){android.util.Log.e("TripoderState","INSPECTION_REPORT_FAILED",error);}
    }
}
