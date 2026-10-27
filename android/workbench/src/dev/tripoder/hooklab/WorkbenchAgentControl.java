package dev.tripoder.hooklab;

import android.os.Bundle;
import dev.tripoder.control.*;
import org.json.*;

/** Validated MCP commands dispatched through the existing control pipeline. */
final class WorkbenchAgentControl {
    private final WorkbenchApplication app;
    volatile String message="电脑 Agent 入口尚未启动";
    volatile boolean listening;
    volatile AgentNetworkIdentity networkIdentity;
    private final WorkbenchAgentLifecycle lifecycle;private final WorkbenchAgentImports imports;
    private WorkbenchFileExports exports;
    private synchronized WorkbenchFileExports exports()throws Exception{if(exports==null)exports=new WorkbenchFileExports(app);return exports;}
    WorkbenchAgentControl(WorkbenchApplication app){this.app=app;lifecycle=new WorkbenchAgentLifecycle(app);imports=new WorkbenchAgentImports(app);}
    private static final java.util.Set<String> VISIBLE_MUTATIONS=java.util.Set.of("space.create","space.rename","space.delete","application.remove","application.delete","application.reverify","application.importDevice","import.commit","project.scriptPut","project.selectorsPut","project.archiveRestore","project.migrationAccept","launch.abandon","launch.close","probes.configure","process.start","application.stop","hook.observe","hook.prepare","hook.activate","hook.disable","hook.discard","hook.updateParameters","hook.uiAction","hook.httpRequest","hook.networkStart","hook.networkStop","hook.networkConfigure","hook.componentSend","hook.providerInteract","hook.privacyConfigureAttribution","hook.privacyMarkConsentStage","hook.personaConfigureTelephony","hook.personaConfigureLocation","hook.personaConfigureBattery","hook.personaConfigureLocale","hook.personaConfigureNetwork","hook.malwareConfigure","hook.malwareClear","hook.timeWarpConfigure","hook.timeWarpReset","hook.honeydataConfigure","hook.honeydataClear","hook.processSnapshotHeap","hook.processSnapshotFreeze","hook.envMockConfigureHttp","hook.envMockConfigureNetwork","hook.envMockClear","hook.guestInvoke","hook.probeStop","hook.classLoaderCreate","hook.dexSnapshot");
    JSONObject wire(JSONObject request)throws Exception{
        String id=request.optString("requestId","");
        try{
            if(!listening)throw new IllegalStateException("AGENT_ENDPOINT_CLOSED");
            AgentCrypto.id(id);if(request.getInt("protocol")!=AgentCrypto.PROTOCOL)throw new IllegalArgumentException("AGENT_PROTOCOL_UNSUPPORTED");
            AgentCrypto.fields(request,"protocol","requestId","method","params");
            String method=request.getString("method");JSONObject params=request.getJSONObject("params"),result;Bundle output;
            if(method.equals("operation.get")){
                AgentCrypto.fields(params,"operationId");String operation=AgentCrypto.id(params.getString("operationId"));result=lifecycle.receiptIfPresent(operation);
                if(result==null)result=exports().store.receipt(operation);
                if(result==null)result=new JSONObject(app.engine.executeBlocking(ControlWire.request("agentOperation","operation",operation)).getString("result"));
                // A local edit interrupted before its terminal receipt remains uncertain.
                if("RUNNING".equals(result.optString("state")))result.put("state","UNKNOWN");
                lifecycle.cleanupReceipt(result);
            }else if(method.startsWith("file.")){
                if(method.equals("file.exportBegin")&&lifecycle.receiptIfPresent(id)!=null)throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");
                result=exports().call(method,params,id);
            }else if(method.startsWith("import.")||method.equals("application.importDevice")||method.equals("application.importStatus")){
                if(method.equals("import.cleanup"))result=lifecycle.mutate(id,method,params,()->imports.call(method,params,id));else{if(lifecycle.receiptIfPresent(id)!=null)throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");result=imports.call(method,params,id);}
            }else if(method.equals("runtime.capabilities")){
                AgentCrypto.fields(params);result=new JSONObject(app.engine.executeBlocking(ControlWire.request("agentCapabilities")).getString("result"));result.put("filePull",WorkbenchFileExports.capabilities()).put("authenticationRequired",false).put("mcpTransports",new JSONArray().put("stdio").put("streamable-http")).put("globalControl",true).put("explicitTargets",true).put("implicitTargetSelection",false);
            }else if(method.equals("targets.list")){
                AgentCrypto.fields(params);result=new JSONObject(app.engine.executeBlocking(ControlWire.request("agentTargets")).getString("result")).put("authenticationRequired",false);
            }else if(method.equals("artifact.list")){
                AgentCrypto.fields(params,"processGeneration","cursor","limit");result=dev.tripoder.runtime.RuntimeCodeArtifacts.list(app,params.getString("processGeneration"),params.getInt("cursor"),params.getInt("limit"));
            }else if(method.equals("artifact.read")){
                AgentCrypto.fields(params,"processGeneration","artifactId","offset","length");result=dev.tripoder.runtime.RuntimeCodeArtifacts.read(app,params.getString("processGeneration"),params.getString("artifactId"),params.getInt("offset"),params.getInt("length"));
            }else if(java.util.Set.of("probes.get","probes.configure","process.start","application.stop").contains(method)){
                if(lifecycle.receiptIfPresent(id)!=null)throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");
                switch(method){
                    case "probes.get":AgentCrypto.fields(params,"target");break;
                    case "probes.configure":AgentCrypto.fields(params,"target","expectedRevision","configuration");break;
                    case "process.start":AgentCrypto.fields(params,"target","component","probeRevision");break;
                    default:AgentCrypto.fields(params,"target");
                }
                Bundle target=lifecycle.target(params.getJSONObject("target"),false);String project=target.getString("project"),binding=target.getString("bindingOperation");
                if(method.equals("probes.get"))output=app.engine.executeBlocking(ControlWire.request("agentProbeGet","project",project,"bindingOperation",binding));
                else if(method.equals("probes.configure"))output=app.engine.executeBlocking(ControlWire.request("agentProbeConfigure","operation",id,"project",project,"bindingOperation",binding,"expectedRevision",params.getString("expectedRevision"),"configuration",params.getJSONObject("configuration").toString()));
                else if(method.equals("application.stop"))output=app.engine.executeBlocking(ControlWire.request("agentStopApplication","operation",id,"project",project,"bindingOperation",binding,"installGeneration",target.getString("generation")));
                else{
                    output=app.engine.executeBlocking(ControlWire.request("agentPrepareLaunch","operation",id,"project",project,"bindingOperation",binding,"component",params.getString("component"),"probeRevision",params.getString("probeRevision")));
                    android.content.Intent intent=output.getParcelable("intent",android.content.Intent.class);if(intent==null)throw new IllegalStateException("PROCESS_ENTRY_MISSING");
                    try{app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception failure){throw new IllegalStateException("PROCESS_ENTRY_DISPATCH_UNKNOWN_QUERY_OPERATION",failure);}
                    return success(id,new JSONObject().put("target",params.getJSONObject("target")).put("processGeneration",output.getString("generation")).put("pid",output.getInt("pid")).put("probeRevision",output.getString("probeRevision")).put("entryDispatchRequested",true).put("operationId",id));
                }
                result=new JSONObject(output.getString("result"));
            }else if(method.startsWith("hook.")||method.equals("process.get")){
                if(lifecycle.receiptIfPresent(id)!=null)throw new IllegalArgumentException("AGENT_REQUEST_ID_CONTENT_MISMATCH");
                return new JSONObject(app.engine.executeBlocking(ControlWire.request("agentExplicit","payload",request.toString())).getString("result"));
            }else result=lifecycle.call(method,params,id);
            return success(id,result);
        }catch(Exception error){
            JSONObject failure=new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",id).put("ok",false).put("error",error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()));
            if(java.util.Set.of("file.exportBegin","file.exportCancel","file.exportRelease").contains(request.optString("method"))){
                boolean rejected=false;if("file.exportBegin".equals(request.optString("method")))try{rejected=exports().store.receipt(id)==null;}catch(Exception unavailable){}
                failure.put("operationId",id).put("state",rejected?"REJECTED":"UNKNOWN");if(rejected)failure.put("dispatched",false);return failure;
            }
            if(java.util.Set.of("space.create","space.rename","space.delete","application.remove","application.delete","application.reverify","application.importDevice","import.begin","import.commit","import.write","import.cleanup","project.scriptPut","project.selectorsPut","project.archiveRestore","project.migrationAccept","launch.abandon","launch.close","probes.configure","process.start","application.stop","hook.observe","hook.prepare","hook.activate","hook.disable","hook.discard","hook.updateParameters","hook.uiAction","hook.httpRequest","hook.networkStart","hook.networkStop","hook.networkConfigure","hook.componentSend","hook.providerInteract","hook.privacyConfigureAttribution","hook.privacyMarkConsentStage","hook.personaConfigureTelephony","hook.personaConfigureLocation","hook.personaConfigureBattery","hook.personaConfigureLocale","hook.personaConfigureNetwork","hook.malwareConfigure","hook.malwareClear","hook.timeWarpConfigure","hook.timeWarpReset","hook.honeydataConfigure","hook.honeydataClear","hook.processSnapshotHeap","hook.processSnapshotFreeze","hook.envMockConfigureHttp","hook.envMockConfigureNetwork","hook.envMockClear","hook.guestInvoke","hook.probeStop","hook.classLoaderCreate","hook.dexSnapshot").contains(request.optString("method")))try{JSONObject row=lifecycle.receiptIfPresent(id);if(row==null)row=new JSONObject(app.engine.executeBlocking(ControlWire.request("agentOperation","operation",id)).getString("result"));failure.put("operationId",id).put("state","RUNNING".equals(row.optString("state"))?"UNKNOWN":row.optString("state","UNKNOWN"));}catch(Exception notAccepted){failure.put("operationId",id).put("state",String.valueOf(notAccepted.getMessage()).contains("OPERATION_NOT_FOUND")?"REJECTED":"UNKNOWN");}
            else failure.put("state","REJECTED");
            return failure;
        }finally{
            String method=request.optString("method");if(VISIBLE_MUTATIONS.contains(method)){
                JSONObject target=null;if(method.startsWith("hook.")||java.util.Set.of("application.stop","application.remove","process.start").contains(method))try{target=request.getJSONObject("params").getJSONObject("target");}catch(Exception invalid){}
                app.controlChanged(target);
            }
        }
    }
    private static JSONObject success(String id,JSONObject result)throws Exception{return new JSONObject().put("protocol",AgentCrypto.PROTOCOL).put("requestId",id).put("ok",true).put("result",result);}
    void revoke(EngineConnection.Result callback){app.engine.execute(ControlWire.request("agentShutdown"),callback);}
    synchronized void closed(){listening=false;message="电脑 Agent 入口已关闭，应用继续运行";}
}
