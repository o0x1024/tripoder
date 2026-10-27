package dev.tripoder.runtime;

import android.database.Cursor;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.*;

/** Trusted project ownership is checked before a worker sees any hook command. */
final class RuntimeControllerHooks {
    static Bundle processes(RuntimeBroker broker,Bundle input)throws Exception{
        RuntimeControllerCommands.targetFields(input,"command","project");
        Bundle target=(input.containsKey("bindingOperation")?new ProjectTargetBindings(broker).resolveBinding(ControlWire.required(input,"project"),input.getString("bindingOperation")):new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project")));JSONArray processes=new JSONArray();
        synchronized(broker){
            requireBinding(broker,target);
            try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT generation,pid,processName FROM sessions WHERE space=? AND package=? AND installGeneration=? AND authorityGeneration=? AND state='RUNNING'",new String[]{target.getString("space"),target.getString("package"),target.getString("generation"),broker.store.authorityGeneration})){
                while(c.moveToNext()){
                    String gen=c.getString(0);var slot=broker.processSlots().stream().filter(s->s.allocation.session.processGeneration.equals(gen)).findFirst().orElse(null);
                    processes.put(new JSONObject().put("processGeneration",gen).put("pid",c.getInt(1)).put("processName",c.getString(2)));
                }
            }
        }
        target.putString("processes",processes.toString());return target;
    }
    static Bundle execute(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        return executeAs(broker,input,lease,ControllerTrust.OPERATIONS,lease);
    }
    static Bundle executeAs(RuntimeBroker broker,Bundle input,String lease,String caller,String owner)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","project","bindingOperation","generation","processGeneration","request");
        String operation=ControlWire.required(input,"operation"),project=ControlWire.required(input,"project"),generation=ControlWire.required(input,"processGeneration"),raw=ControlWire.required(input,"request");
        if(raw.length()>28000)throw new IllegalArgumentException("HOOK_REQUEST_SIZE");
        JSONObject request=new JSONObject(raw);String command=request.getString("command");
        if(!generation.equals(request.getString("processGeneration")))throw new SecurityException("HOOK_PROCESS_STALE");
        Bundle target=new ProjectTargetBindings(broker).resolveBinding(project,input.getString("bindingOperation"));
        if(!input.getString("bindingOperation").equals(target.getString("bindingOperation"))||!input.getString("generation").equals(target.getString("generation")))throw new IllegalStateException("HOOK_PROJECT_BINDING_STALE");
        RuntimeHookClaims.Claim claim;
        synchronized(broker){
            RuntimeControllerService.requireLease(lease);requireBinding(broker,target);
            var slot=broker.requiredSlot(generation);var identity=slot.allocation.session.identity;
            if(!identity.spaceId.equals(target.getString("space"))||!identity.packageName.equals(target.getString("package"))||!identity.installGeneration.equals(target.getString("generation")))throw new SecurityException("HOOK_PROJECT_PROCESS_MISMATCH");
            claim=broker.hookClaims.begin(generation,project,target.getString("bindingOperation"),owner,command);
        }
        try{
            String scope=new JSONObject().put("project",project).put("bindingOperation",target.getString("bindingOperation")).put("installGeneration",target.getString("generation")).put("lease",owner).toString();
            Bundle output=RuntimeHookOperations.execute(broker,operation,generation,raw,caller,scope);
            JSONObject result=new JSONObject(output.getString("result")).getJSONObject("result");
            synchronized(broker){broker.hookClaims.complete(claim,command,result);}
            output.putString("project",project);output.putString("bindingOperation",target.getString("bindingOperation"));return output;
        }catch(Exception failure){
            synchronized(broker){
                if(failure instanceof RuntimeHookOperations.RejectedCommandException||dev.tripoder.runtime.script.HookCommands.readOnly(command))broker.hookClaims.rejected(claim);
                else broker.hookClaims.uncertain(claim,operation);
            }
            throw failure;
        }
    }
    private static void requireBinding(RuntimeBroker broker,Bundle target){
        try(Cursor c=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM project_refs p JOIN installs i ON i.space=p.space AND i.package=p.package AND i.generation=p.generation WHERE p.id=? AND p.operation=? AND p.generation=? AND p.state='COMMITTED' AND p.kind='DIRECT' AND i.state='COMMITTED'",new String[]{target.getString("project"),target.getString("bindingOperation"),target.getString("generation")})){if(!c.moveToFirst())throw new IllegalStateException("HOOK_PROJECT_BINDING_STALE");}
    }
    private RuntimeControllerHooks(){}
}
