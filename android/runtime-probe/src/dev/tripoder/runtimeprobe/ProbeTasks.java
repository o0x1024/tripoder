package dev.tripoder.runtimeprobe;

import android.app.*;
import org.json.JSONArray;
import java.util.concurrent.*;

/** Explicit fixture cleanup: removes only this acceptance APK's previous Android tasks. */
final class ProbeTasks {
    static JSONArray dismissCompleted(Activity owner,int...ids)throws Exception{
        FutureTask<JSONArray> task=new FutureTask<>(()->{
            java.util.Set<Integer> requested=new java.util.HashSet<>();for(int id:ids){if(id<0||id==owner.getTaskId())throw new SecurityException("PROBE_TASK_SCOPE_INVALID");requested.add(id);}
            JSONArray removed=new JSONArray();var manager=(ActivityManager)owner.getSystemService(Activity.ACTIVITY_SERVICE);
            for(var item:manager.getAppTasks()){
                var info=item.getTaskInfo();if(!requested.contains(info.taskId))continue;
                var component=info.baseIntent==null?null:info.baseIntent.getComponent();
                if(component==null||!owner.getPackageName().equals(component.getPackageName())||!component.getClassName().startsWith("dev.tripoder.runtime.generated.")||info.baseActivity!=null||info.topActivity!=null)throw new SecurityException("PROBE_TASK_NOT_COMPLETED");
                item.finishAndRemoveTask();removed.put(info.taskId);
            }
            for(var item:manager.getAppTasks())if(requested.contains(item.getTaskInfo().taskId))throw new IllegalStateException("PROBE_TASK_DISMISS_UNCONFIRMED");return removed;
        });owner.runOnUiThread(task);return task.get(5,TimeUnit.SECONDS);
    }
    static JSONArray prepare(Activity owner)throws Exception{
        FutureTask<JSONArray> task=new FutureTask<>(()->{
            JSONArray removed=new JSONArray();var manager=(ActivityManager)owner.getSystemService(Activity.ACTIVITY_SERVICE);
            for(var previous:manager.getAppTasks())if(previous.getTaskInfo().taskId!=owner.getTaskId()){removed.put(previous.getTaskInfo().taskId);previous.finishAndRemoveTask();}
            for(var remaining:manager.getAppTasks())if(remaining.getTaskInfo().taskId!=owner.getTaskId())throw new IllegalStateException("OWNED_PROBE_TASK_REMOVAL_UNCONFIRMED");return removed;
        });owner.runOnUiThread(task);return task.get(5,TimeUnit.SECONDS);
    }
}
