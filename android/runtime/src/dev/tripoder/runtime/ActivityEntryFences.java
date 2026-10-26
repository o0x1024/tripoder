package dev.tripoder.runtime;

import android.content.ContentValues;
import android.database.Cursor;

/** Entry admission and physical resource ownership are independent durable facts. */
final class ActivityEntryFences {
    static final String REJECTED="ACTIVITY_ENTRY_PERMANENTLY_CLOSED";
    static void rejected(RuntimeBroker broker,String ticket,String kind){
        try{broker.events.put(new org.json.JSONObject().put("stage","closed-activity-entry-rejected").put("ticket",ticket).put("kind",kind).put("time",System.currentTimeMillis()));}
        catch(org.json.JSONException error){throw new IllegalStateException("ENTRY_REJECTION_DIAGNOSTIC_FAILED",error);}
        android.util.Log.i("TripoderRuntime",REJECTED+":"+kind+":"+ticket);
    }
    static boolean closed(RuntimeStore store,String ticket){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT entryFenceOperation FROM activity_tickets WHERE ticket=?",new String[]{ticket})){
            if(!c.moveToFirst())throw new SecurityException("ACTIVITY_TICKET_MISSING");return !c.isNull(0);
        }
    }
    static void requireNoOpenRoot(RuntimeStore store,String space,String pkg,String install){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT ticket,state,taskId,generation,component FROM activity_tickets WHERE space=? AND package=? AND installGeneration=? AND entryFenceOperation IS NULL AND state NOT IN ('FINISHED','REVOKED','RETIRED') LIMIT 1",new String[]{space,pkg,install})){
            if(c.moveToFirst()){android.util.Log.e("TripoderLaunch","OPEN_ROOT ticket="+c.getString(0)+" state="+c.getString(1)+" task="+c.getInt(2)+" generation="+c.getString(3)+" component="+c.getString(4));throw new IllegalStateException("APPLICATION_ACTIVITY_ALREADY_OPEN_OR_UNCONFIRMED");}
        }
    }
    /** A workbench launch's root: the delivery record names the operation that created it. Caller holds authority and its transaction. */
    static void closeController(RuntimeBroker broker,String candidate,String launchOperation,String operation){
        var ticket=broker.activities.get(candidate);var launch=broker.activities.launches.get(broker.activities.launches.forTicket(candidate));
        if(!launchOperation.equals(launch.operation)||!"CONTROLLER".equals(launch.callerKind)||!ticket.generation.equals(launch.generation))throw new SecurityException("LAUNCH_ENTRY_IDENTITY_MISMATCH");
        closeUnattached(broker,ticket,launchOperation,operation);
    }
    /** The root ticket a workbench launch created, or null when the launch never reached ticket creation. */
    static String controllerTicket(RuntimeStore store,String launchOperation){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT candidate FROM activity_launches WHERE operation=? AND callerKind='CONTROLLER'",new String[]{launchOperation})){
            if(!c.moveToFirst())return null;String ticket=c.getString(0);if(c.moveToNext())throw new SecurityException("LAUNCH_ENTRY_AMBIGUOUS");return ticket;
        }
    }
    /** An admitted guest-to-guest route owns its own candidate. */
    static void closeRoute(RuntimeBroker broker,String candidate,String launchOperation,String operation){
        var ticket=broker.activities.get(candidate);var launch=broker.activities.launches.get(broker.activities.launches.forTicket(candidate));
        if(!launchOperation.equals(launch.operation)||launch.payloadDigest==null||!"UNKNOWN".equals(launch.state)||!ticket.generation.equals(launch.generation))throw new SecurityException("ACTIVITY_ROUTE_ENTRY_IDENTITY_MISMATCH");
        if("ACTIVITY".equals(launch.callerKind)){
            if(launch.sourceTicket==null||launch.sourceInstance==null)throw new SecurityException("ACTIVITY_ROUTE_ENTRY_IDENTITY_MISMATCH");var source=broker.activities.get(launch.sourceTicket);
            if(!source.space.equals(ticket.space)||!source.pkg.equals(launch.callerPackage))throw new SecurityException("ACTIVITY_ROUTE_ENTRY_SOURCE_MISMATCH");
        }else if("CONTEXT".equals(launch.callerKind))ActivityContextSource.requireRecorded(broker,launch,ticket);
        else throw new SecurityException("ACTIVITY_ROUTE_ENTRY_IDENTITY_MISMATCH");
        closeUnattached(broker,ticket,launchOperation,operation);
    }
    private static void closeUnattached(RuntimeBroker broker,ActivityLedger.Ticket ticket,String launchOperation,String operation){
        if(ticket.taskId>=0||!("PREPARED".equals(ticket.state)||"CLAIMED".equals(ticket.state)))throw new IllegalStateException("LAUNCH_ENTRY_NOT_UNATTACHED");
        if(broker.contract.query(launchOperation).state!=ExecutionContract.OperationState.UNKNOWN)throw new IllegalStateException("LAUNCH_ENTRY_OUTCOME_NOT_UNKNOWN");
        var db=broker.store.getWritableDatabase();
        try(Cursor c=db.rawQuery("SELECT state FROM sessions WHERE generation=?",new String[]{ticket.generation})){if(!c.moveToFirst()||!"DEAD".equals(c.getString(0)))throw new IllegalStateException("LAUNCH_ENTRY_PROCESS_NOT_DEAD");}
        try(Cursor c=db.rawQuery("SELECT 1 FROM sessions WHERE space=? AND package=? AND installGeneration=? AND processName=? AND state NOT IN ('DEAD','FAILED') LIMIT 1",new String[]{ticket.space,ticket.pkg,ticket.install,ticket.process})){if(c.moveToFirst())throw new IllegalStateException("LAUNCH_ENTRY_REPLACEMENT_PROCESS_ACTIVE");}
        ContentValues fence=new ContentValues();fence.put("entryFenceOperation",operation);
        if(db.update("activity_tickets",fence,"ticket=? AND entryFenceOperation IS NULL",new String[]{ticket.id})!=1)throw new IllegalStateException("LAUNCH_ENTRY_ALREADY_CLOSED");
    }
    private ActivityEntryFences(){}
}
