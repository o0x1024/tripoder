package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** A component can be reassigned only after every instance and Android task reference is gone. */
final class ActivityComponentLedger {
    private final RuntimeStore store;
    private final android.content.Context context;
    private final ActivityAffinityLedger affinities;private final ActivityEntryLedger entries;
    ActivityComponentLedger(RuntimeStore store,android.content.Context context){this.store=store;this.context=context;affinities=new ActivityAffinityLedger(store);entries=new ActivityEntryLedger(store);}
    static void create(SQLiteDatabase db){
        db.execSQL("CREATE TABLE activity_components(stub TEXT PRIMARY KEY, space TEXT NOT NULL, package TEXT NOT NULL, installGeneration TEXT NOT NULL, processName TEXT NOT NULL, processStub TEXT NOT NULL, component TEXT NOT NULL, generation TEXT NOT NULL, lease TEXT NOT NULL UNIQUE, affinityLease TEXT, profile TEXT, UNIQUE(space,package,installGeneration,component))");
    }
    static String canonical(android.content.pm.ActivityInfo info){return info.targetActivity==null?info.name:info.targetActivity;}
    String acquire(RuntimeBroker.Slot slot,String requested){
        var i=slot.allocation.session.identity;var info=RuntimeComponents.activity(slot.install,requested);boolean reparent=RuntimeComponents.reparents(info);String component=canonical(info),profile=info.launchMode+":"+info.taskAffinity+":"+reparent;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub,processStub,processName,lease,profile FROM activity_components WHERE space=? AND package=? AND installGeneration=? AND component=?",new String[]{i.spaceId,i.packageName,i.installGeneration,component})){
            if(c.moveToFirst()){
                if(!profile.equals(c.getString(4)))throw new SecurityException("ACTIVITY_PROFILE_CHANGED_OR_RETIRED");
                if(!c.getString(1).equals(slot.activityStub)||!c.getString(2).equals(i.processName))throw new SecurityException("ACTIVITY_COMPONENT_PROCESS_MISMATCH");
                ContentValues update=new ContentValues();update.put("generation",i.processGeneration);store.getWritableDatabase().update("activity_components",update,"stub=?",new String[]{c.getString(0)});
                return entries.acquire(c.getString(0),c.getString(3),component,requested);
            }
        }
        int aliasCount=0;for(var declared:slot.install.activityIndex().values())if(declared.enabled&&component.equals(declared.targetActivity))aliasCount++;
        if(aliasCount>RuntimeActivitySlots.ALIASES)throw new IllegalStateException("ACTIVITY_DECLARED_ALIAS_CAPACITY_EXHAUSTED");
        // Reserve an alias-capable family even when the canonical target is launched
        // first. Non-alias components cannot consume this separate finite resource.
        int first=aliasCount>0?0:RuntimeActivitySlots.ALIAS_COMPONENTS;
        int end=aliasCount>0?RuntimeActivitySlots.ALIAS_COMPONENTS:RuntimeActivitySlots.capacity(info.launchMode);
        var affinity=affinities.acquire(i.spaceId,i.packageName,info.taskAffinity);
        Set<String> occupied=new HashSet<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub FROM activity_components",null)){while(c.moveToNext())occupied.add(c.getString(0));}
        int process=RuntimeSlot.index(slot.activityStub),mode=info.launchMode;String stub=null;for(int n=first;n<end;n++){String candidate=RuntimeActivitySlots.name(process,n,mode,affinity.slot,reparent);if(!occupied.contains(candidate)){stub=candidate;break;}}
        if(stub==null)throw new IllegalStateException("ACTIVITY_COMPONENT_CAPACITY_EXHAUSTED");
        String lease=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("stub",stub);row.put("space",i.spaceId);row.put("package",i.packageName);row.put("installGeneration",i.installGeneration);row.put("processName",i.processName);row.put("processStub",slot.activityStub);row.put("component",component);row.put("generation",i.processGeneration);row.put("lease",lease);row.put("affinityLease",affinity.lease);row.put("profile",profile);store.getWritableDatabase().insertOrThrow("activity_components",null,row);
        return entries.acquire(stub,lease,component,requested);
    }
    String lease(String stub){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT lease FROM activity_components WHERE stub=?",new String[]{RuntimeActivitySlots.target(stub)})){if(!c.moveToFirst())throw new SecurityException("ACTIVITY_COMPONENT_LEASE_MISSING");return c.getString(0);}}
    void require(ActivityLedger.Ticket ticket,RuntimeBroker.Slot slot){
        String canonical=canonical(RuntimeComponents.activity(slot.install,ticket.component));entries.require(ticket);String target=RuntimeActivitySlots.target(ticket.stub);
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT space,package,installGeneration,processName,processStub,component,lease FROM activity_components WHERE stub=?",new String[]{target})){
            if(!c.moveToFirst()||!c.getString(0).equals(ticket.space)||!c.getString(1).equals(ticket.pkg)||!c.getString(2).equals(ticket.install)||!c.getString(3).equals(ticket.process)||!c.getString(4).equals(slot.activityStub)||!c.getString(5).equals(canonical)||!c.getString(6).equals(ticket.componentLease))throw new SecurityException("ACTIVITY_COMPONENT_LEASE_MISMATCH");
        }
        ContentValues row=new ContentValues();row.put("generation",slot.allocation.session.processGeneration);store.getWritableDatabase().update("activity_components",row,"stub=?",new String[]{target});
    }
    void releaseUnused(){
        if(store.getWritableDatabase().inTransaction())throw new IllegalStateException("ACTIVITY_OBSERVATION_DURING_TRANSACTION");
        RuntimeTaskReferences references=RuntimeTaskReferences.observe(context);
        retireRemovedTasks(references);
        List<String> releasable=new ArrayList<>();
        // A result receipt may precede postDestroy. An unclaimed NEW_INTENT candidate has
        // no instance, while process death proves Java instances gone but not task removal.
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub,lease FROM activity_components ac WHERE NOT EXISTS (SELECT 1 FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation WHERE t.componentLease=ac.lease AND (t.state NOT IN ('FINISHED','REVOKED','RETIRED') OR (t.instance IS NOT NULL AND t.destroyedConfirmed=0 AND COALESCE(s.state,'UNKNOWN')!='DEAD')))",null)){
            while(c.moveToNext()){
                String stub=c.getString(0);Set<String> physical=entries.stubs(c.getString(1));physical.add(stub);boolean safe=Collections.disjoint(references.components,physical);
                try(Cursor instances=store.getReadableDatabase().rawQuery("SELECT t.taskId,t.instance,l.kind,l.receiver,l.instance,t.stub,t.resourceProof FROM activity_tickets t LEFT JOIN activity_launches l ON l.candidate=t.ticket WHERE t.componentLease=?",new String[]{c.getString(1)})){
                    while(instances.moveToNext()){
                        String proof=instances.getString(6);
                        if("TASK_REMOVED_CONFIRMED".equals(proof)||(proof!=null&&proof.startsWith("UNINSTALL_DEAD_UNATTACHED;")))continue;
                        if(!instances.isNull(1))safe&=references.canReleaseFinished(context,instances.getInt(0),instances.getString(5));
                        else safe&=("NEW_INTENT".equals(instances.getString(2))||"TASK_TO_FRONT".equals(instances.getString(2))||"TASK_ALREADY_FRONT".equals(instances.getString(2)))&&!instances.isNull(3)&&!instances.isNull(4);
                    }
                }
                if(safe)releasable.add(stub);
            }
        }
        for(String stub:releasable)store.getWritableDatabase().delete("activity_components","stub=?",new String[]{stub});
        affinities.releaseUnused();
    }
    /** Explicit uninstall of one application, after every process of it was stopped: a component is released when all its tickets are terminal and their generations are dead, and no Android task still references the component. A ticket that never had a Java instance cannot be a task root, so it needs no further proof; a ticket with an instance keeps the usual task-removal proof. */
    void releaseForUninstall(String space,String pkg){
        if(store.getWritableDatabase().inTransaction())throw new IllegalStateException("ACTIVITY_OBSERVATION_DURING_TRANSACTION");
        RuntimeTaskReferences references=RuntimeTaskReferences.observe(context);
        retireRemovedTasks(references);
        List<String> releasable=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT stub,lease FROM activity_components ac WHERE ac.space=? AND ac.package=? AND NOT EXISTS (SELECT 1 FROM activity_tickets t LEFT JOIN sessions s ON s.generation=t.generation WHERE t.componentLease=ac.lease AND (t.state NOT IN ('FINISHED','REVOKED','RETIRED') OR COALESCE(s.state,'UNKNOWN') NOT IN ('DEAD','FAILED')))",new String[]{space,pkg})){
            while(c.moveToNext()){
                String stub=c.getString(0);Set<String> physical=entries.stubs(c.getString(1));physical.add(stub);boolean safe=Collections.disjoint(references.components,physical);
                try(Cursor instances=store.getReadableDatabase().rawQuery("SELECT t.taskId,t.instance,t.stub,t.resourceProof FROM activity_tickets t WHERE t.componentLease=?",new String[]{c.getString(1)})){
                    while(instances.moveToNext()){
                        String proof=instances.getString(3);
                        if("TASK_REMOVED_CONFIRMED".equals(proof)||(proof!=null&&proof.startsWith("UNINSTALL_DEAD_UNATTACHED;"))||instances.isNull(1))continue;
                        safe&=references.canReleaseFinished(context,instances.getInt(0),instances.getString(2));
                    }
                }
                if(safe)releasable.add(stub);
            }
        }
        for(String stub:releasable)store.getWritableDatabase().delete("activity_components","stub=?",new String[]{stub});
        affinities.releaseUnused();
    }
    private void retireRemovedTasks(RuntimeTaskReferences references){
        // Reclaim physical resources only. Operations, deliveries and UI results retain
        // their original outcome, including UNKNOWN; no business effect is replayed.
        // Attached task ownership is positive persisted evidence, not an inferred ID.
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT t.ticket,t.taskId,t.generation FROM activity_tickets t JOIN sessions s ON s.generation=t.generation WHERE t.componentLease IS NOT NULL AND t.instance IS NOT NULL AND t.taskId>=0 AND t.resourceProof='OWNED_TASK_ATTACHED' AND s.state='DEAD' AND t.state NOT IN ('FINISHED','REVOKED','RETIRED')",null)){
            while(c.moveToNext())if(!references.tasks.contains(c.getInt(1))){
                ContentValues row=new ContentValues();row.put("state","RETIRED");row.put("resourceProof","attached-own-task="+c.getInt(1)+";generation-dead="+c.getString(2)+";absent-recent-running-recent;observed="+System.currentTimeMillis());
                store.getWritableDatabase().update("activity_tickets",row,"ticket=?",new String[]{c.getString(0)});
            }
        }
    }
    void requireOwnTask(int taskId){if(taskId<0||!RuntimeTaskReferences.observe(context).owned.contains(taskId))throw new SecurityException("ACTIVITY_EXTERNAL_TASK_UNSUPPORTED");}
    String retainedStub(String space,String pkg,String install,String process){
        releaseUnused();String found=null;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT processStub,installGeneration FROM activity_components WHERE space=? AND package=? AND processName=?",new String[]{space,pkg,process})){
            while(c.moveToNext()){if(!install.equals(c.getString(1)))throw new IllegalStateException("ACTIVITY_INSTALL_PIN_CONFLICT");if(found!=null&&!found.equals(c.getString(0)))throw new IllegalStateException("PROCESS_MULTIPLE_STUB_LEASES");found=c.getString(0);}
        }return found;
    }
    boolean occupied(String stub){releaseUnused();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM activity_components WHERE processStub=? LIMIT 1",new String[]{stub})){return c.moveToFirst();}}
    void requireUnused(String space,String pkg){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT 1 FROM activity_components WHERE space=? AND package=? LIMIT 1",new String[]{space,pkg})){if(c.moveToFirst())throw new IllegalStateException("INSTALL_IN_USE_BY_TASK_COMPONENT");}}
}
