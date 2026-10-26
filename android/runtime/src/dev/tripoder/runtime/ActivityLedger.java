package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.UUID;

/** Durable Android task references. A dead process does not make its stub available. */
final class ActivityLedger {
    private final RuntimeStore store;
    private final ExecutionContract contract;
    private final ActivityComponentLedger components;
    final ActivityLaunchLedger launches;
    ActivityLedger(RuntimeStore store,ExecutionContract contract,ActivityComponentLedger components){this.store=store;this.contract=contract;this.components=components;launches=new ActivityLaunchLedger(store,contract);}
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE activity_tickets(ticket TEXT PRIMARY KEY, space TEXT NOT NULL, package TEXT NOT NULL, installGeneration TEXT NOT NULL, processName TEXT NOT NULL, stub TEXT NOT NULL, component TEXT NOT NULL, generation TEXT NOT NULL, state TEXT NOT NULL, instance TEXT, taskId INTEGER NOT NULL, resultOperation TEXT, processStub TEXT NOT NULL, destroyedConfirmed INTEGER NOT NULL DEFAULT 0, componentLease TEXT, resourceProof TEXT, entryFenceOperation TEXT)");}
    static final class Ticket {
        final String id,space,pkg,install,process,stub,component,generation,state,instance,resultOperation,processStub,componentLease,resourceProof;final int taskId;final boolean destroyedConfirmed;
        Ticket(Cursor c){id=c.getString(0);space=c.getString(1);pkg=c.getString(2);install=c.getString(3);process=c.getString(4);stub=c.getString(5);component=c.getString(6);generation=c.getString(7);state=c.getString(8);instance=c.getString(9);taskId=c.getInt(10);resultOperation=c.getString(11);processStub=c.getString(12);destroyedConfirmed=c.getInt(13)!=0;componentLease=c.getString(14);resourceProof=c.getString(15);}
    }
    String prepare(RuntimeBroker.Slot slot,String component,String resultOperation,String launchOperation,String callerPackage,String payloadDigest,Ticket source){
        return prepareWithCommit(slot,component,resultOperation,launchOperation,callerPackage,payloadDigest,source,ticket->{});
    }
    String prepare(RuntimeBroker.Slot slot,String component,String resultOperation,String launchOperation,String callerPackage,String payloadDigest,Ticket source,int flags){
        return prepareWithCommit(slot,component,resultOperation,launchOperation,callerPackage,payloadDigest,source,flags,ticket->{});
    }
    String prepareContext(RuntimeBroker.Slot slot,String component,String launchOperation,String callerPackage,String payloadDigest,int flags){
        return prepareWithCommit(slot,component,null,launchOperation,callerPackage,payloadDigest,null,flags,ticket->{},true);
    }
    /** Observe Android before opening a transaction; associate ingress metadata in the ticket commit. */
    String prepareWithCommit(RuntimeBroker.Slot slot,String component,String resultOperation,String launchOperation,String callerPackage,String payloadDigest,Ticket source,java.util.function.Consumer<String> associate){
        return prepareWithCommit(slot,component,resultOperation,launchOperation,callerPackage,payloadDigest,source,0,associate);
    }
    private String prepareWithCommit(RuntimeBroker.Slot slot,String component,String resultOperation,String launchOperation,String callerPackage,String payloadDigest,Ticket source,int flags,java.util.function.Consumer<String> associate){
        return prepareWithCommit(slot,component,resultOperation,launchOperation,callerPackage,payloadDigest,source,flags,associate,false);
    }
    private String prepareWithCommit(RuntimeBroker.Slot slot,String component,String resultOperation,String launchOperation,String callerPackage,String payloadDigest,Ticket source,int flags,java.util.function.Consumer<String> associate,boolean fromContext){
        String ticket=UUID.randomUUID().toString();var identity=slot.allocation.session.identity;boolean thin=slot.mode.routesThroughPlatform()&&launchOperation!=null&&!ControllerTrust.OPERATIONS.equals(contract.query(launchOperation).callerIdentity);
        components.releaseUnused();
        var db=store.getWritableDatabase();db.beginTransaction();try{
            String stub=components.acquire(slot,component);
            ContentValues row=new ContentValues();row.put("ticket",ticket);row.put("space",identity.spaceId);row.put("package",identity.packageName);row.put("installGeneration",identity.installGeneration);row.put("processName",identity.processName);row.put("stub",stub);row.put("processStub",slot.activityStub);row.put("component",component);row.put("generation",slot.allocation.session.processGeneration);row.put("state",thin?"LIVE":"PREPARED");row.put("taskId",thin&&source!=null&&source.taskId>=0&&(flags&android.content.Intent.FLAG_ACTIVITY_NEW_TASK)==0?source.taskId:-1);row.put("resultOperation",resultOperation);
            if(thin){row.put("instance",ticket);row.put("resourceProof","OWNED_TASK_ATTACHED");}
            row.put("componentLease",components.lease(stub));db.insertOrThrow("activity_tickets",null,row);String delivery=fromContext?launches.prepareContext(ticket,identity.processGeneration,launchOperation,callerPackage,payloadDigest,flags):launches.prepare(ticket,identity.processGeneration,launchOperation,resultOperation!=null,callerPackage,payloadDigest,source,flags);
            if(thin){Ticket prepared=get(ticket);launches.enter(delivery,prepared,prepared,ticket,"CREATE",payloadDigest);launches.received(delivery,ticket,ticket);}
            associate.accept(ticket);db.setTransactionSuccessful();return ticket;
        }finally{db.endTransaction();}
    }
    void releaseUnused(){components.releaseUnused();}
    void requireCapacity(ApkStore.BundleInstall install,String component){components.requireCapacity(install,component);}
    /** The physical start for a controller-created root: a launcher-style entry whose stub roots a fresh task the platform then owns. */
    android.content.Intent controllerEntry(android.content.Context host,String id,String category){
        if(category!=null&&!category.equals(android.content.Intent.CATEGORY_INFO)&&!category.equals(android.content.Intent.CATEGORY_LAUNCHER))throw new IllegalArgumentException("APPLICATION_ENTRY_CATEGORY_INVALID");
        Ticket ticket=get(id);String delivery=launches.forTicket(id);var launch=launches.get(delivery);
        android.content.Intent intent=new android.content.Intent().setComponent(new android.content.ComponentName(host,ticket.stub)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra("tripoder.component",ticket.component).putExtra("tripoder.generation",ticket.generation).putExtra("tripoder.ticket",id).putExtra("tripoder.delivery",delivery)
            .putExtra("tripoder.callerKind",launch.callerKind).putExtra("tripoder.callerPackage",launch.callerPackage).putExtra("tripoder.expectsResult",launch.expectsResult);
        if(launch.callerComponent!=null)intent.putExtra("tripoder.callerComponent",launch.callerComponent);
        if(category!=null)intent.setAction(android.content.Intent.ACTION_MAIN).addCategory(category);return intent;
    }
    void releaseComponentsForUninstall(String space,String pkg){components.releaseForUninstall(space,pkg);}
    /** Explicit uninstall or dead-installation cleanup: a ticket that never attached to an Android task (taskId < 0), whose process generation is DEAD, owns no live resource. It is retired with its proof recorded so the in-use check does not wait forever on it; a ticket with an attached task or a live generation is never touched. Returns how many were retired. */
    int retireDeadUnattached(String space,String pkg,String install,String operation){
        SQLiteDatabase db=store.getWritableDatabase();int retired=0;
        try(Cursor c=db.rawQuery("SELECT t.ticket,t.generation FROM activity_tickets t JOIN sessions s ON s.generation=t.generation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND t.taskId<0 AND t.destroyedConfirmed=0 AND s.state='DEAD' AND t.state NOT IN ('FINISHED','REVOKED','RETIRED')",new String[]{space,pkg,install})){
            while(c.moveToNext()){
                ContentValues row=new ContentValues();row.put("state","RETIRED");row.put("resourceProof","UNINSTALL_DEAD_UNATTACHED;operation="+operation+";generation-dead="+c.getString(1));
                if(db.update("activity_tickets",row,"ticket=? AND taskId<0 AND state NOT IN ('FINISHED','REVOKED','RETIRED')",new String[]{c.getString(0)})==1)retired++;
            }
        }
        // A delivery whose ticket is already terminal, in a generation that is dead, can no longer happen: a late system start meets a finished ticket and is rejected, whether the ticket ended just now or earlier. Its operation keeps its original outcome, including UNKNOWN; only the physical delivery record is closed.
        ContentValues launch=new ContentValues();launch.put("state","RETIRED");
        db.update("activity_launches",launch,"state IN ('PREPARED','ENTERED','UNKNOWN') AND candidate IN (SELECT t.ticket FROM activity_tickets t JOIN sessions s ON s.generation=t.generation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND t.state IN ('FINISHED','REVOKED','RETIRED') AND s.state='DEAD')",new String[]{space,pkg,install});
        return retired;
    }
    int retireDeadTasks(String space,String pkg,String install,String operation,java.util.List<android.app.ActivityManager.AppTask> appTasks){
        SQLiteDatabase db=store.getWritableDatabase();int retired=0;
        RuntimeTaskReferences observed=RuntimeTaskReferences.observe(components.contextForObservation());
        try(Cursor c=db.rawQuery("SELECT t.ticket,t.generation,t.taskId FROM activity_tickets t JOIN sessions s ON s.generation=t.generation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND s.state='DEAD' AND t.state NOT IN ('FINISHED','REVOKED','RETIRED')",new String[]{space,pkg,install})){
            while(c.moveToNext()){
                String ticket=c.getString(0);
                String gen=c.getString(1);
                int taskId=c.getInt(2);
                // Android removal was requested only by the authenticated task
                // retirement path. A dead process does not prove its task absent.
                // Preserve attached ownership if Android still retains that task.
                if(observed.tasks.contains(taskId))continue;
                ContentValues row=new ContentValues();row.put("state","RETIRED");row.put("resourceProof","DEAD_SESSION_TASK_RETIRED;operation="+operation+";generation-dead="+gen+";absent-recent-running-recent");
                if(db.update("activity_tickets",row,"ticket=? AND state NOT IN ('FINISHED','REVOKED','RETIRED')",new String[]{ticket})==1)retired++;
            }
        }
        ContentValues launch=new ContentValues();launch.put("state","RETIRED");
        db.update("activity_launches",launch,"state IN ('PREPARED','ENTERED','UNKNOWN') AND candidate IN (SELECT t.ticket FROM activity_tickets t JOIN sessions s ON s.generation=t.generation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND t.state IN ('FINISHED','REVOKED','RETIRED') AND s.state='DEAD')",new String[]{space,pkg,install});
        return retired;
    }
    Ticket get(String id){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM activity_tickets WHERE ticket=?",new String[]{id})){if(!c.moveToFirst())throw new SecurityException("ACTIVITY_TICKET_MISSING");return new Ticket(c);}}
    void caller(String delivery,android.os.Bundle out){var launch=launches.get(delivery);launch.caller(out);if("ACTIVITY".equals(launch.callerKind))out.putString("callerPhysical",get(launch.sourceTicket).stub);}
    Ticket source(String id,String instance,RuntimeBroker.Slot slot,int taskId){
        boolean thin=slot.mode.routesThroughPlatform();
        Ticket ticket=thin?get(id):instance(id,slot.allocation.session.processGeneration,instance);requireOwner(ticket,slot);
        if(thin){
            if(!ticket.generation.equals(slot.allocation.session.processGeneration))throw new SecurityException("ACTIVITY_INSTANCE_STALE");
            if(taskId>=0&&(ticket.taskId!=taskId||!"LIVE".equals(ticket.state)||ticket.instance==null)){
                ContentValues row=new ContentValues();row.put("taskId",taskId);row.put("resourceProof","OWNED_TASK_ATTACHED");
                if(ticket.instance==null)row.put("instance",instance!=null&&!instance.isEmpty()?instance:id);
                if("PREPARED".equals(ticket.state)||"CLAIMED".equals(ticket.state))row.put("state","LIVE");
                update(id,row);ticket=get(id);
            }
        }
        // onCreate may start an Activity before its postCreated ACK. Attachment is already
        // confirmed at this point. Destruction/recreation invalidates the old instance.
        if(ticket.taskId<0||ticket.destroyedConfirmed||!(ticket.state.equals("CLAIMED")||ticket.state.equals("LIVE")||ticket.state.equals("FINISHED")))throw new SecurityException("ACTIVITY_SOURCE_NOT_ATTACHED");
        if(taskId!=ticket.taskId){
            if(!RuntimeComponents.reparents(RuntimeComponents.activity(slot.install,ticket.component)))throw new SecurityException("ACTIVITY_TASK_CHANGED_UNDECLARED");
            components.requireOwnTask(taskId);ContentValues row=new ContentValues();row.put("taskId",taskId);row.put("resourceProof","OWNED_TASK_ATTACHED");update(id,row);ticket=get(id);
        }
        return ticket;
    }
    void claim(String id,RuntimeBroker.Slot slot,String component,String instance,String stub,String delivery,String payloadDigest){
        Ticket ticket=get(id);requireOwner(ticket,slot);
        if(!ticket.component.equals(component)||!ticket.generation.equals(slot.allocation.session.processGeneration)||!ticket.stub.equals(stub))throw new SecurityException("ACTIVITY_TICKET_SESSION_MISMATCH");
        if(!ticket.state.equals("PREPARED")&&!ticket.state.equals("RECREATING"))throw new SecurityException("ACTIVITY_TICKET_NOT_CLAIMABLE:"+ticket.state);
        if(!launches.forTicket(id).equals(delivery))throw new SecurityException("ACTIVITY_DELIVERY_TICKET_MISMATCH");
        boolean thin=slot.mode.routesThroughPlatform();
        var db=store.getWritableDatabase();db.beginTransaction();try{
            if(ticket.state.equals("PREPARED")){
                launches.enter(delivery,ticket,ticket,instance,"CREATE",payloadDigest);
                if(thin)launches.received(delivery,id,instance);
            }
            ContentValues row=new ContentValues();row.put("state",thin?"LIVE":"CLAIMED");row.put("instance",instance);row.put("destroyedConfirmed",0);
            if(thin)row.put("resourceProof","OWNED_TASK_ATTACHED");
            update(id,row);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void attached(String id,String generation,String instance,int taskId){Ticket ticket=instance(id,generation,instance);if(!ticket.state.equals("CLAIMED")&&!ticket.state.equals("LIVE"))throw new SecurityException("ACTIVITY_ATTACH_STATE");components.requireOwnTask(taskId);ContentValues row=new ContentValues();row.put("taskId",taskId);row.put("resourceProof","OWNED_TASK_ATTACHED");update(id,row);}
    void created(String id,String generation,String instance,int taskId){
        Ticket ticket=instance(id,generation,instance);if((!ticket.state.equals("CLAIMED")&&!ticket.state.equals("LIVE")&&!ticket.state.equals("FINISHED"))||taskId<0)throw new SecurityException("ACTIVITY_CREATE_STATE");ContentValues row=new ContentValues();row.put("state",ticket.state.equals("FINISHED")?"FINISHED":"LIVE");row.put("taskId",taskId);update(id,row);
        String delivery=launches.forTicket(id);if(launches.get(delivery).state.equals("ENTERED"))launches.received(delivery,id,instance);
    }
    void newIntent(String receiver,String instance,RuntimeBroker.Slot slot,String candidate,String delivery,String stub,String payloadDigest){
        Ticket current=instance(receiver,slot.allocation.session.processGeneration,instance),incoming=get(candidate);requireOwner(current,slot);requireOwner(incoming,slot);
        if(!current.state.equals("LIVE")||!incoming.state.equals("PREPARED")||!incoming.stub.equals(stub)||!current.componentLease.equals(incoming.componentLease)||!incoming.generation.equals(current.generation))throw new SecurityException("ACTIVITY_NEW_INTENT_MISMATCH");
        var db=store.getWritableDatabase();db.beginTransaction();try{launches.enter(delivery,incoming,current,instance,"NEW_INTENT",payloadDigest);ContentValues row=new ContentValues();row.put("state","DELIVERING");update(candidate,row);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    void newIntentReceived(String receiver,String instance,String generation,String delivery,boolean success){
        Ticket current=instance(receiver,generation,instance);var launch=launches.get(delivery);if(!receiver.equals(launch.receiver)||!generation.equals(launch.generation)||!instance.equals(launch.instance)||!"NEW_INTENT".equals(launch.kind))throw new SecurityException("ACTIVITY_NEW_INTENT_ACK_MISMATCH");
        var db=store.getWritableDatabase();db.beginTransaction();try{
            if(success){launches.received(delivery,receiver,instance);ContentValues row=new ContentValues();row.put("state","FINISHED");row.put("taskId",current.taskId);update(launch.candidate,row);}else launches.failed(delivery);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void destroyed(String id,String generation,String instance,boolean finishing,int taskId){
        Ticket ticket=get(id);if(!ticket.generation.equals(generation)||(ticket.instance!=null&&!ticket.instance.equals(instance)&&!ticket.id.equals(ticket.instance)))throw new SecurityException("ACTIVITY_INSTANCE_STALE");
        if(!ticket.state.equals("LIVE")&&!ticket.state.equals("FINISHED"))throw new SecurityException("ACTIVITY_DESTROY_STATE");
        ContentValues row=new ContentValues();row.put("destroyedConfirmed",finishing?1:0);row.put("taskId",taskId);row.put("state",ticket.state.equals("FINISHED")||finishing?"FINISHED":"RECREATING");update(id,row);
    }
    Ticket restore(String id,String stub){
        if(store.recoveryPaused(get(id).generation))throw new IllegalStateException("RECOVERY_TARGET_PAUSED_EXPLICIT_START_REQUIRED");
        requireTaskOpen(id);Ticket ticket=get(id);if(!ticket.stub.equals(stub)||!(ticket.state.equals("LIVE")||ticket.state.equals("RECREATING")||ticket.state.equals("CLAIMED")&&"OWNED_TASK_ATTACHED".equals(ticket.resourceProof)))throw new SecurityException("ACTIVITY_RESTORE_NOT_CONFIRMED");
        store.requireOpen(ticket.generation);if(store.hookRecoveryBlocked(ticket.generation))throw new SecurityException("HOOK_COMPONENT_RECOVERY_REQUIRES_EXPLICIT_START");
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT state FROM sessions WHERE generation=?",new String[]{ticket.generation})){if(!c.moveToFirst()||!c.getString(0).equals("DEAD"))throw new SecurityException("ACTIVITY_PREVIOUS_PROCESS_NOT_DEAD");}
        if(ticket.resultOperation!=null)throw new IllegalStateException("RESULT_RECOVERY_NOT_IMPLEMENTED");
        return ticket;
    }
    /** Read-only construction metadata eligibility. Admission, rebind and
     * delivery acknowledgement remain owned by the subsequent factory claim. */
    Ticket constructionView(String id,String component,String delivery,RuntimeBroker.Slot slot){
        Ticket ticket=get(id);
        if(closedEntry(id,ticket.stub,slot))return null;
        requireScope(ticket,slot);components.requireIdentity(ticket,slot);
        if(!ticket.component.equals(component))throw new SecurityException("ACTIVITY_CONSTRUCTION_COMPONENT_MISMATCH");
        if(!launches.forTicket(id).equals(delivery))throw new SecurityException("ACTIVITY_DELIVERY_TICKET_MISMATCH");
        var launch=launches.get(delivery);if(!launch.candidate.equals(id))throw new SecurityException("ACTIVITY_DELIVERY_TICKET_MISMATCH");
        if(!ticket.generation.equals(slot.allocation.session.processGeneration)){
            if(store.hookRecoveryBlocked(ticket.generation))return null;
            restore(id,ticket.stub);
        }else if(!ticket.state.equals("PREPARED")&&!ticket.state.equals("RECREATING")&&!(slot.mode.routesThroughPlatform()&&ticket.state.equals("LIVE")))
            throw new SecurityException("ACTIVITY_TICKET_NOT_CLAIMABLE:"+ticket.state);
        else if(ticket.state.equals("LIVE")&&(!launch.state.equals("RECEIVED")||!"CREATE".equals(launch.kind)||!id.equals(launch.receiver)||!java.util.Objects.equals(ticket.instance,launch.instance)))
            throw new SecurityException("ACTIVITY_CONSTRUCTION_THIN_RECEIPT_MISMATCH");
        return ticket;
    }
    void rebind(Ticket ticket,RuntimeBroker.Slot slot){requireOwner(ticket,slot);ContentValues row=new ContentValues();row.put("generation",slot.allocation.session.processGeneration);row.put("state","RECREATING");row.putNull("instance");update(ticket.id,row);}
    void resultDelivered(String operation){
        var db=store.getWritableDatabase();db.beginTransaction();try{
            db.execSQL("UPDATE activity_tickets SET state=CASE WHEN state='PREPARED' THEN 'PREPARED' ELSE 'FINISHED' END, resultOperation=CASE WHEN state='PREPARED' THEN NULL ELSE resultOperation END WHERE resultOperation=?",new Object[]{operation});
            db.execSQL("UPDATE activity_launches SET expectsResult=0 WHERE operation=? AND state IN ('PREPARED','ENTERED')",new Object[]{operation});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void controllerResultDelivered(String id){Ticket ticket=get(id);if(ticket.resultOperation!=null)throw new SecurityException("ACTIVITY_RESULT_OWNER_MISMATCH");if(ticket.state.equals("REVOKED")||ticket.state.equals("RETIRED"))throw new SecurityException("ACTIVITY_TICKET_RETIRED");ContentValues row=new ContentValues();row.put("state","FINISHED");update(id,row);}
    /** An unresolved ticket pins this immutable install/process to its original Android stub. */
    String retainedStub(String space,String pkg,String install,String process){
        components.releaseUnused();
        String found=null;try(Cursor c=store.getReadableDatabase().rawQuery("SELECT DISTINCT processStub,installGeneration FROM activity_tickets WHERE space=? AND package=? AND processName=? AND state NOT IN ('FINISHED','REVOKED','RETIRED')",new String[]{space,pkg,process})){
            while(c.moveToNext()){if(!c.getString(1).equals(install))throw new IllegalStateException("ACTIVITY_INSTALL_PIN_CONFLICT");if(found!=null&&!found.equals(c.getString(0)))throw new IllegalStateException("PROCESS_MULTIPLE_STUB_LEASES");found=c.getString(0);}}
        String component=components.retainedStub(space,pkg,install,process);if(found!=null&&component!=null&&!found.equals(component))throw new IllegalStateException("PROCESS_MULTIPLE_STUB_LEASES");return found==null?component:found;
    }
    boolean occupied(String stub){components.releaseUnused();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT ticket FROM activity_tickets WHERE processStub=? AND state NOT IN ('FINISHED','REVOKED','RETIRED') LIMIT 1",new String[]{stub})){if(c.moveToFirst())return true;}return components.occupied(stub);}
    boolean processPinned(String stub){components.releaseUnused();return ActivityProcessPins.pinned(store,stub);}
    boolean processPinnedByOther(String stub,ApkStore.BundleInstall install,String process){components.releaseUnused();return ActivityProcessPins.pinnedByOther(store,stub,install,process);}
    void recover(android.content.Context context){
        launches.recover();
        RuntimeRetiredActivities.reconcile(context,store);
        // RecentTasks can hide a still-live task. Absence never revokes its ticket.
        components.releaseUnused();
    }
    boolean closedEntry(String id,String stub){
        Ticket ticket=get(id);if(!ticket.stub.equals(stub))throw new SecurityException("ACTIVITY_ENTRY_LEASE_MISMATCH");
        if(ActivityEntryFences.closed(store,id)||store.closeRequested(ticket.generation)||java.util.Set.of("FINISHED","REVOKED","RETIRED").contains(ticket.state))return true;
        if(!components.lease(stub).equals(ticket.componentLease))throw new SecurityException("ACTIVITY_ENTRY_LEASE_MISMATCH");return false;
    }
    boolean closedEntry(String id,String stub,RuntimeBroker.Slot slot){
        Ticket ticket=get(id);if(!ticket.processStub.equals(slot.activityStub))throw new SecurityException("ACTIVITY_ENTRY_PROCESS_SLOT_MISMATCH");
        if(closedEntry(id,stub))return true;var i=slot.allocation.session.identity;
        if(!ticket.space.equals(i.spaceId)||!ticket.pkg.equals(i.packageName)||!ticket.install.equals(i.installGeneration)||!ticket.process.equals(i.processName)||!ticket.processStub.equals(slot.activityStub))throw new SecurityException("ACTIVITY_ENTRY_OWNER_MISMATCH");
        return closedEntry(id,stub);
    }
    private void requireTaskOpen(String ticket){if(ActivityEntryFences.closed(store,ticket))throw new SecurityException("ACTIVITY_ENTRY_PERMANENTLY_CLOSED");try(Cursor c=store.getReadableDatabase().rawQuery("SELECT resourceProof FROM activity_tickets WHERE ticket=?",new String[]{ticket})){if(!c.moveToFirst())throw new SecurityException("ACTIVITY_TICKET_MISSING");String proof=c.getString(0);if(proof!=null&&proof.startsWith("TASK_REMOV"))throw new SecurityException("ACTIVITY_TASK_CLOSING");}}
    private void requireScope(Ticket ticket,RuntimeBroker.Slot slot){requireTaskOpen(ticket.id);var i=slot.allocation.session.identity;if(!ticket.space.equals(i.spaceId)||!ticket.pkg.equals(i.packageName)||!ticket.install.equals(i.installGeneration)||!ticket.process.equals(i.processName)||!ticket.processStub.equals(slot.activityStub))throw new SecurityException("ACTIVITY_LEASE_MISMATCH");}
    private void requireOwner(Ticket ticket,RuntimeBroker.Slot slot){requireScope(ticket,slot);components.require(ticket,slot);}
    private Ticket instance(String id,String generation,String instance){Ticket ticket=get(id);if(!ticket.generation.equals(generation)||!instance.equals(ticket.instance))throw new SecurityException("ACTIVITY_INSTANCE_STALE");return ticket;}
    private void update(String ticket,ContentValues row){if(store.getWritableDatabase().update("activity_tickets",row,"ticket=?",new String[]{ticket})!=1)throw new IllegalStateException("ACTIVITY_PERSIST_FAILED");}
}
