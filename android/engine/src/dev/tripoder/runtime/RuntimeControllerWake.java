package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.os.Bundle;
import java.util.*;

/** Tapping an application that is already running returns its task to the front instead of opening a second root. */
final class RuntimeControllerWake {
    static final String WOKEN="APPLICATION_WOKEN:";
    /** Null: no entry of this installation is open. Otherwise the open entries must prove one attached,
     *  live task, or the launch stays rejected with its structured error; nothing is guessed. */
    static Bundle wake(RuntimeBroker broker,String space,String pkg,String generation,String operation)throws Exception{
        Set<Integer> liveTasks=new LinkedHashSet<>();
        synchronized(broker){
            Set<String> processes=new HashSet<>();int open=0;
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT state,taskId,generation FROM activity_tickets WHERE space=? AND package=? AND installGeneration=? AND entryFenceOperation IS NULL AND state NOT IN ('FINISHED','REVOKED','RETIRED') LIMIT 65",new String[]{space,pkg,generation})){
                while(c.moveToNext()){
                    if(++open>64)throw new IllegalStateException("APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED");
                    int task=c.getInt(1);
                    if(task<0)throw new IllegalStateException("APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED");
                    if("LIVE".equals(c.getString(0)))liveTasks.add(task);
                    processes.add(c.getString(2));
                }
            }
            if(open==0)return null;
            if(liveTasks.isEmpty())throw new IllegalStateException("APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED");
            for(String process:processes)try(var c=broker.store.getReadableDatabase().rawQuery("SELECT state FROM sessions WHERE generation=?",new String[]{process})){
                if(!c.moveToFirst()||"DEAD".equals(c.getString(0))||"FAILED".equals(c.getString(0)))throw new IllegalStateException("APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED");
            }
        }
        // The platform call is made without holding the broker monitor.
        ActivityManager.AppTask target=null;int taskId=-1;
        var tasks=broker.host.getSystemService(ActivityManager.class).getAppTasks();
        if(tasks==null||tasks.size()>256)throw new IllegalStateException("TASK_WAKE_OBSERVATION_INCOMPLETE");
        for(var task:tasks){var info=task.getTaskInfo();if(info!=null&&liveTasks.contains(info.taskId)){target=task;taskId=info.taskId;break;}}
        if(target==null)throw new IllegalStateException("APPLICATION_TASK_NOT_FOUND");
        target.moveToFront();
        synchronized(broker){
            broker.contract.succeed(operation,WOKEN+taskId);
            Bundle out=new Bundle();out.putString("operation",operation);out.putBoolean("woken",true);out.putInt("taskId",taskId);return out;
        }
    }
    private RuntimeControllerWake(){}
}
