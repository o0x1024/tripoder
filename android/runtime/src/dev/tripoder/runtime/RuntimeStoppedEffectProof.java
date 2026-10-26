package dev.tripoder.runtime;

import android.database.sqlite.SQLiteDatabase;

/** Only an authenticated outer stop executor may be retired; its business UNKNOWN stays. */
final class RuntimeStoppedEffectProof {
    static boolean permits(SQLiteDatabase db,String operation,String owner,boolean continuation){
        // Dispatch by the persisted role before reading stop-specific evidence.
        try(var rows=db.rawQuery("SELECT 1 FROM operation_scopes WHERE operation=? AND scope=? AND command IN ('stopApplication','agentStopApplication')",new String[]{operation,owner})){
            if(!rows.moveToFirst())return false;
        }
        String recordedDigest;long unknownAt;
        try(var rows=db.rawQuery("SELECT o.digest,o.updated FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.id=? AND o.state='UNKNOWN' AND o.caller=? AND o.detail IN ('BROKER_RECOVERY','STOP_UNCONFIRMED:PROCESS_STOP_UNCONFIRMED','STOP_UNCONFIRMED:ISOLATED_STOP_BEFORE_ATTACH_UNKNOWN') AND s.scope=? AND s.command IN ('stopApplication','agentStopApplication')",new String[]{operation,RuntimeControllerOperationIdentity.CALLER,owner})){
            if(!rows.moveToFirst())return false;recordedDigest=rows.getString(0);unknownAt=rows.getLong(1);
        }
        String space,pkg,generation;
        try(var rows=db.rawQuery("SELECT space,package,generation FROM installs WHERE state='COMMITTED' AND 'space/'||space||'/package/'||package=? LIMIT 2",new String[]{owner})){
            if(!rows.moveToFirst())return false;space=rows.getString(0);pkg=rows.getString(1);generation=rows.getString(2);if(rows.moveToNext())return false;
        }
        if(!space.matches("[a-zA-Z0-9_-]{1,160}")||!pkg.matches("[a-zA-Z0-9_.]+")||!owner.equals("space/"+space+"/package/"+pkg))return false;
        final String digest;
        try{digest=RuntimeControllerOperationIdentity.stopDigest(space,pkg,generation);}
        catch(Exception error){throw new IllegalStateException("STOP_PROOF_IDENTITY_UNAVAILABLE",error);}
        if(!digest.equals(recordedDigest))return false;
        // Before the first ACK every old generation must have confirmed death.
        // Later live generations belong to the admitted start. The persistent
        // close marker still independently fences every queued old STOP target.
        try(var rows=db.rawQuery("SELECT COUNT(*),SUM(CASE WHEN state='DEAD' THEN 1 ELSE 0 END),SUM(CASE WHEN closeRequested=1 AND state='DEAD' THEN 1 ELSE 0 END),SUM(CASE WHEN closeRequested=1 AND state!='DEAD' THEN 1 ELSE 0 END) FROM sessions WHERE space=? AND package=? AND installGeneration=?",new String[]{space,pkg,generation})){
            if(!rows.moveToFirst()||rows.getInt(0)==0||rows.getInt(2)==0||rows.getInt(3)!=0||!continuation&&rows.getInt(0)!=rows.getInt(1))return false;
        }
        // BROKER_RECOVERY is the existing single-authority recovery boundary.
        // The exact STOP_UNCONFIRMED phase is written only by the outer close
        // catch: it cannot run task/job cleanup after committing that UNKNOWN.
        // An isolated child whose bootstrap was never consumed (older authorities recorded that as ISOLATED_STOP_BEFORE_ATTACH_UNKNOWN) is
        // covered by the same evidence: every session of the installation must already be DEAD, so none can still be running.
        // A later successful explicit owner stop supplies fresh resource closure.
        // Child journal UNKNOWNs are deliberately NOT discharged by this proof.
        try(var rows=db.rawQuery("SELECT 1 FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.state='SUCCEEDED' AND o.caller=? AND o.digest=? AND o.created>=? AND s.scope=? AND s.command IN ('stopApplication','agentStopApplication') LIMIT 1",new String[]{RuntimeControllerOperationIdentity.CALLER,digest,Long.toString(unknownAt),owner})){
            return rows.moveToFirst();
        }
    }
    private RuntimeStoppedEffectProof(){}
}
