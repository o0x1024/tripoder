package dev.tripoder.runtimeprobe;

import android.app.ActivityManager;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.util.HashSet;
import org.json.*;

/** Read-only target diagnostics. Does not recover ledgers or call a guest Binder. */
final class TargetStartupObservation {
    static JSONObject read(Context context,String space,String pkg,String generation)throws Exception{
        JSONObject result=new JSONObject().put("time",System.currentTimeMillis()).put("generation",generation);
        HashSet<String> created=new HashSet<>();JSONArray activities=new JSONArray();
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("tripoder-runtime-v2.db").toString(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
            try(var c=db.rawQuery("SELECT state,pid FROM sessions WHERE generation=? AND space=? AND package=?",new String[]{generation,space,pkg})){
                if(!c.moveToFirst())throw new SecurityException("TARGET_SESSION_NOT_FOUND");result.put("state",c.getString(0)).put("pid",c.getInt(1));
            }
            try(var c=db.rawQuery("SELECT t.ticket,t.component,t.stub,t.state,t.taskId,l.state FROM activity_tickets t LEFT JOIN activity_launches l ON l.candidate=t.ticket WHERE t.generation=? AND t.space=? AND t.package=? LIMIT 129",new String[]{generation,space,pkg})){
                while(c.moveToNext()){
                    if(activities.length()==128)throw new IllegalStateException("TARGET_ACTIVITY_OBSERVATION_LIMIT");
                    activities.put(new JSONObject().put("ticket",c.getString(0)).put("component",c.getString(1)).put("stub",c.getString(2)).put("state",c.getString(3)).put("taskId",c.getInt(4)).put("delivery",c.getString(5)));
                    if("LIVE".equals(c.getString(3))&&"RECEIVED".equals(c.getString(5)))created.add(c.getInt(4)+":"+c.getString(2));
                }
            }
        }
        JSONArray tasks=new JSONArray();boolean retained=false;
        for(var task:context.getSystemService(ActivityManager.class).getAppTasks()){
            var info=task.getTaskInfo();if(info.topActivity==null||!context.getPackageName().equals(info.topActivity.getPackageName()))continue;
            if(created.contains(info.taskId+":"+info.topActivity.getClassName())){retained=true;tasks.put(new JSONObject().put("taskId",info.taskId).put("top",info.topActivity.flattenToString()).put("activities",info.numActivities));}
        }
        return result.put("activities",activities).put("tasks",tasks).put("createdActivityInTask",retained);
    }
    private TargetStartupObservation(){}
}
