package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.*;
import java.util.*;
import org.json.*;

/** Automatic cleanup requires an empty task; explicit uninstall also permits retained owned records. */
public final class RuntimeEmptyTasks {
    private static final String REQUESTED="TASK_REMOVAL_REQUESTED:";
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
        Intent intent=info.baseIntent;if(intent==null||intent.getComponent()==null)return null;
        // Every root, controller or guest-started, is authenticated by its own ticket and delivery record.
        if(!broker.host.getPackageName().equals(intent.getComponent().getPackageName()))return null;
        String rootTicket=intent.getStringExtra("tripoder.ticket");if(rootTicket==null)return null;
        var actual=broker.activities.get(rootTicket);
        String delivery=broker.activities.launches.forTicket(rootTicket);
        if(!actual.stub.equals(intent.getComponent().getClassName())||!actual.component.equals(intent.getStringExtra("tripoder.component"))||!delivery.equals(intent.getStringExtra("tripoder.delivery")))return null;
        var launch=broker.activities.launches.get(delivery);
        if(!launch.generation.equals(intent.getStringExtra("tripoder.generation")))return null;
        String operation=launch.operation,space=actual.space,pkg=actual.pkg,install=actual.install,process=launch.generation,component=actual.component,hash=launch.payloadDigest!=null?launch.payloadDigest:delivery;
        if(removal!=null&&(!removal[0].equals(space)||!removal[1].equals(pkg)||!removal[2].equals(install)))return null;
        var root=broker.activities.get(rootTicket);
        if(!root.space.equals(space)||!root.pkg.equals(pkg)||!root.install.equals(install)||!root.component.equals(component))return null;
        if(generation!=null&&!generation.equals(root.generation))return null;
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE generation=? AND space=? AND package=? AND installGeneration=? AND processName=?",new String[]{process,root.space,root.pkg,root.install,root.process})){if(!c.moveToFirst())return null;}
        String proof;try(var c=broker.store.getReadableDatabase().rawQuery("SELECT resourceProof FROM activity_tickets WHERE ticket=?",new String[]{rootTicket})){if(!c.moveToFirst())return null;proof=c.getString(0);}
        boolean attached=root.taskId==info.taskId&&root.instance!=null&&"OWNED_TASK_ATTACHED".equals(proof);
        boolean fenced=root.taskId==info.taskId&&proof!=null&&proof.startsWith(REQUESTED);
        // A root that never materialised (the entry was dispatched but no Activity instance was ever created) can no
        // longer enter: the installation's sessions must all be DEAD below, so no client exists that could attach it.
        // Such an empty task owns no guest UI and only pins a process stub; retaining it forever exhausts capacity.
        boolean neverEntered=removal==null&&root.taskId==info.taskId&&root.instance==null;
        if(root.taskId!=info.taskId||(!neverEntered&&root.instance==null)||(!neverEntered&&!attached&&!fenced))return null;
        // A replacement generation may already be entering recovery; never reclaim under it.
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE space=? AND package=? AND installGeneration=? AND processName=? AND state!='DEAD' LIMIT 1",new String[]{root.space,root.pkg,root.install,root.process})){if(c.moveToFirst())return null;}
        List<String> tickets=new ArrayList<>();Set<String> physical=new HashSet<>();
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT t.ticket,t.space,t.package,t.installGeneration,t.generation,s.state,t.stub,s.space,s.package,s.installGeneration,t.processName,s.processName FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation WHERE t.taskId=? OR t.ticket=? ORDER BY t.ticket",new String[]{Integer.toString(info.taskId),rootTicket})){
            while(c.moveToNext()){
                // Android retains one task across process recreation. Its tickets may
                // belong to several generations; each must prove the same installation
                // ownership and an independently confirmed dead session.
                if(!space.equals(c.getString(1))||!pkg.equals(c.getString(2))||!install.equals(c.getString(3))||!"DEAD".equals(c.getString(5))||!space.equals(c.getString(7))||!pkg.equals(c.getString(8))||!install.equals(c.getString(9))||!Objects.equals(c.getString(10),c.getString(11)))return null;
                tickets.add(c.getString(0));physical.add(c.getString(6));
            }
        }
        if(!tickets.contains(rootTicket))return null;
        try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state!='DEAD' LIMIT 1",new String[]{space,pkg,install})){if(c.moveToFirst())return null;}
        if(removal!=null){
            for(ComponentName name:new ComponentName[]{info.baseActivity,info.topActivity,info.origActivity})if(name!=null&&(!broker.host.getPackageName().equals(name.getPackageName())||!physical.contains(name.getClassName())))return null;
        }
        String digest=ApkStore.textDigest((removal==null?"empty-ingress-task-v1\n":"uninstall-ingress-task-v1\n")+info.taskId+"\n"+operation+"\n"+hash+"\n"+process+"\n"+root.generation+"\n"+String.join("\n",tickets));
        return new Plan("task-reclaim-"+digest,info.taskId,operation,rootTicket,digest,tickets);
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
