package dev.tripoder.runtime;

import android.content.Intent;

/** Context starts carry a process identity, never an Activity/result capability. */
final class ActivityContextSource {
    static void requireShape(ActivityLaunchLedger.Launch launch){
        if(!"CONTEXT".equals(launch.callerKind)||launch.sourceTicket!=null||launch.sourceInstance!=null||launch.callerComponent!=null||launch.expectsResult||launch.requestFlags==null||(launch.requestFlags&Intent.FLAG_ACTIVITY_NEW_TASK)==0||launch.operation==null||launch.payloadDigest==null)throw new SecurityException("ACTIVITY_START_RESULT_CONTEXT_MISMATCH");
    }
    static void requireRecorded(RuntimeBroker broker,ActivityLaunchLedger.Launch launch,ActivityLedger.Ticket target){
        requireShape(launch);var operation=broker.contract.query(launch.operation);String[] owner=operation==null?null:RuntimeSessionIdentity.parse(operation.callerIdentity);
        if(owner==null||!target.space.equals(owner[0])||!launch.callerPackage.equals(owner[1]))throw new SecurityException("ACTIVITY_CONTEXT_SOURCE_JOURNAL_MISMATCH");
        try(var rows=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE space=? AND package=? AND installGeneration=? AND processName=? AND generation=?",owner)){
            if(!rows.moveToFirst()||rows.moveToNext())throw new SecurityException("ACTIVITY_CONTEXT_SOURCE_JOURNAL_MISMATCH");
        }
    }
    private ActivityContextSource(){}
}
