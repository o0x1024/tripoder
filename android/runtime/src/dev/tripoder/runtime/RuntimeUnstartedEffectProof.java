package dev.tripoder.runtime;

import android.database.sqlite.SQLiteDatabase;

/** A persisted pre-START fact retires execution, while its business outcome stays UNKNOWN. */
final class RuntimeUnstartedEffectProof {
    static boolean permits(SQLiteDatabase db,String operation,String owner,boolean continuation){
        String caller,dispatch;long unknownAt;
        try(var rows=db.rawQuery("SELECT o.caller,o.updated,s.dispatch FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.id=? AND o.state='UNKNOWN' AND o.detail='BROKER_RECOVERY' AND s.scope=? AND s.command='legacy-or-internal'",new String[]{operation,owner})){
            if(!rows.moveToFirst())return false;caller=rows.getString(0);unknownAt=rows.getLong(1);dispatch=rows.getString(2);
        }
        String[] identity=RuntimeSessionIdentity.parse(caller);if(identity==null||!owner.equals("space/"+identity[0]+"/package/"+identity[1]))return false;
        // ACK is a durable proof that the old execution was inert at admission.
        // That fact survives failure of the new start, but failure never grants
        // continuation; its active-owner check is performed by the caller.
        if(!"NOT_DISPATCHED".equals(dispatch)&&!historicAck(db,dispatch,owner))return false;
        try(var rows=db.rawQuery("SELECT 1 FROM sessions s JOIN installs i ON i.generation=s.installGeneration AND i.space=s.space AND i.package=s.package WHERE s.space=? AND s.package=? AND s.installGeneration=? AND s.processName=? AND s.generation=? AND s.state='DEAD' AND i.state='COMMITTED'",identity)){
            if(!rows.moveToFirst()||rows.moveToNext())return false;
        }
        if(!continuation)try(var rows=db.rawQuery("SELECT 1 FROM sessions WHERE space=? AND package=? AND state!='DEAD' LIMIT 1",new String[]{identity[0],identity[1]})){if(rows.moveToFirst())return false;}
        try(var rows=db.rawQuery("SELECT 1 FROM broadcast_registrations WHERE generation=? AND state!='CLOSED' LIMIT 1",new String[]{identity[4]})){if(rows.moveToFirst())return false;}
        // A contradiction to pre-START must remain fenced. Each session caller
        // path commits START before creating/returning any of these admissions.
        for(String table:new String[]{"activity_launches","broadcast_deliveries","service_calls","provider_calls","job_mutations","pending_mutations","pending_alarms"}){
            try(var rows=db.rawQuery("SELECT 1 FROM "+table+" WHERE operation=? LIMIT 1",new String[]{operation})){if(rows.moveToFirst())return false;}
        }
        final String digest;
        try{digest=RuntimeControllerOperationIdentity.stopDigest(identity[0],identity[1],identity[2]);}
        catch(Exception failure){throw new IllegalStateException("STOP_PROOF_IDENTITY_UNAVAILABLE",failure);}
        try(var rows=db.rawQuery("SELECT 1 FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.state='SUCCEEDED' AND o.caller=? AND o.digest=? AND o.created>=? AND s.scope=? AND s.command IN ('stopApplication','agentStopApplication') LIMIT 1",new String[]{RuntimeControllerOperationIdentity.CALLER,digest,Long.toString(unknownAt),owner})){
            return rows.moveToFirst();
        }
    }
    private static boolean historicAck(SQLiteDatabase db,String dispatch,String owner){
        if(dispatch==null||!dispatch.startsWith(RuntimeEffectRecoveryProof.ACK))return false;
        String admission=dispatch.substring(RuntimeEffectRecoveryProof.ACK.length());
        try(var rows=db.rawQuery("SELECT 1 FROM operations o JOIN operation_scopes s ON s.operation=o.id WHERE o.id=? AND o.caller=? AND s.scope=? AND s.command IN ('prepareApplicationLaunch','prepareLaunch','agentPrepareLaunch')",new String[]{admission,RuntimeControllerOperationIdentity.CALLER,owner})){return rows.moveToFirst();}
    }
    private RuntimeUnstartedEffectProof(){}
}
