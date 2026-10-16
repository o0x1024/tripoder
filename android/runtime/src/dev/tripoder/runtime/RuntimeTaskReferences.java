package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.*;
import java.util.*;

/** A task snapshot adds pins; it cannot prove that a hidden stack entry is absent. */
final class RuntimeTaskReferences {
    final Set<Integer> tasks=new HashSet<>();
    final Set<Integer> recent=new HashSet<>(),running=new HashSet<>(),owned=new HashSet<>();
    final Set<String> components=new HashSet<>();
    final Map<Integer,Set<String>> retainedByTask=new HashMap<>();
    final Map<Integer,Set<String>> rootTickets=new HashMap<>();
    static RuntimeTaskReferences observe(Context context){
        var manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        RuntimeTaskReferences result=new RuntimeTaskReferences();
        result.recents(context,manager);
        var attached=manager.getRunningTasks(4096);if(attached==null||attached.size()>=4096)throw new IllegalStateException("ACTIVITY_RUNNING_TASK_OBSERVATION_INCOMPLETE");
        for(var info:attached){result.tasks.add(info.taskId);result.running.add(info.taskId);result.add(context,info.baseActivity);result.add(context,info.topActivity);result.add(context,info.origActivity);if(info.baseActivity!=null&&context.getPackageName().equals(info.baseActivity.getPackageName()))result.owned.add(info.taskId);}
        result.recents(context,manager);
        return result;
    }
    private void recents(Context context,ActivityManager manager){
        var observed=manager.getAppTasks();if(observed==null)throw new IllegalStateException("ACTIVITY_TASK_OBSERVATION_UNAVAILABLE");
        for(var task:observed){
            var info=task.getTaskInfo();if(info==null||info.taskId<0)throw new IllegalStateException("ACTIVITY_TASK_OBSERVATION_INCOMPLETE");
            tasks.add(info.taskId);recent.add(info.taskId);if(info.baseActivity!=null&&context.getPackageName().equals(info.baseActivity.getPackageName()))owned.add(info.taskId);
            Set<String> retained=new HashSet<>();
            for(ComponentName name:new ComponentName[]{info.baseIntent==null?null:info.baseIntent.getComponent(),info.baseActivity,info.origActivity}){
                if(name!=null)retained.add(name.flattenToString());
            }
            if(info.baseIntent==null||info.baseIntent.getComponent()==null)throw new IllegalStateException("ACTIVITY_TASK_BASE_UNAVAILABLE");
            if(context.getPackageName().equals(info.baseIntent.getComponent().getPackageName())){
                String ticket=info.baseIntent.getStringExtra("tripoder.ticket");
                if(ticket!=null)rootTickets.computeIfAbsent(info.taskId,k->new HashSet<>()).add(ticket);
            }
            retainedByTask.computeIfAbsent(info.taskId,k->new HashSet<>()).addAll(retained);
            add(context,info.baseIntent==null?null:info.baseIntent.getComponent());
            add(context,info.baseActivity);add(context,info.origActivity);add(context,info.topActivity);
        }
    }
    boolean canReleaseFinished(Context context,int taskId,String stub){if(taskId<0)return false;if(!tasks.contains(taskId))return true;Set<String> retained=retainedByTask.get(taskId);return owned.contains(taskId)&&retained!=null&&!retained.contains(new ComponentName(context,stub).flattenToString());}
    private void add(Context context,ComponentName component){if(component!=null&&context.getPackageName().equals(component.getPackageName()))components.add(component.getClassName());}
}
