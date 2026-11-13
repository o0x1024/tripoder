package dev.tripoder.runtime;

import android.database.sqlite.SQLiteDatabase;

/** Retires execution admission, never the UNKNOWN business outcome or its original operation id. */
final class RuntimeEffectRecoveryProof {
    static final String ACK="RETIRED_EXECUTION_ACK:";
    /** Recorded by RuntimeBroker when an Activity route failed before anything was delivered for it. */
    static final String COMPONENT_PREPARATION_UNCONFIRMED="COMPONENT_PREPARATION_UNCONFIRMED";
    /** Every detail a launch-scope UNKNOWN may carry. A writer that records a new one must add it here, or its row
     *  can never be discharged and fences its package until the application or the space is deleted. */
    /** Details only the launch path can record: they describe a preparation that never delivered anything, so they are
     *  discharged on delivery evidence alone. The recorded command is NOT consulted: an operation accepted outside a
     *  command guard (a guest-initiated route) carries the placeholder legacy-or-internal, which no command filter
     *  names, and consulting it would fence that package forever. */
    private static final String LAUNCH_ONLY_DETAILS="'LAUNCH_PREPARATION_UNCONFIRMED','"+COMPONENT_PREPARATION_UNCONFIRMED+"','ACTIVITY_DELIVERY_PROCESS_DIED','ACTIVITY_DELIVERY_RECOVERED_UNKNOWN'";
    /** Ambiguous detail: BROKER_RECOVERY can also mark an install/stop, so it stays gated on a launch command. */
    private static final String AMBIGUOUS_DETAILS="'BROKER_RECOVERY'";
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
        String noPendingEffects=" AND NOT EXISTS(SELECT 1 FROM broadcast_deliveries WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM activity_launches WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM service_calls WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM provider_calls WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM job_mutations WHERE operation=original.id) AND NOT EXISTS(SELECT 1 FROM pending_alarms WHERE operation=original.id)";
        String noEffects=noPendingEffects+" AND NOT EXISTS(SELECT 1 FROM pending_mutations WHERE operation=original.id)";
        try(var rows=db.rawQuery("SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id=? AND original.state='UNKNOWN' AND original.detail='BROKER_RECOVERY' AND own.scope=? AND own.command='legacy-or-internal' AND own.dispatch!='MAY_HAVE_DISPATCHED'"+noEffects+liveService+" AND EXISTS(SELECT 1 FROM operations stopped JOIN operation_scopes ss ON ss.operation=stopped.id WHERE stopped.state='SUCCEEDED' AND ss.scope=own.scope AND ss.command IN ('stopApplication','agentStopApplication') AND stopped.created>=original.updated) LIMIT 1",new String[]{operation,owner})){
            if(rows.moveToFirst())return true;
        }
        // PendingIntent.get* creates a capability, not an application delivery. If authority recovery
        // interrupted that exact GET, a later same-install owner stop and a fully fenced token ledger
        // prove that neither an old process nor a returned token can still dispatch it. SEND/CANCEL,
        // alarms and every other effect remain fenced; the original UNKNOWN outcome is not rewritten.
        // Admission closes the proven GET execution in the same transaction. Both acknowledged
        // continuations and a later fresh start re-check the durable evidence; closed only records
        // execution ownership and never changes the UNKNOWN business outcome or weakens the proof.
        // Once admitted, newly created tokens belong to the acknowledged start and cannot revoke the
        // old proof. A later fresh start again requires every token fenced before it can proceed.
        String liveTokens=admitted?"":" AND NOT EXISTS(SELECT 1 FROM pending_intents token WHERE token.installGeneration=mutation.installGeneration AND token.fenced=0)";
        String pendingOnly="SELECT original.updated,mutation.installGeneration,i.space,i.package FROM operations original JOIN operation_scopes own ON own.operation=original.id JOIN pending_mutations mutation ON mutation.operation=original.id JOIN installs i ON i.generation=mutation.installGeneration AND i.state='COMMITTED' WHERE original.id=? AND original.state='UNKNOWN' AND original.detail='BROKER_RECOVERY' AND own.scope=? AND own.command='legacy-or-internal' AND mutation.kind='GET' AND 'space/'||i.space||'/package/'||i.package=own.scope"+noPendingEffects+liveService+liveTokens+" LIMIT 1";
        try(var rows=db.rawQuery(pendingOnly,new String[]{operation,owner})){
            if(rows.moveToFirst()){
                long unknownAt=rows.getLong(0);String install=rows.getString(1),space=rows.getString(2),pkg=rows.getString(3);
                final String digest;
                try{digest=RuntimeControllerOperationIdentity.stopDigest(space,pkg,install);}
                catch(Exception failure){throw new IllegalStateException("STOP_PROOF_IDENTITY_UNAVAILABLE",failure);}
                try(var stopped=db.rawQuery("SELECT 1 FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.state='SUCCEEDED' AND o.caller=? AND o.digest=? AND o.created>=? AND s.scope=? AND s.command IN ('stopApplication','agentStopApplication') LIMIT 1",new String[]{RuntimeControllerOperationIdentity.CALLER,digest,Long.toString(unknownAt),owner})){
                    if(stopped.moveToFirst())return true;
                }
            }
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
        // this clause already requires every activity entry of the operation to be provably inert, so the launch
        // never crossed into a delivered Activity, and the stop/dead-session evidence below proves no session of
        // it still runs. Only launch-command scopes are discharged (own.command filter); a BROKER_RECOVERY
        // install/stop stays fenced. An entry is inert when it was explicitly fenced out, or when it never
        // attached a task nor an instance, never left PREPARED/CLAIMED/REVOKED/RETIRED, and its session is
        // DEAD/FAILED — the same proof the stale-entry retirement uses. An attached task, an entered ticket or a
        // possibly-live session keeps the fence: the business effect may have happened.
        String inertEntries=" AND NOT EXISTS(SELECT 1 FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate LEFT JOIN sessions s ON s.generation=t.generation WHERE l.operation=original.id AND NOT (t.entryFenceOperation IS NOT NULL OR t.taskId<0 AND t.instance IS NULL AND t.state IN ('PREPARED','CLAIMED','REVOKED','RETIRED') AND s.state IN ('DEAD','FAILED')))";
        String prepared="SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id=? AND original.state='UNKNOWN' AND own.scope=? AND (original.detail IN ("+LAUNCH_ONLY_DETAILS+") OR original.detail IN ("+AMBIGUOUS_DETAILS+") AND own.command IN ('prepareApplicationLaunch','prepareLaunch','agentPrepareLaunch'))"+inertEntries+live;
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
        // The complete process-local Hook plan lifecycle dies with its worker,
        // including a lost disable ACK. Admit a fresh owner only on the same
        // confirmed death/stop proof, retaining UNKNOWN and the original evidence.
        // Do not include componentSend, guestInvoke, HTTP or network capture: their
        // externally delivered effects are not proved inert by worker death.
        String hook="SELECT 1 FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id=? AND original.state='UNKNOWN' AND original.detail='HOOK_WORKER_OUTCOME_UNCONFIRMED' AND own.scope=? AND own.command IN ('hook:activate','hook:observe','hook:prepare','hook:disable','hook:updateParameters','hook:cancelPreparation','hook:discard')"+live;
        try(var rows=db.rawQuery(hook+" AND (EXISTS(SELECT 1 FROM sessions s WHERE "+owned+" AND s.state='DEAD') OR EXISTS(SELECT 1 FROM operations stopped JOIN operation_scopes ss ON ss.operation=stopped.id WHERE stopped.state='SUCCEEDED' AND ss.scope=own.scope AND ss.command IN ('stopApplication','agentStopApplication') AND stopped.created>=original.updated)) LIMIT 1",new String[]{operation,owner})){
            return rows.moveToFirst();
        }
    }
    private RuntimeEffectRecoveryProof(){}
}
