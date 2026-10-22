package dev.tripoder.runtime;

import android.os.Bundle;
import dev.tripoder.control.AgentCrypto;
import dev.tripoder.control.ControlWire;
import java.util.*;
import org.json.*;

/** Unauthenticated desktop connections. Sessions name a target, not a trusted
 * client. Binding/process fences and operation journals still prevent stale
 * target execution and accidental mutation replay. */
final class RuntimeAgentSessions implements AutoCloseable {
    private static final int RESPONSE_LIMIT=256,RESPONSE_BYTES=2*1024*1024;
    private final RuntimeBroker broker;
    private final Map<String,Session> sessions=new LinkedHashMap<>();
    private static final class Session {
        final String id,subject,lease,project,process;final Bundle target;
        final LinkedHashMap<String,String> requests=new LinkedHashMap<>();
        final LinkedHashMap<String,JSONObject> responses=new LinkedHashMap<>();
        boolean live=true,busy;int responseBytes;String state="OPEN",hookState="IDLE";
        Session(String lease,Bundle target,String process){
            id=UUID.randomUUID().toString();this.lease=lease;this.target=new Bundle(target);
            project=target.getString("project");this.process=process;subject="agent:"+id;
        }
    }
    RuntimeAgentSessions(RuntimeBroker broker){this.broker=broker;}
    Bundle control(Bundle input,String lease)throws Exception{
        RuntimeControllerService.requireLease(lease);String command=ControlWire.required(input,"command");
        JSONObject result;
        switch(command){
            case "agentPrepareLaunch":return RuntimeAgentAnalysis.prepareLaunch(broker,input,lease);
            case "agentStopApplication":return RuntimeAgentAnalysis.stopApplication(broker,input,lease);
            case "agentCapabilities":case "agentProjects":case "agentProbeConfigure":case "agentProbeGet":case "agentOperation":result=RuntimeAgentAnalysis.control(broker,input);break;
            case "agentExplicit":RuntimeControllerCommands.fields(input,"command","payload");result=explicit(input.getString("payload"),lease);break;
            case "agentTargets":RuntimeControllerCommands.fields(input,"command");result=targets();break;
            case "agentSelect":RuntimeControllerCommands.fields(input,"command","project","processGeneration");result=select(input,lease);break;
            case "agentCall":RuntimeControllerCommands.fields(input,"command","session","payload");result=call(input.getString("session"),input.getString("payload"),lease);break;
            case "agentShutdown":RuntimeControllerCommands.fields(input,"command");close();result=new JSONObject().put("state","CLOSED");break;
            default:throw new UnsupportedOperationException("AGENT_CONTROL_UNSUPPORTED");
        }
        Bundle out=new Bundle();out.putString("result",result.toString());return out;
    }
    private JSONObject targets()throws Exception{
        new ApplicationProjects(broker).reconcileMetadata();
        JSONArray rows=new JSONArray();synchronized(broker){
            try(var c=broker.store.getReadableDatabase().rawQuery("SELECT p.id,p.space,p.package,p.generation,p.operation,p.mode,s.generation,s.processName,s.pid FROM project_refs p JOIN installs i ON i.space=p.space AND i.package=p.package AND i.generation=p.generation JOIN sessions s ON s.space=p.space AND s.package=p.package AND s.installGeneration=p.generation WHERE p.kind='DIRECT' AND p.state='COMMITTED' AND i.state='COMMITTED' AND s.state='RUNNING' AND s.authorityGeneration=? ORDER BY p.id,s.processName LIMIT 257",new String[]{broker.store.authorityGeneration})){
                while(c.moveToNext())rows.put(new JSONObject().put("project",c.getString(0)).put("space",c.getString(1)).put("package",c.getString(2)).put("installGeneration",c.getString(3)).put("bindingOperation",c.getString(4)).put("mode",c.getString(5)).put("processGeneration",c.getString(6)).put("processName",c.getString(7)).put("pid",c.getInt(8)));
            }
        }
        if(rows.length()>256)throw new IllegalStateException("AGENT_TARGET_PAGE_REQUIRED");
        return new JSONObject().put("targets",rows).put("authenticationRequired",false);
    }
    private JSONObject explicit(String raw,String lease)throws Exception{
        JSONObject request=new JSONObject(raw);AgentCrypto.fields(request,"protocol","requestId","method","params");
        JSONObject params=request.getJSONObject("params"),identity=params.getJSONObject("target");
        AgentCrypto.fields(identity,"space","package","installGeneration","processGeneration");
        Bundle target=new ApplicationProjects(broker).target(AgentCrypto.text(identity,"space"),AgentCrypto.text(identity,"package"),AgentCrypto.text(identity,"installGeneration"));
        String process=AgentCrypto.text(identity,"processGeneration");
        // Resolve and fence this call's exact target. No connection-wide selection is read or changed.
        JSONObject context=select(ControlWire.request("agentSelect","project",target.getString("project"),"processGeneration",process,"bindingOperation",target.getString("bindingOperation")),lease);
        params.remove("target");if(request.getString("method").equals("hook.guestInvoke")){Object invocation=params.remove("invocation");if(!(invocation instanceof JSONObject))throw new IllegalArgumentException("AGENT_INVOCATION_REQUIRED");params.put("target",invocation);}JSONObject response=call(context.getString("session"),request.toString(),lease);response.remove("session");response.put("target",identity);return response;
    }
    private synchronized JSONObject select(Bundle input,String lease)throws Exception{
        String project=ControlWire.required(input,"project"),process=ControlWire.required(input,"processGeneration");
        Bundle target=input.containsKey("bindingOperation")?new ProjectTargetBindings(broker).resolveBinding(project,input.getString("bindingOperation")):new ProjectTargetBindings(broker).resolve(project);
        synchronized(broker){
            var slot=broker.requiredSlot(process);var identity=slot.allocation.session.identity;
            if(broker.contract.getSession(process).state!=ExecutionContract.ProcessState.RUNNING||!identity.spaceId.equals(target.getString("space"))||!identity.packageName.equals(target.getString("package"))||!identity.installGeneration.equals(target.getString("generation")))throw new IllegalStateException("AGENT_TARGET_STALE");
        }
        // A stopped process or superseded binding is no longer a selectable
        // context. Repeated launches must not exhaust a lifetime session quota.
        for(Session prior:sessions.values())synchronized(prior){
            boolean current=prior.live&&prior.lease.equals(lease);
            if(current)try{
                synchronized(broker){current=broker.contract.getSession(prior.process).state==ExecutionContract.ProcessState.RUNNING;}
                Bundle binding=new ProjectTargetBindings(broker).resolveBinding(prior.project,prior.target.getString("bindingOperation"));
                current=current&&prior.target.getString("bindingOperation").equals(binding.getString("bindingOperation"));
            }catch(Exception stale){current=false;}
            if(!current&&!prior.busy){prior.live=false;prior.state="CLOSED";}
        }
        sessions.entrySet().removeIf(e->!e.getValue().live&&!e.getValue().busy);
        for(Session prior:sessions.values())synchronized(prior){if(prior.live&&prior.lease.equals(lease)&&prior.project.equals(project)&&prior.process.equals(process)&&prior.target.getString("bindingOperation").equals(target.getString("bindingOperation")))return describe(prior);}
        if(sessions.size()>=32)throw new IllegalStateException("AGENT_TARGET_CAPACITY");
        Session session=new Session(lease,target,process);sessions.put(session.id,session);return describe(session);
    }
    // Phone controls remain usable while desktop Agents are connected.
    void requireUiControl(String project,String command){}
    void requireApplicationControl(String space,String pkg){}
    private synchronized Session owned(String id,String lease){
        Session value=sessions.get(id);if(value==null||!value.lease.equals(lease))throw new IllegalStateException("AGENT_TARGET_SELECTION_REQUIRED");return value;
    }
    private void live(Session session){
        RuntimeControllerService.requireLease(session.lease);
        if(!session.live)throw new IllegalStateException("AGENT_SESSION_CLOSED_SELECT_TARGET");
    }
    private JSONObject call(String id,String payload,String lease)throws Exception{
        if(payload==null||payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32000)throw new IllegalArgumentException("AGENT_REQUEST_SIZE");
        Session session=owned(id,lease);JSONObject request=new JSONObject(payload);AgentCrypto.fields(request,"protocol","requestId","method","params");
        if(request.getInt("protocol")!=AgentCrypto.PROTOCOL)throw new IllegalArgumentException("AGENT_PROTOCOL_UNSUPPORTED");
        String operation=AgentCrypto.id(AgentCrypto.text(request,"requestId")),method=AgentCrypto.text(request,"method");JSONObject params=request.getJSONObject("params");String digest=ApkStore.textDigest(payload);
        synchronized(session){
            if(!Set.of("session.get","operation.get","session.close").contains(method))live(session);
            String prior=session.requests.get(operation);
            if(prior!=null){if(!prior.equals(digest))throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");JSONObject response=session.responses.get(operation);if(response==null)throw new IllegalStateException("AGENT_OPERATION_QUERY_REQUIRED");return new JSONObject(response.toString());}
            if(session.busy)throw new IllegalStateException("AGENT_OPERATION_IN_FLIGHT");
            while(session.requests.size()>=RESPONSE_LIMIT||session.responseBytes>=RESPONSE_BYTES){
                String oldest=session.responses.keySet().iterator().next();JSONObject removed=session.responses.remove(oldest);session.requests.remove(oldest);session.responseBytes-=removed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            session.requests.put(operation,digest);session.busy=true;
        }
        try{
            JSONObject value;
            if(method.equals("session.get")){AgentCrypto.fields(params);synchronized(session){value=describe(session);}}
            else if(method.equals("process.get")){
                AgentCrypto.fields(params);synchronized(broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT pid,processName FROM sessions WHERE generation=? AND state='RUNNING' AND authorityGeneration=?",new String[]{session.process,broker.store.authorityGeneration})){if(!c.moveToFirst())throw new IllegalStateException("AGENT_TARGET_STOPPED");value=new JSONObject().put("processGeneration",session.process).put("pid",c.getInt(0)).put("processName",c.getString(1)).put("space",session.target.getString("space")).put("package",session.target.getString("package"));}}
            }else if(method.equals("operation.get"))value=operation(session,params);
            else if(method.startsWith("hook."))value=hook(session,operation,method.substring(5),params);
            else if(method.equals("session.close")){AgentCrypto.fields(params);synchronized(session){session.live=false;session.state="CLOSED";value=describe(session);}}
            else throw new UnsupportedOperationException("AGENT_METHOD_UNSUPPORTED");
            return cache(session,operation,new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",operation).put("session",id).put("project",session.project).put("processGeneration",session.process).put("ok",true).put("result",value));
        }catch(Exception error){
            String state="FAILED";synchronized(broker){var row=broker.contract.query(operation);if(row!=null){if(!session.subject.equals(row.callerIdentity))throw new IllegalArgumentException("AGENT_OPERATION_ID_CONFLICT");state=row.state.name();}}
            JSONObject failure=new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",operation).put("session",id).put("ok",false).put("operationId",operation).put("state",state).put("error",error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()));
            if(error instanceof RuntimeHookOperations.RejectedCommandException)failure.put("outcome","REJECTED");
            return cache(session,operation,failure);
        }finally{synchronized(session){session.busy=false;}}
    }
    private JSONObject cache(Session session,String operation,JSONObject response){synchronized(session){session.responses.put(operation,response);session.responseBytes+=response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}return response;}
    private JSONObject operation(Session session,JSONObject params)throws Exception{
        AgentCrypto.fields(params,"operationId");String id=AgentCrypto.id(AgentCrypto.text(params,"operationId"));
        synchronized(broker){var row=broker.contract.query(id);if(row==null)throw new IllegalStateException("AGENT_OPERATION_NOT_FOUND");if(!session.subject.equals(row.callerIdentity))throw new IllegalArgumentException("AGENT_OPERATION_ID_CONFLICT");return new JSONObject().put("operationId",row.id).put("state",row.state.name()).put("detail",row.detail).put("result",row.result).put("evidence",row.evidence);}
    }
    private JSONObject hook(Session session,String operation,String command,JSONObject params)throws Exception {
        switch(command){
            case "observe":AgentCrypto.fields(params,"selectors");break;
            case "prepare":AgentCrypto.fields(params,"evidenceId","source");break;
            case "activate":AgentCrypto.fields(params,"token","sourceSha256");break;
            case "disable":AgentCrypto.fields(params,"revision");break;
            case "updateParameters":AgentCrypto.fields(params,"revision","parameterVersion","parameters");break;
            case "events":AgentCrypto.fields(params,"sinceSeq");break;
            case "dexSearch":AgentCrypto.fields(params,"packagePrefix","limit");break;
            case "dexInspect":AgentCrypto.fields(params,"className");break;
            case "nativeExports":AgentCrypto.fields(params,"moduleName","symbolPrefix","limit");break;
            case "uiDump":AgentCrypto.fields(params,"includeInvisible");break;
            case "uiAction":AgentCrypto.fields(params,"action");break;
            case "storageInspect":AgentCrypto.fields(params,"category","limit");break;
            case "networkTraffic":AgentCrypto.fields(params,"sinceSeq","limit");break;
            case "httpRequest":AgentCrypto.fields(params,"request");break;
            case "guestInvoke":AgentCrypto.fields(params,"target");break;
            case "probeStatus":case "probeStop":case "classLoaders":case "codeArtifacts":AgentCrypto.fields(params);break;
            case "dexList":AgentCrypto.fields(params,"classLoaderId","cursor","limit");break;
            case "dexSnapshot":AgentCrypto.fields(params,"dexId");break;
            case "runtimeEvents":AgentCrypto.fields(params,"sinceSeq","limit");break;
            case "classInspect":AgentCrypto.fields(params,"classLoaderId","className");break;
            case "classLoaderCreate":AgentCrypto.fields(params,"dexId","parentClassLoaderId");break;
            case "status":case "modules":case "discard":case "diagnostics":case "manifestInspect":AgentCrypto.fields(params);break;
            default:throw new UnsupportedOperationException("AGENT_HOOK_CAPABILITY_UNSUPPORTED");
        }
        live(session);
        JSONObject raw=new JSONObject(params.toString()).put("command",command).put("processGeneration",session.process);
        Bundle input=ControlWire.request("hook","operation",operation,"project",session.project,"bindingOperation",session.target.getString("bindingOperation"),"generation",session.target.getString("generation"),"processGeneration",session.process,"request",raw.toString());
        Bundle reply=RuntimeControllerHooks.executeAs(broker,input,session.lease,session.subject,session.lease);
        JSONObject result=new JSONObject(reply.getString("result")).getJSONObject("result");
        synchronized(session){
            if(command.equals("observe"))session.hookState="OBSERVED";
            if(result.has("state"))session.hookState=result.getString("state");
        }
        return result.put("operationId",operation);
    }
    private JSONObject describe(Session session)throws Exception{
        return new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("session",session.id).put("state",session.state)
            .put("project",session.project).put("space",session.target.getString("space")).put("package",session.target.getString("package"))
            .put("installGeneration",session.target.getString("generation")).put("bindingOperation",session.target.getString("bindingOperation"))
            .put("processGeneration",session.process).put("mode",session.target.getString("mode",RuntimeMode.DEFAULT.wire))
            .put("hookState",session.hookState).put("authenticationRequired",false).put("reviewRequired",false).put("autoApprove",true)
            .put("expires",JSONObject.NULL).put("processClose","NOT_REQUESTED").put("strongIsolation",false);
    }
    @Override public synchronized void close(){for(Session session:sessions.values())synchronized(session){session.live=false;session.state="CLOSED";}}
}
