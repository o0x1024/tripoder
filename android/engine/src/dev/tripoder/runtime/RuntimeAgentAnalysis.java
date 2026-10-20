package dev.tripoder.runtime;

import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import org.json.*;

/** Generic pre-process controls. Independent of selected/running sessions. */
final class RuntimeAgentAnalysis {
    static Bundle stopApplication(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","project","installGeneration");
        Bundle target=new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project"));
        if(!target.getString("generation").equals(ControlWire.required(input,"installGeneration")))throw new IllegalStateException("INSTALL_GENERATION_CONFLICT");
        String operation=ControlWire.required(input,"operation"),space=target.getString("space"),pkg=target.getString("package"),generation=target.getString("generation");JSONObject result=null;
        synchronized(broker){var prior=broker.contract.query(operation);if(prior!=null){
            broker.contract.accept(operation,ControllerTrust.OPERATIONS,RuntimeControllerApplications.stopDigest(space,pkg,generation));
            if(prior.state!=ExecutionContract.OperationState.SUCCEEDED)throw new IllegalStateException("OPERATION_QUERY_REQUIRED");result=new JSONObject(prior.result);
        }}
        if(result==null){Bundle reply=RuntimeControllerApplications.stop(broker,ControlWire.request("stopApplication","operation",operation,"space",space,"package",pkg,"generation",generation),lease);result=new JSONObject().put("stoppedProcesses",reply.getInt("stoppedProcesses")).put("retiredUnattachedTickets",reply.getInt("retiredUnattachedTickets"));}
        result.put("project",target.getString("project")).put("installGeneration",generation).put("operationId",operation);Bundle output=new Bundle();output.putString("result",result.toString());return output;
    }
    static Bundle prepareLaunch(RuntimeBroker broker,Bundle input,String lease)throws Exception{
        RuntimeControllerCommands.fields(input,"command","operation","project","component","probeRevision");
        Bundle target=new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project"));
        synchronized(broker){broker.probePlans.beginLaunch(target,input.getString("probeRevision"));}
        try{
            // An existing process has already passed its startup boundary.
            synchronized(broker){for(var slot:broker.processSlots())if(slot.install.space.equals(target.getString("space"))&&slot.install.info.packageName.equals(target.getString("package")))throw new IllegalStateException("PROCESS_ALREADY_RUNNING_STARTUP_PROBE_NOT_APPLIED");}
            String component=input.getString("component");
            if(component.isEmpty()){
                JSONArray entries=new JSONArray(RuntimeControllerLaunch.targets(broker,ControlWire.request("launchTargets","project",target.getString("project"))).getString("targets"));
                for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).getBoolean("launcher")){if(!component.isEmpty())throw new IllegalStateException("APPLICATION_HAS_MULTIPLE_LAUNCHERS");component=entries.getJSONObject(i).getString("component");}
                if(component.isEmpty())throw new IllegalStateException("APPLICATION_HAS_NO_LAUNCHER");
            }
            Bundle request=ControlWire.request("prepareLaunch","operation",input.getString("operation"),"project",target.getString("project"),"bindingOperation",target.getString("bindingOperation"),"generation",target.getString("generation"),"component",component);request.putString("codeProbeRevision",input.getString("probeRevision"));
            Bundle result=RuntimeControllerLaunch.prepare(broker,request,lease);
            result.putString("project",target.getString("project"));result.putString("probeRevision",input.getString("probeRevision"));return result;
        }finally{synchronized(broker){broker.probePlans.endLaunch(target);}}
    }
    static JSONObject control(RuntimeBroker broker,Bundle input)throws Exception{
        String command=input.getString("command");
        if(command.equals("agentCapabilities")){
            RuntimeControllerCommands.fields(input,"command");return new JSONObject().put("startupProbes",true).put("applicationStop",true).put("loaderDiscovery",true).put("dexMemorySnapshots",true).put("dexVersions",new JSONArray(new int[]{35,37,38,39,40})).put("compactDex",false).put("methodBodyReconstruction",false).put("atomicMemorySnapshot",false).put("defaultProbeHooks",0).put("maxArtifactChunkBytes",16384).put("discoveryScope","OWNED_BASE_DEX_CLASS_LOADERS").put("probePlansSurviveAuthorityRestart",false);
        }
        if(command.equals("agentProjects")){
            RuntimeControllerCommands.fields(input,"command");JSONArray projects=new JSONArray();synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT p.id,p.space,p.package,p.generation,p.operation,p.mode FROM project_refs p JOIN installs i ON i.space=p.space AND i.package=p.package AND i.generation=p.generation WHERE p.kind='DIRECT' AND p.state='COMMITTED' AND i.state='COMMITTED' ORDER BY p.id LIMIT 257",null)){
                while(c.moveToNext())projects.put(new JSONObject().put("project",c.getString(0)).put("space",c.getString(1)).put("package",c.getString(2)).put("installGeneration",c.getString(3)).put("bindingOperation",c.getString(4)).put("mode",c.getString(5)));
            }}if(projects.length()>256)throw new IllegalStateException("PROJECT_PAGE_REQUIRED");return new JSONObject().put("projects",projects);
        }
        if(command.equals("agentOperation")){
            RuntimeControllerCommands.fields(input,"command","operation");synchronized(broker){var row=broker.contract.query(ControlWire.required(input,"operation"));if(row==null)throw new IllegalArgumentException("OPERATION_NOT_FOUND");return new JSONObject().put("operationId",row.id).put("state",row.state.name()).put("detail",row.detail).put("result",row.result).put("evidence",row.evidence);}
        }
        boolean write=command.equals("agentProbeConfigure");
        if(write)RuntimeControllerCommands.fields(input,"command","operation","project","expectedRevision","configuration");else RuntimeControllerCommands.fields(input,"command","project");
        synchronized(broker){
            Bundle target=new ProjectTargetBindings(broker).resolve(ControlWire.required(input,"project"));
            if(!write)return broker.probePlans.describe(target);
            JSONObject configuration=RuntimeProbePlans.validate(new JSONObject(ControlWire.required(input,"configuration")));
            String operation=ControlWire.required(input,"operation"),digest=ApkStore.textDigest("probe-configuration-v1\n"+target.getString("bindingOperation")+"\n"+input.getString("expectedRevision")+"\n"+configuration);
            if(!broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest).created){var prior=broker.contract.query(operation);if(prior.state==ExecutionContract.OperationState.SUCCEEDED)return new JSONObject(prior.result);throw new IllegalStateException("OPERATION_QUERY_REQUIRED");}
            broker.contract.start(operation);boolean changed=false;
            try{JSONObject result=broker.probePlans.configure(target,input.getString("expectedRevision"),configuration);changed=true;broker.contract.succeed(operation,result.toString());return result;}
            catch(Exception failure){if(changed)broker.contract.markUnknown(operation,"PROBE_CONFIGURATION_ACK_UNKNOWN");else broker.contract.fail(operation,"PROBE_CONFIGURATION_REJECTED");throw failure;}
        }
    }
}
