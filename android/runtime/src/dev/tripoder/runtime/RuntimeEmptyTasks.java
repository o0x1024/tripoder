package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.*;
import java.util.*;
import org.json.*;

/** Automatic cleanup requires an empty task; explicit uninstall also permits retained owned records. */
public final class RuntimeEmptyTasks {
    private static final String REQUESTED="TASK_REMOVAL_REQUESTED:";
    private static final String GATEWAY="dev.tripoder.runtime.RuntimeGuestGateway";
    public static JSONArray reclaim(RuntimeBroker broker,String generation)throws Exception {return reclaim(broker,generation,null);}
    /** Explicit uninstall may remove retained Activity records, after all owned processes are dead. */
    static JSONArray removeApplicationTasks(RuntimeBroker broker,String space,String pkg,String install)throws Exception{return reclaim(broker,null,new String[]{space,pkg,install});}
    private static synchronized JSONArray reclaim(RuntimeBroker broker,String generation,String[] removal)throws Exception {
        JSONArray results=new JSONArray();
        synchronized(broker){reconcile(broker,generation,results);}
        var manager=broker.host.getSystemService(ActivityManager.class);var tasks=manager.getAppTasks();
        if(tasks==null||tasks.size()>256)throw new IllegalStateException("TASK_RECLAIM_OBSERVATION_INCOMPLETE");
        for(var task:tasks){
            var info=task.getTaskInfo();Plan plan;
            synchronized(broker){plan=plan(broker,info,generation,removal);if(plan==null)continue;
                var db=broker.store.getWritableDatabase();db.beginTransaction();try{
                    var admission=broker.contract.accept(plan.operation,"runtime-task-resources",plan.digest);
                    if(!admission.created){
                        if(admission.operation.state!=ExecutionContract.OperationState.UNKNOWN){results.put(plan.result(admission.operation.state.name()));db.setTransactionSuccessful();continue;}
                        // Removal is idempotent only after re-observing the complete original
                        // root identity and its durable fence. Never replay a guest operation.
                        for(String ticket:plan.tickets)try(var c=db.rawQuery("SELECT 1 FROM activity_tickets WHERE ticket=? AND taskId=? AND resourceProof=?",new String[]{ticket,Integer.toString(plan.taskId),REQUESTED+plan.operation})){if(!c.moveToFirst())throw new SecurityException("TASK_RECLAIM_FENCE_MISMATCH");}
                    }else{
                        broker.contract.start(plan.operation);
                        for(String ticket:plan.tickets){ContentValues row=new ContentValues();row.put("resourceProof",REQUESTED+plan.operation);db.update("activity_tickets",row,"ticket=?",new String[]{ticket});}
                    }
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            try{
                // The ticket fence is committed before this second observation and the side effect.
                // No broker monitor is held across the Android call.
                var current=task.getTaskInfo();
                synchronized(broker){Plan checked=plan(broker,current,generation,removal);if(checked==null||!plan.digest.equals(checked.digest))throw new SecurityException("TASK_RECLAIM_IDENTITY_CHANGED");}
                task.finishAndRemoveTask();
                synchronized(broker){
                    if(RuntimeTaskReferences.observe(broker.host).tasks.contains(plan.taskId))throw new IllegalStateException("TASK_REMOVAL_UNCONFIRMED");
                    complete(broker,plan.operation,plan.taskId,plan.tickets);results.put(plan.result("TASK_RESOURCES_RELEASED"));
                }
            }catch(Exception error){synchronized(broker){
                if(broker.contract.query(plan.operation).state==ExecutionContract.OperationState.RUNNING)broker.contract.markUnknown(plan.operation,"TASK_REMOVAL_UNCONFIRMED");
                results.put(plan.result("RETAINED_UNCONFIRMED").put("error",error.getClass().getSimpleName()));
            }}
        }
        return results;
    }
    private static Plan plan(RuntimeBroker broker,ActivityManager.RecentTaskInfo info,String generation,String[] removal)throws Exception {
        if(info==null||info.taskId<0)return null;
        if(removal==null&&(info.numActivities!=0||info.baseActivity!=null||info.topActivity!=null))return null;
        Intent intent=info.baseIntent;if(intent==null||!new ComponentName(broker.host,GATEWAY).equals(intent.getComponent()))return null;
        String operation=intent.getStringExtra("operation"),capability=intent.getStringExtra("capability");
        if(operation==null||capability==null||!capability.matches("[a-f0-9]{64}"))return null;
        var entry=new ControllerLaunchLedger(broker).get(operation);
        if(entry==null||!entry.state.equals("DISPATCHED")||entry.ticket==null||!entry.hash.equals(ApkStore.textDigest(capability)))return null;
        if(removal!=null&&(!removal[0].equals(entry.space)||!removal[1].equals(entry.pkg)||!removal[2].equals(entry.install)))return null;
        var root=broker.activities.get(entry.ticket);
        if(!root.space.equals(entry.space)||!root.pkg.equals(entry.pkg)||!root.install.equals(entry.install)||!root.component.equals(entry.component))return null;
        if(generation!=null&&!generation.equals(root.generation))return null;
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE generation=? AND space=? AND package=? AND installGeneration=? AND processName=?",new String[]{entry.process,root.space,root.pkg,root.install,root.process})){if(!c.moveToFirst())return null;}
        String proof;try(var c=broker.store.getReadableDatabase().rawQuery("SELECT resourceProof FROM activity_tickets WHERE ticket=?",new String[]{entry.ticket})){if(!c.moveToFirst())return null;proof=c.getString(0);}
        boolean attached=root.taskId==info.taskId&&root.instance!=null&&"OWNED_TASK_ATTACHED".equals(proof);
        boolean fenced=root.taskId==info.taskId&&proof!=null&&proof.startsWith(REQUESTED);
        // A gateway task ID alone does not prove where Android placed the guest.
        // Unattached launches and a guest reparented into another task remain pinned.
        if(root.taskId!=info.taskId||root.instance==null||(!attached&&!fenced))return null;
        if(entry.gatewayTaskId>=0&&entry.gatewayTaskId!=info.taskId)return null;
        // A replacement generation may already be entering recovery; never reclaim under it.
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE space=? AND package=? AND installGeneration=? AND processName=? AND state!='DEAD' LIMIT 1",new String[]{root.space,root.pkg,root.install,root.process})){if(c.moveToFirst())return null;}
        List<String> tickets=new ArrayList<>();Set<String> physical=new HashSet<>();physical.add(GATEWAY);
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT t.ticket,t.space,t.package,t.installGeneration,t.generation,s.state,t.stub FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation WHERE t.taskId=? OR t.ticket=? ORDER BY t.ticket",new String[]{Integer.toString(info.taskId),entry.ticket})){
            while(c.moveToNext()){
                // Automatic cleanup is generation-scoped; uninstall is installation-scoped.
                // Neither can remove another application's task or a live process's record.
                if(!entry.space.equals(c.getString(1))||!entry.pkg.equals(c.getString(2))||!entry.install.equals(c.getString(3))||(removal==null&&!root.generation.equals(c.getString(4)))||!"DEAD".equals(c.getString(5)))return null;
                tickets.add(c.getString(0));physical.add(c.getString(6));
            }
        }
        if(!tickets.contains(entry.ticket))return null;
        if(removal!=null){
            for(ComponentName component:new ComponentName[]{info.baseActivity,info.topActivity,info.origActivity})if(component!=null&&(!broker.host.getPackageName().equals(component.getPackageName())||!physical.contains(component.getClassName())))return null;
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state!='DEAD' LIMIT 1",removal)){if(c.moveToFirst())return null;}
        }
        String digest=ApkStore.textDigest((removal==null?"empty-ingress-task-v1\n":"uninstall-ingress-task-v1\n")+info.taskId+"\n"+operation+"\n"+entry.hash+"\n"+entry.process+"\n"+root.generation+"\n"+String.join("\n",tickets));
        return new Plan("task-reclaim-"+digest,info.taskId,operation,entry.ticket,digest,tickets);
    }
    private static void reconcile(RuntimeBroker broker,String generation,JSONArray results)throws Exception {
        var refs=RuntimeTaskReferences.observe(broker.host);Map<String,List<String>> tickets=new TreeMap<>();Map<String,Integer> ids=new HashMap<>();
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT t.ticket,t.taskId,t.resourceProof,s.state FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation WHERE t.resourceProof LIKE 'TASK_REMOVAL_REQUESTED:%' "+(generation==null?"":"AND t.generation=? ")+"ORDER BY t.ticket",generation==null?null:new String[]{generation})){
            while(c.moveToNext()){
                if(!"DEAD".equals(c.getString(3)))throw new IllegalStateException("TASK_RECLAIM_FENCE_HAS_LIVE_PROCESS");
                String operation=c.getString(2).substring(REQUESTED.length());int id=c.getInt(1);
                Integer previous=ids.put(operation,id);if(previous!=null&&previous!=id)throw new SecurityException("TASK_RECLAIM_FENCE_MISMATCH");
                tickets.computeIfAbsent(operation,k->new ArrayList<>()).add(c.getString(0));
            }
        }
        for(var group:tickets.entrySet())if(!refs.tasks.contains(ids.get(group.getKey()))){complete(broker,group.getKey(),ids.get(group.getKey()),group.getValue());results.put(new JSONObject().put("operation",group.getKey()).put("taskId",ids.get(group.getKey())).put("state","TASK_RESOURCES_RELEASED"));}
    }
    private static void complete(RuntimeBroker broker,String operation,int taskId,List<String> tickets)throws Exception {
        String evidence="authenticated-ingress-task="+taskId+";ticket-fence="+operation+";all-generations-dead;absent-recent-running-recent";
        var row=broker.contract.query(operation);if(row==null||!row.callerIdentity.equals("runtime-task-resources"))throw new SecurityException("TASK_RECLAIM_OPERATION_MISMATCH");
        var db=broker.store.getWritableDatabase();db.beginTransaction();try{
            for(String ticket:tickets){ContentValues v=new ContentValues();v.put("state","RETIRED");v.put("resourceProof","TASK_REMOVED_CONFIRMED");if(db.update("activity_tickets",v,"ticket=? AND resourceProof=?",new String[]{ticket,REQUESTED+operation})!=1)throw new SecurityException("TASK_RECLAIM_FENCE_MISMATCH");}
            if(row.state==ExecutionContract.OperationState.UNKNOWN)broker.contract.reconcile(operation,ExecutionContract.OperationState.SUCCEEDED,evidence,evidence);
            else if(row.state==ExecutionContract.OperationState.RUNNING)broker.contract.succeed(operation,evidence);
            else throw new IllegalStateException("TASK_RECLAIM_OPERATION_STATE");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static final class Plan {
        final String operation,launch,root,digest;final int taskId;final List<String> tickets;
        Plan(String operation,int taskId,String launch,String root,String digest,List<String> tickets){this.operation=operation;this.taskId=taskId;this.launch=launch;this.root=root;this.digest=digest;this.tickets=tickets;}
        JSONObject result(String state)throws Exception{return new JSONObject().put("operation",operation).put("taskId",taskId).put("launchOperation",launch).put("rootTicket",root).put("tickets",new JSONArray(tickets)).put("state",state);}
    }
    private RuntimeEmptyTasks(){}
}
