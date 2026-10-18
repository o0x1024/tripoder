package dev.tripoder.hooklab;

import android.os.*;
import dev.tripoder.control.*;
import org.json.*;
import java.util.*;

/** UI authorizes; the transport only verifies identity and forwards signed bounded commands. */
final class WorkbenchAgentControl {
    static final class Pairing {
        final String id,name,key,fingerprint;final long expires=SystemClock.elapsedRealtime()+120_000;
        String session="",project="",failure="";boolean approving;
        Pairing(String id,String name,String key)throws Exception{this.id=id;this.name=name;this.key=key;fingerprint=AgentCrypto.fingerprint(key);}
    }
    private final WorkbenchApplication app;
    private AgentDeviceKey key;
    private Pairing pending;
    volatile String message="外部控制已关闭",session="",project="";
    volatile boolean listening;
    WorkbenchAgentControl(WorkbenchApplication app){this.app=app;}
    synchronized AgentDeviceKey deviceKey()throws Exception{if(key==null)key=new AgentDeviceKey();return key;}
    synchronized Pairing pending(){return pending;}
    JSONObject wire(JSONObject envelope)throws Exception{
        AgentCrypto.fields(envelope,"kind","id","payload","signature");
        String kind=AgentCrypto.text(envelope,"kind"),id=AgentCrypto.id(AgentCrypto.text(envelope,"id")),payload=AgentCrypto.text(envelope,"payload"),signature=AgentCrypto.text(envelope,"signature");
        JSONObject result;
        try{
            if(!listening)throw new IllegalStateException("AGENT_ENDPOINT_CLOSED");
            if(kind.equals("pair"))result=pair(id,payload,signature);
            else if(kind.equals("call")){
                if(!id.equals(session))throw new SecurityException("AGENT_SESSION_NOT_PAIRED");
                Bundle output=app.engine.executeBlocking(ControlWire.request("agentCall","session",id,"payload",payload,"signature",signature));result=new JSONObject(output.getString("result"));
            }else throw new IllegalArgumentException("AGENT_ENVELOPE_KIND_UNSUPPORTED");
        }catch(Exception error){
            String requestId="";try{requestId=AgentCrypto.id(new JSONObject(payload).getString("requestId"));}catch(Exception invalid){/* No valid correlation id was supplied. */}
            result=new JSONObject().put("protocol",1).put("requestId",requestId).put("ok",false).put("error",error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()));
        }
        String response=result.toString();AgentDeviceKey identity=deviceKey();return new JSONObject().put("id",id).put("publicKey",identity.publicKey).put("payload",response).put("signature",identity.sign(id,response));
    }
    private synchronized JSONObject pair(String id,String payload,String signature)throws Exception{
        if(payload.length()>1024)throw new IllegalArgumentException("AGENT_PAIR_SIZE");JSONObject value=new JSONObject(payload);AgentCrypto.fields(value,"protocol","nonce","name","publicKey");
        if(value.getInt("protocol")!=1||!id.equals(AgentCrypto.text(value,"nonce")))throw new SecurityException("AGENT_PAIR_PROTOCOL_OR_NONCE");
        String publicKey=AgentCrypto.text(value,"publicKey"),name=AgentCrypto.text(value,"name");
        if(!name.matches("[A-Za-z0-9 _.-]{1,60}"))throw new IllegalArgumentException("AGENT_NAME_INVALID");
        AgentCrypto.verify(AgentCrypto.publicKey(publicKey),"pair",id,payload,signature);
        if(pending!=null&&pending.id.equals(id)){
            if(!pending.key.equals(publicKey)||!pending.name.equals(name))throw new SecurityException("AGENT_PAIR_REQUEST_CHANGED");
        }else{
            if(!session.isEmpty())throw new IllegalStateException("AGENT_SESSION_ALREADY_PAIRED");
            if(pending!=null&&SystemClock.elapsedRealtime()<pending.expires)throw new IllegalStateException("AGENT_PAIR_REQUEST_PENDING");
            pending=new Pairing(id,name,publicKey);
        }
        if(pending.session.isEmpty()&&SystemClock.elapsedRealtime()>pending.expires)throw new SecurityException("AGENT_PAIR_REQUEST_EXPIRED");
        return new JSONObject().put("protocol",1).put("nonce",id).put("ok",pending.failure.isEmpty()).put("state",pending.session.isEmpty()?"PENDING_PHONE_APPROVAL":"PAIRED").put("session",pending.session).put("project",pending.project).put("error",pending.failure);
    }
    void approve(Pairing request,String project,Bundle target,JSONObject process,EngineConnection.Result callback)throws Exception{
        approve(request,project,target,process,false,callback);
    }
    void approve(Pairing request,String project,Bundle target,JSONObject process,boolean autoApprove,EngineConnection.Result callback)throws Exception{
        synchronized(this){if(pending!=request||request.approving||!request.session.isEmpty()||SystemClock.elapsedRealtime()>request.expires)throw new IllegalStateException("AGENT_PAIR_REQUEST_STALE");request.approving=true;}
        Bundle input=ControlWire.request("agentGrant","operation",UUID.randomUUID().toString(),"session",request.id,"publicKey",request.key,"project",project,"bindingOperation",target.getString("bindingOperation"),"generation",target.getString("generation"),"processGeneration",process.getString("processGeneration"),"autoApprove",String.valueOf(autoApprove));
        app.engine.execute(input,(reply,error)->{
            synchronized(this){request.approving=false;if(error==null){session=request.session=request.id;this.project=request.project=project;message=autoApprove?"外部会话已授权（会话内自动部署）":"外部会话已授权 · 部署仍需审阅脚本";}else{request.failure=error.getMessage();message="授权没有完成："+UiText.error(error);}}
            callback.complete(reply,error);
        });
    }
    void revoke(EngineConnection.Result callback){
        String id=session;if(id.isEmpty()){callback.complete(new Bundle(),null);return;}
        app.engine.execute(ControlWire.request("agentRevoke","session",id),(reply,error)->{message=error==null?"已撤销会话，正在确认应用停止":"撤销结果待确认："+UiText.error(error);callback.complete(reply,error);});
    }
    synchronized void closed(){listening=false;pending=null;session="";project="";message="外部控制已关闭；引擎独立处理授权撤销与到期";}
}
