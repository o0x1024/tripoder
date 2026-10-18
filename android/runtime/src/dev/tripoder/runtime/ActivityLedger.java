package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.UUID;

/** Durable Android task references. A dead process does not make its stub available. */
final class ActivityLedger {
    private final RuntimeStore store;
    private final ActivityComponentLedger components;
    final ActivityLaunchLedger launches;
    ActivityLedger(RuntimeStore store,ExecutionContract contract,ActivityComponentLedger components){this.store=store;this.components=components;launches=new ActivityLaunchLedger(store,contract);}
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
    /** Observe Android before opening a transaction; associate ingress metadata in the ticket commit. */
    String prepareWithCommit(RuntimeBroker.Slot slot,String component,String resultOperation,String launchOperation,String callerPackage,String payloadDigest,Ticket source,java.util.function.Consumer<String> associate){
        return prepareWithCommit(slot,component,resultOperation,launchOperation,callerPackage,payloadDigest,source,0,associate);
    }
    private String prepareWithCommit(RuntimeBroker.Slot slot,String component,String resultOperation,String launchOperation,String callerPackage,String payloadDigest,Ticket source,int flags,java.util.function.Consumer<String> associate){
        String ticket=UUID.randomUUID().toString();var identity=slot.allocation.session.identity;
        components.releaseUnused();
        var db=store.getWritableDatabase();db.beginTransaction();try{
            String stub=components.acquire(slot,component);
            ContentValues row=new ContentValues();row.put("ticket",ticket);row.put("space",identity.spaceId);row.put("package",identity.packageName);row.put("installGeneration",identity.installGeneration);row.put("processName",identity.processName);row.put("stub",stub);row.put("processStub",slot.activityStub);row.put("component",component);row.put("generation",slot.allocation.session.processGeneration);row.put("state","PREPARED");row.put("taskId",-1);row.put("resultOperation",resultOperation);
            row.put("componentLease",components.lease(stub));db.insertOrThrow("activity_tickets",null,row);launches.prepare(ticket,identity.processGeneration,launchOperation,resultOperation!=null,callerPackage,payloadDigest,source,flags);associate.accept(ticket);db.setTransactionSuccessful();return ticket;
        }finally{db.endTransaction();}
    }
    void releaseUnused(){components.releaseUnused();}
    /** The physical start for a controller-created root: a launcher-style entry whose stub roots a fresh task the platform then owns. */
    android.content.Intent controllerEntry(android.content.Context host,String id,boolean launcher){
        Ticket ticket=get(id);android.content.Intent intent=new android.content.Intent().setComponent(new android.content.ComponentName(host,ticket.stub)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra("tripoder.component",ticket.component).putExtra("tripoder.generation",ticket.generation).putExtra("tripoder.ticket",id).putExtra("tripoder.delivery",launches.forTicket(id));
        if(launcher)intent.setAction(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);return intent;
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
    Ticket get(String id){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM activity_tickets WHERE ticket=?",new String[]{id})){if(!c.moveToFirst())throw new SecurityException("ACTIVITY_TICKET_MISSING");return new Ticket(c);}}
    void caller(String delivery,android.os.Bundle out){var launch=launches.get(delivery);launch.caller(out);if("ACTIVITY".equals(launch.callerKind))out.putString("callerPhysical",get(launch.sourceTicket).stub);}
    Ticket source(String id,String instance,RuntimeBroker.Slot slot,int taskId){
        Ticket ticket=instance(id,slot.allocation.session.processGeneration,instance);requireOwner(ticket,slot);
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
        var db=store.getWritableDatabase();db.beginTransaction();try{if(ticket.state.equals("PREPARED"))launches.enter(delivery,ticket,ticket,instance,"CREATE",payloadDigest);
            ContentValues row=new ContentValues();row.put("state","CLAIMED");row.put("instance",instance);row.put("destroyedConfirmed",0);update(id,row);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void attached(String id,String generation,String instance,int taskId){Ticket ticket=instance(id,generation,instance);if(!ticket.state.equals("CLAIMED"))throw new SecurityException("ACTIVITY_ATTACH_STATE");components.requireOwnTask(taskId);ContentValues row=new ContentValues();row.put("taskId",taskId);row.put("resourceProof","OWNED_TASK_ATTACHED");update(id,row);}
    void created(String id,String generation,String instance,int taskId){
        Ticket ticket=instance(id,generation,instance);if((!ticket.state.equals("CLAIMED")&&!ticket.state.equals("FINISHED"))||taskId<0)throw new SecurityException("ACTIVITY_CREATE_STATE");ContentValues row=new ContentValues();row.put("state",ticket.state.equals("FINISHED")?"FINISHED":"LIVE");row.put("taskId",taskId);update(id,row);
        String delivery=launches.forTicket(id);if(launches.get(delivery).state.equals("ENTERED"))launches.received(delivery,id,instance);
    }
    void newIntent(String receiver,String instance,RuntimeBroker.Slot slot,String candidate,String delivery,String stub,String payloadDigest){
        Ticket current=instance(receiver,slot.allocation.session.processGeneration,instance),incoming=get(candidate);requireOwner(current,slot);requireOwner(incoming,slot);
        if(!current.state.equals("LIVE")||!incoming.state.equals("PREPARED")||!incoming.stub.equals(stub)||!current.componentLease.equals(incoming.componentLease)||!incoming.generation.equals(current.generation)||incoming.resultOperation!=null)throw new SecurityException("ACTIVITY_NEW_INTENT_MISMATCH");
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
        Ticket ticket=instance(id,generation,instance);
        if(!ticket.state.equals("LIVE")&&!ticket.state.equals("FINISHED"))throw new SecurityException("ACTIVITY_DESTROY_STATE");
        ContentValues row=new ContentValues();row.put("destroyedConfirmed",finishing?1:0);row.put("taskId",taskId);row.put("state",ticket.state.equals("FINISHED")||finishing?"FINISHED":"RECREATING");update(id,row);
    }
    Ticket restore(String id,String stub){
        requireTaskOpen(id);Ticket ticket=get(id);if(!ticket.stub.equals(stub)||!(ticket.state.equals("LIVE")||ticket.state.equals("RECREATING")||ticket.state.equals("CLAIMED")&&"OWNED_TASK_ATTACHED".equals(ticket.resourceProof)))throw new SecurityException("ACTIVITY_RESTORE_NOT_CONFIRMED");
        store.requireOpen(ticket.generation);if(store.hookRecoveryBlocked(ticket.generation))throw new SecurityException("HOOK_COMPONENT_RECOVERY_REQUIRES_EXPLICIT_START");
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT state FROM sessions WHERE generation=?",new String[]{ticket.generation})){if(!c.moveToFirst()||!c.getString(0).equals("DEAD"))throw new SecurityException("ACTIVITY_PREVIOUS_PROCESS_NOT_DEAD");}
        if(ticket.resultOperation!=null)throw new IllegalStateException("RESULT_RECOVERY_NOT_IMPLEMENTED");
        return ticket;
    }
    void rebind(Ticket ticket,RuntimeBroker.Slot slot){requireOwner(ticket,slot);ContentValues row=new ContentValues();row.put("generation",slot.allocation.session.processGeneration);row.put("state","RECREATING");row.putNull("instance");update(ticket.id,row);}
    void resultDelivered(String operation){ContentValues row=new ContentValues();row.put("state","FINISHED");store.getWritableDatabase().update("activity_tickets",row,"resultOperation=?",new String[]{operation});}
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
    private void requireOwner(Ticket ticket,RuntimeBroker.Slot slot){requireTaskOpen(ticket.id);var i=slot.allocation.session.identity;if(!ticket.space.equals(i.spaceId)||!ticket.pkg.equals(i.packageName)||!ticket.install.equals(i.installGeneration)||!ticket.process.equals(i.processName)||!ticket.processStub.equals(slot.activityStub))throw new SecurityException("ACTIVITY_LEASE_MISMATCH");components.require(ticket,slot);}
    private Ticket instance(String id,String generation,String instance){Ticket ticket=get(id);if(!ticket.generation.equals(generation)||!instance.equals(ticket.instance))throw new SecurityException("ACTIVITY_INSTANCE_STALE");return ticket;}
    private void update(String ticket,ContentValues row){if(store.getWritableDatabase().update("activity_tickets",row,"ticket=?",new String[]{ticket})!=1)throw new IllegalStateException("ACTIVITY_PERSIST_FAILED");}
}
