package dev.tripoder.runtime;

import android.os.Bundle;
import android.os.SystemClock;
import dev.tripoder.control.AgentCrypto;
import dev.tripoder.control.ControlWire;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Authority-owned, process-bound grants. Restarts never restore a live authorization. */
final class RuntimeAgentSessions implements AutoCloseable {
    private static final int REQUEST_BUDGET = 256;
    private static final int RESPONSE_BYTES_BUDGET = 2 * 1024 * 1024;
    private final RuntimeBroker broker;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService stops = Executors.newSingleThreadExecutor();
    private static final class Session {
        final String id, subject, key, lease, project, process;
        final Bundle target;
        final PublicKey publicKey;
        final long expires;
        final Map<String, String> requests = new HashMap<>();
        final Map<String, JSONObject> responses = new HashMap<>();
        final AgentContactLease contact = new AgentContactLease(SystemClock.elapsedRealtime());
        final boolean autoApprove;
        boolean live = true, busy;int responseBytes;
        String state = "OPEN", reason = "", hookState = "IDLE", approved = "", source = "";
        JSONObject prepared;JSONArray taskResources=new JSONArray();String processClose="NOT_REQUESTED";
        Session(String id, String key, String lease, Bundle target, String process, boolean autoApprove) throws Exception {
            this.id=id;this.key=key;this.lease=lease;this.target=new Bundle(target);this.autoApprove=autoApprove;
            this.project=target.getString("project");this.process=process;this.publicKey=AgentCrypto.publicKey(key);
            this.subject="agent:"+ApkStore.textDigest(id+"\n"+AgentCrypto.fingerprint(key)+"\n"+lease+"\n"+project+"\n"+process);this.expires=contact.expires;
        }
    }
    RuntimeAgentSessions(RuntimeBroker broker) {
        this.broker=broker;
        watchdog.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }
    Bundle control(Bundle input, String lease) throws Exception {
        String command = ControlWire.required(input,"command");
        if (command.equals("agentGrant")) return grant(input,lease);
        if (command.equals("agentCall")) {
            RuntimeControllerCommands.fields(input,"command","session","payload","signature");
            JSONObject result=call(input.getString("session"),input.getString("payload"),input.getString("signature"),lease);
            Bundle out=new Bundle();out.putString("result",result.toString());return out;
        }
        if (command.equals("agentInspect")) {
            RuntimeControllerCommands.fields(input,"command","session");Session session=owned(input.getString("session"),lease);
            Bundle out=new Bundle();synchronized(session){out.putString("result",describe(session).put("draftSource",session.source).toString());}return out;
        }
        if (command.equals("agentApprove")) {
            RuntimeControllerCommands.fields(input,"command","session","sourceSha256");Session session=owned(input.getString("session"),lease);
            synchronized(session){live(session);if(session.busy||session.prepared==null||!session.prepared.getString("sourceSha256").equals(input.getString("sourceSha256")))throw new SecurityException("AGENT_REVIEW_STALE");
                String operation=UUID.randomUUID().toString();synchronized(broker){broker.contract.accept(operation,ControllerTrust.OPERATIONS,ApkStore.textDigest("agent-review-v1\n"+session.id+"\n"+session.prepared.toString()));broker.contract.start(operation);broker.contract.succeed(operation,"APPROVED:"+input.getString("sourceSha256"));}session.approved=input.getString("sourceSha256");}
            return new Bundle();
        }
        if (command.equals("agentRevoke")) {
            RuntimeControllerCommands.fields(input,"command","session");Session session=owned(input.getString("session"),lease);revoke(session,"USER_REVOKED");return new Bundle();
        }
        throw new UnsupportedOperationException("AGENT_CONTROL_UNSUPPORTED");
    }
    private Bundle grant(Bundle input,String lease)throws Exception {
        if(input.containsKey("autoApprove"))RuntimeControllerCommands.fields(input,"command","operation","session","publicKey","project","bindingOperation","generation","processGeneration","autoApprove");
        else RuntimeControllerCommands.fields(input,"command","operation","session","publicKey","project","bindingOperation","generation","processGeneration");
        boolean autoApprove="true".equals(input.getString("autoApprove"));
        String id=AgentCrypto.id(input.getString("session")),process=input.getString("processGeneration");
        Bundle target=new ProjectTargetBindings(broker).resolve(input.getString("project"));
        if(!target.getString("bindingOperation").equals(input.getString("bindingOperation"))||!target.getString("generation").equals(input.getString("generation")))throw new SecurityException("AGENT_BINDING_STALE");
        Session session=new Session(id,input.getString("publicKey"),lease,target,process,autoApprove);
        synchronized(this){
            sessions.entrySet().removeIf(entry->{Session prior=entry.getValue();synchronized(prior){return prior.state.equals("CLOSED")&&SystemClock.elapsedRealtime()>=prior.expires;}});
            if(sessions.size()>=8||sessions.containsKey(id))throw new IllegalStateException("AGENT_SESSION_CAPACITY_OR_REUSE");
            for(Session prior:sessions.values())synchronized(prior){if(prior.live||!prior.state.equals("CLOSED"))throw new IllegalStateException("AGENT_SESSION_ALREADY_OPEN_OR_CLOSE_UNCONFIRMED");}
            synchronized(broker){
                RuntimeControllerService.requireLease(lease);
                var slot=broker.requiredSlot(process);var identity=slot.allocation.session.identity;
                if(broker.contract.getSession(process).state!=ExecutionContract.ProcessState.RUNNING||!identity.spaceId.equals(target.getString("space"))||!identity.packageName.equals(target.getString("package"))||!identity.installGeneration.equals(target.getString("generation")))throw new SecurityException("AGENT_PROCESS_MISMATCH");
                broker.hookClaims.requireUnclaimedProject(session.project);
                String operation=input.getString("operation");
                String digest=ApkStore.textDigest("agent-grant-v1\n"+id+"\n"+AgentCrypto.fingerprint(session.key)+"\n"+target.getString("bindingOperation")+"\n"+process+"\n"+lease+"\n"+autoApprove);
                if(!broker.contract.accept(operation,ControllerTrust.OPERATIONS,digest).created)throw new IllegalStateException("AGENT_GRANT_ALREADY_ACCEPTED_QUERY_REQUIRED");
                broker.contract.start(operation);broker.contract.succeed(operation,describe(session).toString());sessions.put(id,session);
            }
        }
        Bundle out=new Bundle();out.putString("result",describe(session).toString());return out;
    }
    void requireUiControl(String project,String command) {
        if(Set.of("status","modules","events").contains(command))return;
        synchronized(this){for(Session session:sessions.values())synchronized(session){if(session.live&&session.project.equals(project))throw new IllegalStateException("AGENT_SESSION_ACTIVE_REVOKE_FIRST");}}
    }
    void requireApplicationControl(String space,String pkg){
        synchronized(this){for(Session session:sessions.values())synchronized(session){
            if(!session.state.equals("CLOSED")&&space.equals(session.target.getString("space"))&&pkg.equals(session.target.getString("package")))throw new IllegalStateException("AGENT_SESSION_ACTIVE_REVOKE_FIRST");
        }}
    }
    private Session owned(String id,String lease) {
        Session session;synchronized(this){session=sessions.get(id);}
        if(session==null||!session.lease.equals(lease))throw new SecurityException("AGENT_SESSION_NOT_OWNED");return session;
    }
    private void live(Session session) {
        String expired=session.contact.expiry(SystemClock.elapsedRealtime());
        if(session.live&&expired!=null)revoke(session,expired);
        if(!session.live)throw new SecurityException("AGENT_SESSION_EXPIRED_OR_REVOKED");
        RuntimeControllerService.requireLease(session.lease);
    }
    private JSONObject call(String id,String payload,String signature,String lease)throws Exception {
        if(payload==null||payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32000)throw new IllegalArgumentException("AGENT_REQUEST_SIZE");
        Session session=owned(id,lease);AgentCrypto.verify(session.publicKey,"request",id,payload,signature);
        JSONObject request=new JSONObject(payload);AgentCrypto.fields(request,"protocol","requestId","method","params");
        if(!(request.get("protocol") instanceof Integer)||request.getInt("protocol")!=1)throw new IllegalArgumentException("AGENT_PROTOCOL_UNSUPPORTED");
        String operation=AgentCrypto.id(AgentCrypto.text(request,"requestId")),method=AgentCrypto.text(request,"method");JSONObject params=request.getJSONObject("params");
        boolean recoveryRead;synchronized(session){String expired=session.contact.expiry(SystemClock.elapsedRealtime());if(session.live&&expired!=null)revoke(session,expired);recoveryRead=!session.live&&(method.equals("session.get")||method.equals("operation.get"));}
        if(recoveryRead){
            RuntimeControllerService.requireLease(session.lease);JSONObject value;
            if(method.equals("session.get")){AgentCrypto.fields(params);synchronized(session){value=describe(session);}}else value=operation(session,params);
            return new JSONObject().put("protocol",1).put("requestId",operation).put("session",id).put("project",session.project).put("processGeneration",session.process).put("ok",true).put("result",value);
        }
        String digest=ApkStore.textDigest(payload);
        synchronized(session){
            if(method.equals("session.get")||method.equals("operation.get"))RuntimeControllerService.requireLease(session.lease);else live(session);
            if(session.live)session.contact.touch(SystemClock.elapsedRealtime());
            String prior=session.requests.get(operation);
            if(prior!=null){
                if(!prior.equals(digest))throw new SecurityException("AGENT_REQUEST_ID_CONTENT_MISMATCH");
                JSONObject response=session.responses.get(operation);
                if(response==null)throw new IllegalStateException("AGENT_OPERATION_QUERY_REQUIRED");return new JSONObject(response.toString());
            }
            if(session.busy)throw new IllegalStateException("AGENT_OPERATION_IN_FLIGHT");
            if(session.requests.size()>=REQUEST_BUDGET||session.responseBytes>=RESPONSE_BYTES_BUDGET){revoke(session,"BUDGET_EXHAUSTED");throw new IllegalStateException("AGENT_REQUEST_BUDGET_EXHAUSTED");}
            session.requests.put(operation,digest);session.busy=true;
        }
        try {
            JSONObject value;
            if(method.equals("session.get")){AgentCrypto.fields(params);synchronized(session){value=describe(session);}}
            else if(method.equals("process.get")){
                AgentCrypto.fields(params);Bundle list=RuntimeControllerHooks.processes(broker,ControlWire.request("hookProcesses","project",session.project));JSONArray actual=new JSONArray(list.getString("processes"));JSONObject found=null;
                for(int i=0;i<actual.length();i++)if(actual.getJSONObject(i).getString("processGeneration").equals(session.process))found=actual.getJSONObject(i);
                if(found==null)throw new IllegalStateException("AGENT_PROCESS_NOT_RUNNING");value=found;
            } else if(method.equals("operation.get")) {
                value=operation(session,params);
            } else if(method.startsWith("hook.")) value=hook(session,operation,method.substring(5),params);
            else if(method.equals("session.close")){AgentCrypto.fields(params);revoke(session,"AGENT_CLOSED");synchronized(session){value=describe(session);}}
            else throw new UnsupportedOperationException("AGENT_METHOD_UNSUPPORTED");
            JSONObject response=new JSONObject().put("protocol",1).put("requestId",operation).put("session",id).put("project",session.project).put("processGeneration",session.process).put("ok",true).put("result",value);
            synchronized(session){session.responses.put(operation,response);session.responseBytes+=response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}return response;
        } catch(Exception error) {
            String code=error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage());String state;
            synchronized(broker){
                var row=broker.contract.query(operation);
                if(row==null){broker.contract.accept(operation,session.subject,ApkStore.textDigest("agent-denial-v1\n"+payload));broker.contract.start(operation);row=broker.contract.fail(operation,"AGENT_COMMAND_REJECTED");}
                if(!session.subject.equals(row.callerIdentity))throw new SecurityException("AGENT_OPERATION_OWNER_MISMATCH");state=row.state.name();
            }
            JSONObject response=new JSONObject().put("protocol",1).put("requestId",operation).put("session",id).put("project",session.project).put("processGeneration",session.process).put("ok",false).put("operationId",operation).put("state",state).put("error",code);
            synchronized(session){session.responses.put(operation,response);session.responseBytes+=response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}return response;
        } finally { synchronized(session){session.busy=false;} }
    }
    private JSONObject operation(Session session,JSONObject params)throws Exception{
        AgentCrypto.fields(params,"operationId");String queryId=AgentCrypto.id(AgentCrypto.text(params,"operationId"));
        synchronized(broker){var prior=broker.contract.query(queryId);if(prior==null)throw new IllegalStateException("AGENT_OPERATION_NOT_FOUND");if(!session.subject.equals(prior.callerIdentity))throw new SecurityException("AGENT_OPERATION_OWNER_MISMATCH");return new JSONObject().put("operationId",prior.id).put("state",prior.state.name()).put("detail",prior.detail).put("result",prior.result).put("evidence",prior.evidence);}
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
            case "status":case "modules":case "discard":case "diagnostics":case "manifestInspect":AgentCrypto.fields(params);break;
            default:throw new UnsupportedOperationException("AGENT_HOOK_CAPABILITY_UNSUPPORTED");
        }
        synchronized(session){
            live(session);
            if(command.equals("prepare")){session.approved="";session.prepared=null;session.source=AgentCrypto.text(params,"source");if(session.source.length()>24000)throw new IllegalArgumentException("AGENT_SOURCE_SIZE");}
            if(command.equals("activate")&&(session.prepared==null||!session.approved.equals(params.getString("sourceSha256"))||!session.prepared.getString("token").equals(params.getString("token"))))throw new SecurityException("AGENT_PHONE_REVIEW_REQUIRED");
        }
        JSONObject raw=new JSONObject(params.toString()).put("command",command).put("processGeneration",session.process);
        Bundle input=ControlWire.request("hook","operation",operation,"project",session.project,"bindingOperation",session.target.getString("bindingOperation"),"generation",session.target.getString("generation"),"processGeneration",session.process,"request",raw.toString());
        Bundle reply=RuntimeControllerHooks.executeAs(broker,input,session.lease,session.subject,session.subject);
        JSONObject result=new JSONObject(reply.getString("result")).getJSONObject("result");
        synchronized(session){
            if(command.equals("observe"))session.hookState="OBSERVED";
            if(result.has("state"))session.hookState=result.getString("state");
            if(command.equals("prepare")){
                session.prepared=new JSONObject(result.toString());
                if(session.autoApprove)session.approved=result.getString("sourceSha256");
            }
            if(command.equals("activate")||command.equals("disable")||command.equals("discard")){session.prepared=null;session.approved="";}
        }
        return result.put("operationId",operation);
    }
    private JSONObject describe(Session session)throws Exception {
        JSONObject value=new JSONObject().put("protocol",1).put("session",session.id).put("state",session.state).put("reason",session.reason).put("subjectFingerprint",AgentCrypto.fingerprint(session.key))
            .put("project",session.project).put("space",session.target.getString("space")).put("package",session.target.getString("package")).put("installGeneration",session.target.getString("generation")).put("bindingOperation",session.target.getString("bindingOperation")).put("processGeneration",session.process).put("mode",session.target.getString("mode",RuntimeMode.DEFAULT.wire))
            .put("remainingMs",Math.max(0,session.expires-SystemClock.elapsedRealtime())).put("remainingRequests",Math.max(0,REQUEST_BUDGET-session.requests.size())).put("hookState",session.hookState).put("reviewRequired",!session.autoApprove).put("autoApprove",session.autoApprove).put("approvedSourceSha256",session.approved)
            .put("capabilities",new JSONArray(List.of("session.get","process.get","operation.get","hook.observe","hook.prepare","hook.activate","hook.status","hook.modules","hook.updateParameters","hook.events","hook.dexSearch","hook.dexInspect","hook.nativeExports","hook.uiDump","hook.uiAction","hook.diagnostics","hook.disable","hook.discard","session.close")))
            .put("processClose",session.processClose).put("taskResources",session.taskResources).put("disconnectGraceMs",AgentContactLease.CONTACT_TIMEOUT).put("clientConnected",session.contact.connected()).put("contactRemainingMs",Math.max(0,session.contact.deadline()-SystemClock.elapsedRealtime())).put("strongIsolation",false);
        if(session.prepared!=null)value.put("preparedSourceSha256",session.prepared.getString("sourceSha256"));return value;
    }
    private void revoke(Session session,String reason) {
        synchronized(session){if(!session.live)return;session.live=false;session.reason=reason;session.state="CLOSING";session.approved="";}
        stops.execute(()->{
            String operation=UUID.randomUUID().toString();
            try{
                boolean stop;synchronized(session){stop=session.busy||!Set.of("IDLE","DISABLED").contains(session.hookState);}
                synchronized(broker){broker.contract.accept(operation,session.subject,ApkStore.textDigest("agent-close-v1\n"+session.id+"\n"+reason));broker.contract.start(operation);}
                if(stop)broker.processes.close(session.process);
                JSONArray taskResources=RuntimeEmptyTasks.reclaim(broker,session.process);
                synchronized(broker){broker.contract.succeed(operation,stop?"PROCESS_TERMINATED":"NO_ACTIVE_HOOK");}
                synchronized(session){session.processClose=stop?"PROCESS_DEAD_CONFIRMED":"NO_ACTIVE_HOOK";session.taskResources=taskResources;session.state="CLOSED";}
            }catch(Exception error){
                synchronized(broker){var row=broker.contract.query(operation);if(row!=null&&row.state==ExecutionContract.OperationState.RUNNING)broker.contract.markUnknown(operation,"AGENT_CLOSE_UNCONFIRMED");}
                synchronized(session){session.state="CLOSE_UNKNOWN";session.reason=reason+":"+error.getClass().getSimpleName();}
            }
        });
    }
    private void expire(){
        List<Session> all;synchronized(this){all=new ArrayList<>(sessions.values());}
        for(Session session:all){String reason=null;synchronized(session){if(!session.live)continue;reason=session.contact.expiry(SystemClock.elapsedRealtime());if(reason==null)try{RuntimeControllerService.requireLease(session.lease);}catch(Exception gone){reason="CONTROLLER_DIED";}}
            if(reason!=null)revoke(session,reason);
        }
    }
    @Override public void close(){watchdog.shutdownNow();List<Session> all;synchronized(this){all=new ArrayList<>(sessions.values());}for(Session session:all)revoke(session,"SERVICE_CLOSED");stops.shutdown();}
}
