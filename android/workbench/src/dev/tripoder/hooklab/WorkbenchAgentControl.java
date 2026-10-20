package dev.tripoder.hooklab;

import android.os.Bundle;
import dev.tripoder.control.*;
import org.json.*;

/** Bounded, unsigned desktop requests over the device loopback endpoint. */
final class WorkbenchAgentControl {
    private final WorkbenchApplication app;
    volatile String message="电脑 Agent 入口尚未启动";
    volatile boolean listening;
    WorkbenchAgentControl(WorkbenchApplication app){this.app=app;}
    JSONObject wire(JSONObject request)throws Exception{
        String id=request.optString("requestId","");
        try{
            if(!listening)throw new IllegalStateException("AGENT_ENDPOINT_CLOSED");
            AgentCrypto.id(id);if(request.getInt("protocol")!=AgentCrypto.PROTOCOL)throw new IllegalArgumentException("AGENT_PROTOCOL_UNSUPPORTED");
            String method=request.getString("method");JSONObject params=request.getJSONObject("params");Bundle output;
            if(java.util.Set.of("runtime.capabilities","projects.list","probes.configure","probes.get","process.start","application.stop","operation.get","artifact.list","artifact.read").contains(method)){
                AgentCrypto.fields(request,"protocol","requestId","method","params");
                switch(method){
                    case "runtime.capabilities":case "projects.list":AgentCrypto.fields(params);output=app.engine.executeBlocking(ControlWire.request(method.equals("projects.list")?"agentProjects":"agentCapabilities"));break;
                    case "probes.get":AgentCrypto.fields(params,"project");output=app.engine.executeBlocking(ControlWire.request("agentProbeGet","project",params.getString("project")));break;
                    case "probes.configure":AgentCrypto.fields(params,"project","expectedRevision","configuration");output=app.engine.executeBlocking(ControlWire.request("agentProbeConfigure","operation",id,"project",params.getString("project"),"expectedRevision",params.getString("expectedRevision"),"configuration",params.getJSONObject("configuration").toString()));break;
                    case "operation.get":AgentCrypto.fields(params,"operationId");output=app.engine.executeBlocking(ControlWire.request("agentOperation","operation",params.getString("operationId")));break;
                    case "application.stop":AgentCrypto.fields(params,"project","installGeneration");output=app.engine.executeBlocking(ControlWire.request("agentStopApplication","operation",id,"project",params.getString("project"),"installGeneration",params.getString("installGeneration")));break;
                    case "artifact.list":AgentCrypto.fields(params,"processGeneration");return new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",id).put("ok",true).put("result",dev.tripoder.runtime.RuntimeCodeArtifacts.list(app,params.getString("processGeneration")));
                    case "artifact.read":AgentCrypto.fields(params,"processGeneration","artifactId","offset","length");return new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",id).put("ok",true).put("result",dev.tripoder.runtime.RuntimeCodeArtifacts.read(app,params.getString("processGeneration"),params.getString("artifactId"),params.getInt("offset"),params.getInt("length")));
                    default:{
                        AgentCrypto.fields(params,"project","component","probeRevision");
                        output=app.engine.executeBlocking(ControlWire.request("agentPrepareLaunch","operation",id,"project",params.getString("project"),"component",params.getString("component"),"probeRevision",params.getString("probeRevision")));
                        android.content.Intent intent=output.getParcelable("intent",android.content.Intent.class);if(intent==null)throw new IllegalStateException("PROCESS_ENTRY_MISSING");
                        try{app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception failure){throw new IllegalStateException("PROCESS_ENTRY_DISPATCH_UNKNOWN_QUERY_OPERATION",failure);}
                        JSONObject result=new JSONObject().put("project",output.getString("project")).put("processGeneration",output.getString("generation")).put("pid",output.getInt("pid")).put("probeRevision",output.getString("probeRevision")).put("entryDispatchRequested",true).put("operationId",id);
                        return new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",id).put("ok",true).put("result",result);
                    }
                }
            }else if(method.equals("targets.list")){
                AgentCrypto.fields(request,"protocol","requestId","method","params");AgentCrypto.fields(params);
                output=app.engine.executeBlocking(ControlWire.request("agentTargets"));
            }else if(method.equals("target.select")){
                AgentCrypto.fields(request,"protocol","requestId","method","params");AgentCrypto.fields(params,"project","processGeneration");
                output=app.engine.executeBlocking(ControlWire.request("agentSelect","project",params.getString("project"),"processGeneration",params.getString("processGeneration")));
            }else{
                AgentCrypto.fields(request,"protocol","requestId","method","params","session");
                JSONObject payload=new JSONObject(request.toString());payload.remove("session");
                return new JSONObject(app.engine.executeBlocking(ControlWire.request("agentCall","session",request.getString("session"),"payload",payload.toString())).getString("result"));
            }
            return new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",id).put("ok",true).put("result",new JSONObject(output.getString("result")));
        }catch(Exception error){
            JSONObject failure=new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",id).put("ok",false).put("error",error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()));
            if(java.util.Set.of("process.start","application.stop","probes.configure").contains(request.optString("method"))){failure.put("operationId",id);try{JSONObject row=new JSONObject(app.engine.executeBlocking(ControlWire.request("agentOperation","operation",id)).getString("result"));failure.put("state",row.getString("state"));}catch(Exception notAccepted){failure.put("state","REJECTED");}}
            return failure;
        }
    }
    void revoke(EngineConnection.Result callback){app.engine.execute(ControlWire.request("agentShutdown"),callback);}
    synchronized void closed(){listening=false;message="电脑 Agent 入口已关闭，应用继续运行";}
}
