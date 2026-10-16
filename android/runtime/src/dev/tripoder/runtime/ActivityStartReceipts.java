package dev.tripoder.runtime;

import android.content.*;
import android.database.Cursor;
import android.os.Bundle;
import java.util.*;

/** Android may complete a start by moving a task, without delivering an Intent. */
final class ActivityStartReceipts {
    private final RuntimeStore store;
    private final Context context;
    private final ActivityLedger activities;
    private final RuntimeBroker broker;
    ActivityStartReceipts(RuntimeStore store,Context context,ActivityLedger activities,RuntimeBroker broker){this.store=store;this.context=context;this.activities=activities;this.broker=broker;}
    void record(Bundle input,RuntimeBroker.Slot source){
        var launch=activities.launches.get(RuntimeWire.required(input,"delivery"));
        var candidate=activities.get(RuntimeWire.required(input,"candidate"));
        if(!"ACTIVITY".equals(launch.callerKind)||!launch.candidate.equals(candidate.id)||!candidate.stub.equals(RuntimeWire.required(input,"stub"))||!launch.generation.equals(candidate.generation))throw new SecurityException("ACTIVITY_START_RESULT_TARGET_MISMATCH");
        var caller=activities.get(launch.sourceTicket);var identity=source.allocation.session;
        // Clear-top may destroy the source before this cross-process receipt arrives.
        // Its accepted immutable instance and authenticated generation still identify it.
        if(!caller.generation.equals(identity.processGeneration)||!launch.sourceInstance.equals(caller.instance)||!caller.space.equals(candidate.space)||!caller.pkg.equals(identity.identity.packageName))throw new SecurityException("ACTIVITY_START_RESULT_SOURCE_MISMATCH");
        int result=input.getInt("startResult",Integer.MIN_VALUE);activities.launches.startResult(launch.id,result);
        if(result==2||result==3){reconcile(candidate.generation);return;}
        if(result!=0&&result!=3){activities.launches.unsupportedStart(launch.id);throw new UnsupportedOperationException("ANDROID_START_RESULT_UNSUPPORTED:"+result);}
        // START_SUCCESS and START_DELIVERED_TO_TOP are not callback receipts.
    }
    void reconcile(String generation){
        List<String> pending=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id FROM activity_launches WHERE generation=? AND startResult IN (2,3) AND state='PREPARED'",new String[]{generation})){while(c.moveToNext())pending.add(c.getString(0));}
        if(pending.isEmpty())return;
        RuntimeTaskReferences observed=RuntimeTaskReferences.observe(context);
        for(String id:pending){
            var launch=activities.launches.get(id);var candidate=activities.get(launch.candidate);
            // Both 2 and 3 describe task disposition, not whether a NewIntent was
            // queued. A standard Activity with SINGLE_TOP must acknowledge its actual
            // onNewIntent. Historical unrecorded flags provide no callback-free proof.
            if(launch.requestFlags==null||(launch.requestFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP)!=0)continue;
            if(RuntimeComponents.activity(broker.slotForReceipt(generation).install,candidate.component).launchMode!=android.content.pm.ActivityInfo.LAUNCH_MULTIPLE)continue;
            ActivityLedger.Ticket root=null;boolean ambiguous=false;
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT * FROM activity_tickets WHERE componentLease=? AND generation=? AND state='LIVE' AND instance IS NOT NULL AND destroyedConfirmed=0 AND taskId>=0",new String[]{candidate.componentLease,generation})){
                while(c.moveToNext()){
                    var current=new ActivityLedger.Ticket(c);Set<String> roots=observed.rootTickets.get(current.taskId),retained=observed.retainedByTask.get(current.taskId);
                    if(!observed.owned.contains(current.taskId)||roots==null||!roots.contains(current.id)||retained==null||!retained.contains(new ComponentName(context,current.stub).flattenToString()))continue;
                    if(root!=null){ambiguous=true;break;}root=current;
                }
            }
            // A missing or ambiguous root is unresolved. Never select a task by guesswork.
            if(root!=null&&!ambiguous)activities.launches.taskToFront(id,root);
        }
    }
}
