package dev.tripoder.runtime;

import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.Set;

/** Explicitly ends a failed entry's admission, retaining its UNKNOWN outcome and resource pin. */
final class RuntimeControllerEntryClosures {
    static Bundle recovery(RuntimeBroker broker,Bundle input)throws Exception{
        if(!input.containsKey("project"))return preparationRecovery(broker,input);
        RuntimeControllerCommands.targetFields(input,"command","project","operation");String operation=ControlWire.required(input,"operation"),project=ControlWire.required(input,"project");RuntimeFiles.id(operation);
        synchronized(broker){
            String root=ActivityEntryFences.controllerTicket(broker.store,operation);var original=broker.contract.query(operation);
            Bundle target=input.containsKey("bindingOperation")?new ProjectTargetBindings(broker).resolveBinding(project,input.getString("bindingOperation")):new ProjectTargetBindings(broker).resolve(project);
            if(root==null){
                String scope=null;
                try(var row=broker.store.getReadableDatabase().rawQuery("SELECT scope FROM operation_scopes WHERE operation=?",new String[]{operation})){if(row.moveToFirst())scope=row.getString(0);}
                RuntimeRecoveryIdentity.requireScope(scope,target.getString("space"),target.getString("package"));
                Bundle proof=preparationRecovery(broker,ControlWire.request("launchRecovery","operation",operation));
                // The old no-entry journal does not record an installation
                // generation. Matching ownership scope is not generation proof.
                proof.putBoolean("targetVerified",false);proof.putString("targetVerification","SCOPE_ONLY_ORIGINAL_INSTALLATION_IDENTITY_REQUIRED");return proof;
            }
            if(original==null||!ControllerTrust.OPERATIONS.equals(original.callerIdentity)||original.state!=ExecutionContract.OperationState.UNKNOWN)throw new SecurityException("UNKNOWN_PROJECT_LAUNCH_REQUIRED");
            var ticket=broker.activities.get(root);
            if(!ticket.space.equals(target.getString("space"))||!ticket.pkg.equals(target.getString("package"))||!ticket.install.equals(target.getString("generation")))throw new SecurityException("LAUNCH_RECOVERY_BINDING_STALE");boolean inert=Set.of("FINISHED","REVOKED","RETIRED").contains(ticket.state)||ActivityEntryFences.closed(broker.store,ticket.id);
            int open,live;var db=broker.store.getReadableDatabase();
            try(var c=db.rawQuery("SELECT COUNT(*) FROM activity_tickets WHERE space=? AND package=? AND installGeneration=? AND entryFenceOperation IS NULL AND state NOT IN ('FINISHED','REVOKED','RETIRED')",new String[]{ticket.space,ticket.pkg,ticket.install})){if(!c.moveToFirst())throw new IllegalStateException("LAUNCH_RECOVERY_COUNT_MISSING");open=c.getInt(0);}
            try(var c=db.rawQuery("SELECT COUNT(*) FROM sessions WHERE space=? AND package=? AND installGeneration=? AND state NOT IN ('DEAD','FAILED')",new String[]{ticket.space,ticket.pkg,ticket.install})){if(!c.moveToFirst())throw new IllegalStateException("LAUNCH_RECOVERY_COUNT_MISSING");live=c.getInt(0);}
            target.putString("operation",operation);target.putString("state","UNKNOWN");target.putString("detail",original.detail);target.putString("ticket",ticket.id);target.putString("ticketState",ticket.state);target.putBoolean("entryInert",inert);target.putInt("openEntries",open);target.putInt("liveProcesses",live);target.putBoolean("canAcknowledge",inert&&open==0&&live==0);return target;
        }
    }
    /** No root ticket means nothing can enter the guest. Preserve UNKNOWN because
     * process preparation may still have performed application side effects. */
    private static Bundle preparationRecovery(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation");String id=ControlWire.required(input,"operation");RuntimeFiles.id(id);
        synchronized(broker){
            var operation=broker.contract.query(id);
            if(operation==null||!ControllerTrust.OPERATIONS.equals(operation.callerIdentity)
                    ||operation.state!=ExecutionContract.OperationState.UNKNOWN
                    ||!"LAUNCH_PREPARATION_UNCONFIRMED".equals(operation.detail))
                throw new SecurityException("UNKNOWN_LAUNCH_PREPARATION_REQUIRED");
            if(ActivityEntryFences.controllerTicket(broker.store,id)!=null)throw new IllegalStateException("LAUNCH_ENTRY_ALREADY_ISSUED");
            Bundle proof=new Bundle();proof.putString("operation",id);proof.putString("state","UNKNOWN");proof.putString("detail",operation.detail);
            proof.putBoolean("entryIssued",false);proof.putBoolean("entryInert",true);proof.putBoolean("canAcknowledge",false);proof.putString("recoveryState","NO_ENTRY_TO_FENCE_OUTCOME_UNKNOWN");proof.putBoolean("resourcesRetired",false);return proof;
        }
    }
    static Bundle receipt(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation");String id=ControlWire.required(input,"operation");RuntimeFiles.id(id);
        synchronized(broker){
            var closure=broker.contract.query(id);String prefix="ENTRY_FENCED_RESOURCES_RETAINED:";
            if(closure==null||!ControllerTrust.OPERATIONS.equals(closure.callerIdentity)||closure.state!=ExecutionContract.OperationState.SUCCEEDED||closure.result==null||!closure.result.startsWith(prefix))throw new SecurityException("ENTRY_CLOSE_RECEIPT_REQUIRED");
            String ticket=closure.result.substring(prefix.length());RuntimeFiles.id(ticket);
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT entryFenceOperation FROM activity_tickets WHERE ticket=?",new String[]{ticket})){if(!c.moveToFirst()||!id.equals(c.getString(0)))throw new SecurityException("ENTRY_CLOSE_RECEIPT_MISMATCH");}
            var launch=broker.activities.launches.get(broker.activities.launches.forTicket(ticket));var original=broker.contract.query(launch.operation);
            if(original==null||original.state!=ExecutionContract.OperationState.UNKNOWN)throw new SecurityException("ENTRY_CLOSE_ORIGINAL_OUTCOME_CHANGED");
            Bundle out=new Bundle();out.putString("operation",id);out.putString("ticket",ticket);out.putString("launchOperation",launch.operation);out.putString("state",original.state.name());out.putString("detail",original.detail);out.putBoolean("entryClosed",true);out.putBoolean("resourcesRetired",false);return out;
        }
    }
    static Bundle list(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.targetFields(input,"command","project");
        Bundle target=input.containsKey("bindingOperation")?new ProjectTargetBindings(broker).resolveBinding(ControlWire.required(input,"project"),input.getString("bindingOperation")):new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project"));JSONArray entries=new JSONArray();
        synchronized(broker){
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT l.operation,t.ticket,t.generation,t.state,t.taskId,s.state,o.state,o.detail FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate JOIN sessions s ON s.generation=t.generation JOIN operations o ON o.id=l.operation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND l.callerKind='CONTROLLER' AND t.entryFenceOperation IS NULL AND t.taskId<0 AND t.state IN ('PREPARED','CLAIMED') AND s.state='DEAD' AND o.state='UNKNOWN' ORDER BY l.operation LIMIT 129",new String[]{target.getString("space"),target.getString("package"),target.getString("generation")})){
                while(c.moveToNext())entries.put(new JSONObject().put("operation",c.getString(0)).put("ticket",c.getString(1)).put("processGeneration",c.getString(2)).put("ticketState",c.getString(3)).put("taskId",c.getInt(4)).put("processState",c.getString(5)).put("outcome",c.getString(6)).put("detail",c.getString(7)).put("resourcesRetained",true));
            }
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT l.operation,t.ticket,t.generation,t.state,t.taskId,s.state,o.state,t.component,l.sourceTicket,o.detail,l.callerKind FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate JOIN sessions s ON s.generation=t.generation JOIN operations o ON o.id=l.operation WHERE t.space=? AND t.package=? AND t.installGeneration=? AND ((l.callerKind='ACTIVITY' AND l.sourceTicket IS NOT NULL AND l.sourceInstance IS NOT NULL) OR (l.callerKind='CONTEXT' AND l.sourceTicket IS NULL AND l.sourceInstance IS NULL AND l.callerComponent IS NULL AND l.expectsResult=0 AND (l.requestFlags & 268435456)!=0)) AND l.payloadDigest IS NOT NULL AND l.state='UNKNOWN' AND t.entryFenceOperation IS NULL AND t.taskId<0 AND t.state IN ('PREPARED','CLAIMED') AND s.state='DEAD' AND o.state='UNKNOWN' ORDER BY l.operation LIMIT 129",new String[]{target.getString("space"),target.getString("package"),target.getString("generation")})){
                while(c.moveToNext())entries.put(new JSONObject().put("operation",c.getString(0)).put("ticket",c.getString(1)).put("processGeneration",c.getString(2)).put("ticketState",c.getString(3)).put("taskId",c.getInt(4)).put("processState",c.getString(5)).put("outcome",c.getString(6)).put("component",c.getString(7)).put("sourceTicket",c.getString(8)).put("kind",c.getString(10)+"_ROUTE").put("detail",c.getString(9)).put("resourcesRetained",true));
            }
        }
        if(entries.length()>128)throw new IllegalStateException("LAUNCH_ENTRY_PAGE_REQUIRED");target.putString("entries",entries.toString());return target;
    }
    static Bundle close(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","project","bindingOperation","generation","launchOperation");
        String operation=ControlWire.required(input,"operation"),project=ControlWire.required(input,"project"),binding=ControlWire.required(input,"bindingOperation"),install=ControlWire.required(input,"generation"),launch=ControlWire.required(input,"launchOperation");
        RuntimeFiles.id(operation);RuntimeFiles.id(launch);
        String digest=ApkStore.textDigest("close-unattached-entry-v1\n"+project+"\n"+binding+"\n"+install+"\n"+launch);
        synchronized(broker){
            String controller=ActivityEntryFences.controllerTicket(broker.store,launch),ticket;
            if(controller!=null){
                var root=broker.activities.get(controller);
                try(var c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM project_refs WHERE id=? AND operation=? AND generation=? AND space=? AND package=? AND kind='DIRECT'",new String[]{project,binding,install,root.space,root.pkg})){if(!c.moveToFirst()||!install.equals(root.install))throw new SecurityException("LAUNCH_ENTRY_BINDING_MISMATCH");}
                ticket=controller;
            }
            else try(var c=broker.store.getReadableDatabase().rawQuery("SELECT t.ticket FROM activity_launches l JOIN activity_tickets t ON t.ticket=l.candidate JOIN project_refs p ON p.space=t.space AND p.package=t.package AND p.generation=t.installGeneration WHERE l.operation=? AND l.callerKind IN ('ACTIVITY','CONTEXT') AND p.id=? AND p.operation=? AND p.generation=? AND p.kind='DIRECT' AND p.state='COMMITTED'",new String[]{launch,project,binding,install})){
                if(!c.moveToFirst())throw new SecurityException("LAUNCH_ENTRY_BINDING_MISMATCH");ticket=c.getString(0);if(c.moveToNext())throw new SecurityException("ACTIVITY_ROUTE_ENTRY_AMBIGUOUS");
            }
            var db=broker.store.getWritableDatabase();db.beginTransaction();try{
                try(var c=db.rawQuery("SELECT 1 FROM project_refs p JOIN installs i ON i.space=p.space AND i.package=p.package WHERE p.id=? AND p.operation=? AND p.generation=? AND p.kind='DIRECT' AND p.state='COMMITTED' AND i.state='COMMITTED' AND i.generation=p.generation",new String[]{project,binding,install})){if(!c.moveToFirst())throw new SecurityException("LAUNCH_ENTRY_BINDING_STALE");}
                var admitted=broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest);
                if(admitted.created){broker.contract.start(operation);if(controller==null)ActivityEntryFences.closeRoute(broker,ticket,launch,operation);else ActivityEntryFences.closeController(broker,ticket,launch,operation);broker.contract.succeed(operation,"ENTRY_FENCED_RESOURCES_RETAINED:"+ticket);}
                else if(admitted.operation.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("ENTRY_CLOSE_QUERY_REQUIRED");
                try(var c=db.rawQuery("SELECT entryFenceOperation FROM activity_tickets WHERE ticket=?",new String[]{ticket})){if(!c.moveToFirst()||!operation.equals(c.getString(0)))throw new SecurityException("ENTRY_CLOSE_RECEIPT_MISMATCH");}
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            Bundle result=new Bundle();result.putString("operation",operation);result.putString("launchOperation",launch);result.putString("ticket",ticket);result.putBoolean("entryClosed",true);result.putBoolean("resourcesRetired",false);result.putString("launchState",broker.contract.query(launch).state.name());return result;
        }
    }
    private RuntimeControllerEntryClosures(){}
}
