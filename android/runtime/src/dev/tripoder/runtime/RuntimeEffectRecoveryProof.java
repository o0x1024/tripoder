package dev.tripoder.runtime;

import android.database.sqlite.SQLiteDatabase;

/** Retires execution admission, never the UNKNOWN business outcome or its original operation id. */
final class RuntimeEffectRecoveryProof {
    static final String ACK="RETIRED_EXECUTION_ACK:";
    static boolean acknowledged(SQLiteDatabase db,String dispatch,String owner){
        if(dispatch==null||!dispatch.startsWith(ACK))return false;
        String admission=dispatch.substring(ACK.length());
        try(var rows=db.rawQuery("SELECT 1 FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.id=? AND o.state IN ('RUNNING','SUCCEEDED') AND s.scope=? AND s.command IN ('prepareApplicationLaunch','prepareLaunch','agentPrepareLaunch')",new String[]{admission,owner})){return rows.moveToFirst();}
    }
    static boolean permitsExplicitStart(SQLiteDatabase db,String operation,String owner){
        return inert(db,operation,owner,false,false);
    }
    static boolean permitsAcknowledgedContinuation(SQLiteDatabase db,String operation,String dispatch,String owner){
        return acknowledged(db,dispatch,owner)&&inert(db,operation,owner,true,true);
    }
    static boolean permitsFreshStart(SQLiteDatabase db,String operation,String owner){return inert(db,operation,owner,false,true);}
    /** A running session's own bookkeeping cannot conflict with a broadcast whose every unfinished delivery targets a dead generation
     *  already fenced at ingress. Without this, a secondary process dying mid-delivery fences the whole package and crashes the healthy one. */
    static boolean permitsLiveSessionContinuation(SQLiteDatabase db,String operation){
        try(var rows=db.rawQuery("SELECT COUNT(*),SUM(CASE WHEN d.resourceRetired=1 AND d.resourceProof='TARGET_DEAD_AND_INGRESS_FENCED' AND s.state='DEAD' AND (d.registration='' OR EXISTS(SELECT 1 FROM broadcast_registrations r WHERE r.id=d.registration AND r.generation=d.target AND r.state='CLOSED')) THEN 1 ELSE 0 END) FROM broadcast_deliveries d LEFT JOIN sessions s ON s.generation=d.target WHERE d.operation=? AND d.state!='COMPLETED'",new String[]{operation})){
            return rows.moveToFirst()&&rows.getInt(0)>0&&rows.getInt(0)==rows.getInt(1);
        }
    }
    private static boolean inert(SQLiteDatabase db,String operation,String owner,boolean admitted,boolean unstarted){
        if(unstarted&&RuntimeStoppedEffectProof.permits(db,operation,owner,admitted))return true;
        if(unstarted&&RuntimeUnstartedEffectProof.permits(db,operation,owner,admitted))return true;
        try(var rows=db.rawQuery("SELECT 1 FROM broadcast_deliveries d JOIN sessions s ON s.generation=d.target WHERE d.operation=? AND d.resourceRetired=1 AND d.resourceProof='TARGET_DEAD_AND_INGRESS_FENCED' AND s.state='DEAD' AND (d.registration='' OR EXISTS(SELECT 1 FROM broadcast_registrations r WHERE r.id=d.registration AND r.generation=d.target AND r.state='CLOSED'))",new String[]{operation})){
            if(rows.moveToFirst())return true;
        }
        // A late Android delivery meets a terminal ticket, and no receiving Java
        // instance survives. Every delivery of this exact operation must be inert.
        try(var rows=db.rawQuery("SELECT COUNT(*),SUM(CASE WHEN (t.state IN ('FINISHED','REVOKED','RETIRED') OR s.state='DEAD') AND s.state='DEAD' THEN 1 ELSE 0 END) FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate JOIN sessions s ON s.generation=t.generation WHERE l.operation=?",new String[]{operation})){
            if(rows.moveToFirst()&&rows.getInt(0)>0&&rows.getInt(0)==rows.getInt(1))return true;
        }
        // Service preparation records a dispatch capability before returning one.
        // If preparation threw before any such record, a later explicit owner stop
        // and confirmed death make that exact attempt inert; the UNKNOWN result stays.
        String liveService=admitted?"":" AND NOT EXISTS(SELECT 1 FROM sessions s WHERE 'space/'||s.space||'/package/'||s.package=own.scope AND s.state NOT IN ('DEAD','FAILED'))";
        try(var rows=db.rawQuery("SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id=? AND original.state='UNKNOWN' AND original.detail='SERVICE_PREPARATION_UNCONFIRMED' AND own.scope=? AND own.command='legacy-or-internal' AND NOT EXISTS(SELECT 1 FROM service_calls c WHERE c.operation=original.id)"+liveService+" AND EXISTS(SELECT 1 FROM operations stopped JOIN operation_scopes ss ON ss.operation=stopped.id WHERE stopped.state='SUCCEEDED' AND ss.scope=own.scope AND ss.command IN ('stopApplication','agentStopApplication') AND stopped.created>=original.updated) LIMIT 1",new String[]{operation,owner})){
            if(rows.moveToFirst())return true;
        }
        // A broadcast route delivery sub-operation that was interrupted before its delivery ticket was recorded.
        // If sibling deliveries of the same broadcast fanout were retired and target session is confirmed dead,
        // no receiver was ever dispatched or can ever execute; the unrecorded attempt is provably inert.
        try(var rows=db.rawQuery("SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id=? AND original.state='UNKNOWN' AND original.detail='BROKER_RECOVERY' AND own.scope=? AND own.command='legacy-or-internal' AND NOT EXISTS(SELECT 1 FROM broadcast_deliveries WHERE operation=original.id)"+liveService+" AND EXISTS(SELECT 1 FROM broadcast_deliveries sibling JOIN sessions s ON s.generation=sibling.target WHERE sibling.resourceRetired=1 AND sibling.resourceProof='TARGET_DEAD_AND_INGRESS_FENCED' AND s.state='DEAD' AND (sibling.operation LIKE substr(original.id,1,37)||'%' OR (instr(original.id,'pending-implicit-')=1 AND sibling.operation LIKE substr(original.id,1,54)||'%'))) LIMIT 1",new String[]{operation,owner})){
            if(rows.moveToFirst())return true;
        }
        // An internal operation marked BROKER_RECOVERY that never recorded any dispatch entry
        // (broadcast, activity, service, provider, job, pending) into the durable tables before broker death.
        // A later authenticated stop proves nothing of it survived.
        String noEffects=" AND NOT EXISTS(SELECT 1 FROM broadcast_deliveries WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM activity_launches WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM service_calls WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM provider_calls WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM job_mutations WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM pending_mutations WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM pending_alarms WHERE operation=original.id)";
        try(var rows=db.rawQuery("SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id=? AND original.state='UNKNOWN' AND original.detail='BROKER_RECOVERY' AND own.scope=? AND own.command='legacy-or-internal' AND own.dispatch!='MAY_HAVE_DISPATCHED'"+noEffects+liveService+" AND EXISTS(SELECT 1 FROM operations stopped JOIN operation_scopes ss ON ss.operation=stopped.id WHERE stopped.state='SUCCEEDED' AND ss.scope=own.scope AND ss.command IN ('stopApplication','agentStopApplication') AND stopped.created>=original.updated) LIMIT 1",new String[]{operation,owner})){
            if(rows.moveToFirst())return true;
        }
        // Failed Application preparation has no entry to close. A later explicit
        // owner stop is positive evidence that all its execution was stopped. Do
        // not infer this from targets=[], a timeout or a connection disappearance.
        // Initial admission requires no live owner. Once that transaction has
        // acknowledged the exact inert attempt, new sessions are expected: they
        // belong to the admitted start and cannot revive the old no-entry attempt.
        String live=admitted?"":" AND NOT EXISTS(SELECT 1 FROM sessions s WHERE 'space/'||s.space||'/package/'||s.package=own.scope AND s.state NOT IN ('DEAD','FAILED'))";
        // BROKER_RECOVERY is the single-authority recovery boundary (an authority died with this launch in flight).
        // A launch marked UNKNOWN that way is inert on exactly the same evidence as LAUNCH_PREPARATION_UNCONFIRMED:
        // this clause already requires NO activity entry for the operation, so the launch never crossed into a
        // delivered Activity, and the stop/dead-session evidence below proves no session of it still runs. Only
        // launch-command scopes are discharged (own.command filter); a BROKER_RECOVERY install/stop stays fenced.
        String prepared="SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id=? AND original.state='UNKNOWN' AND original.detail IN ('LAUNCH_PREPARATION_UNCONFIRMED','BROKER_RECOVERY') AND own.scope=? AND own.command IN ('prepareApplicationLaunch','prepareLaunch','agentPrepareLaunch') AND NOT EXISTS(SELECT 1 FROM activity_launches l WHERE l.operation=original.id)"+live;
        try(var rows=db.rawQuery(prepared+" AND EXISTS(SELECT 1 FROM operations stopped JOIN operation_scopes ss ON ss.operation=stopped.id WHERE stopped.state='SUCCEEDED' AND ss.scope=own.scope AND ss.command IN ('stopApplication','agentStopApplication') AND stopped.created>=original.updated) LIMIT 1",new String[]{operation,owner})){
            if(rows.moveToFirst())return true;
        }
        // The application's own process died during preparation (it crashed, or was killed) and nobody stopped it afterwards.
        // Every launch records its session before the process is bound, so a preparation that ever ran has a session row; one the
        // supervisor saw die by Binder death is DEAD. No live session left, and at least one confirmed dead, means nothing of it
        // still runs. A timeout or a vanished connection never reaches DEAD, so those attempts keep waiting for an explicit stop.
        String owned="'space/'||s.space||'/package/'||s.package=own.scope";
        try(var rows=db.rawQuery(prepared+" AND EXISTS(SELECT 1 FROM sessions s WHERE "+owned+" AND s.state='DEAD') LIMIT 1",new String[]{operation,owner})){
            if(rows.moveToFirst())return true;
        }
        // Hook activation/observation whose process has confirmed death or stop and no live sessions survive.
        String hook="SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id=? AND original.state='UNKNOWN' AND original.detail='HOOK_WORKER_OUTCOME_UNCONFIRMED' AND own.scope=? AND own.command IN ('hook:activate','hook:observe')"+live;
        try(var rows=db.rawQuery(hook+" AND (EXISTS(SELECT 1 FROM sessions s WHERE "+owned+" AND s.state='DEAD') OR EXISTS(SELECT 1 FROM operations stopped JOIN operation_scopes ss ON ss.operation=stopped.id WHERE stopped.state='SUCCEEDED' AND ss.scope=own.scope AND ss.command IN ('stopApplication','agentStopApplication') AND stopped.created>=original.updated)) LIMIT 1",new String[]{operation,owner})){
            return rows.moveToFirst();
        }
    }
    private RuntimeEffectRecoveryProof(){}
}
