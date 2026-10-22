package dev.tripoder.hooklab;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** MCP dispatch shared by native HTTP clients and the desktop stdio adapter. */
final class AgentMcp {
    static final String VERSION="2025-11-25";
    private final WorkbenchAgentControl control;
    private final Map<String,JSONObject> entries=new LinkedHashMap<>();
    private final Map<String,Session> sessions=new LinkedHashMap<>();
    private static final class Session {boolean ready;long used=System.currentTimeMillis();}
    record Reply(int status,JSONObject body,String session){}
    AgentMcp(Context context,WorkbenchAgentControl control)throws Exception{
        this.control=control;JSONObject contract;
        try(var input=context.getAssets().open("mcp-contract.json")){contract=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        if(!VERSION.equals(contract.getString("protocolVersion")))throw new IllegalArgumentException("MCP_CONTRACT_VERSION");
        JSONArray records=contract.getJSONArray("entries");for(int i=0;i<records.length();i++){JSONObject entry=records.getJSONObject(i);entries.put(entry.getJSONObject("tool").getString("name"),entry);}
    }
    synchronized boolean remove(String id){return sessions.remove(id)!=null;}
    synchronized Reply handle(JSONObject request,String sessionId,String version,java.util.function.BooleanSupplier authorized)throws Exception{
        Object id=request.opt("id");
        if(!authorized.getAsBoolean())return new Reply(401,null,null);
        if(!"2.0".equals(request.optString("jsonrpc"))||!(request.opt("method") instanceof String)||id!=null&&!(id instanceof String)&&!(id instanceof Number))return new Reply(400,error(id,-32600,"Invalid Request"),null);
        if(request.has("params")&&!(request.opt("params") instanceof JSONObject))return new Reply(400,error(id,-32602,"Object params required"),null);
        String method=request.getString("method");JSONObject params=request.optJSONObject("params");if(params==null)params=new JSONObject();
        if("initialize".equals(method)){
            if(id==null||!sessionId.isEmpty()||!VERSION.equals(params.optString("protocolVersion")))return new Reply(400,error(id,-32602,"MCP_INITIALIZE_INVALID"),null);
            long now=System.currentTimeMillis();sessions.values().removeIf(s->now-s.used>86400000L);if(sessions.size()>=64)return new Reply(503,error(id,-32000,"MCP_SESSION_LIMIT"),null);
            String created=UUID.randomUUID().toString();sessions.put(created,new Session());
            return new Reply(200,result(id,new JSONObject().put("protocolVersion",VERSION).put("capabilities",new JSONObject().put("tools",new JSONObject().put("listChanged",false))).put("serverInfo",new JSONObject().put("name","tripoder").put("version","0.2.0")).put("instructions","Global Tripoder control. Every scoped call requires an explicit target. Unknown mutations require operation_get with the original operationId; never automatically replay. Disconnecting does not stop guests or disable Hooks.")),created);
        }
        Session session=sessions.get(sessionId);if(session==null)return new Reply(404,error(id,-32000,"MCP_SESSION_NOT_FOUND"),null);
        if(!VERSION.equals(version))return new Reply(400,error(id,-32602,"MCP_PROTOCOL_VERSION_REQUIRED"),null);session.used=System.currentTimeMillis();
        if(id==null){if("notifications/initialized".equals(method))session.ready=true;return new Reply(202,null,null);}
        if("ping".equals(method))return new Reply(200,result(id,new JSONObject()),null);
        if(!session.ready)return new Reply(400,error(id,-32000,"MCP_NOT_INITIALIZED"),null);
        if("tools/list".equals(method)){JSONArray list=new JSONArray();for(JSONObject entry:entries.values())list.put(entry.getJSONObject("tool"));return new Reply(200,result(id,new JSONObject().put("tools",list)),null);}
        if(!"tools/call".equals(method))return new Reply(200,error(id,-32601,"Method not found"),null);
        String name=params.optString("name");JSONObject entry=entries.get(name);if(entry==null)return new Reply(200,error(id,-32602,"TOOL_NOT_FOUND"),null);
        Object raw=params.has("arguments")?params.get("arguments"):new JSONObject();JSONObject receipt;
        try{AgentSchema.validate(raw,entry.getJSONObject("tool").getJSONObject("inputSchema"),"arguments");AgentSchema.selectors((JSONObject)raw);}
        catch(Exception invalid){receipt=new JSONObject().put("protocol",2).put("ok",false).put("state","REJECTED").put("dispatched",false).put("errorCode","INVALID_TOOL_ARGUMENTS").put("error",invalid.getMessage());return tool(id,receipt);}
        JSONObject args=new JSONObject(raw.toString());boolean mutation=entry.getBoolean("mutation");String operation=mutation?args.getString("operationId"):UUID.randomUUID().toString();if(mutation)args.remove("operationId");
        JSONObject wire=new JSONObject().put("protocol",2).put("requestId",operation).put("method",entry.getString("method")).put("params",args);
        receipt=control.wire(wire);return tool(id,receipt);
    }
    private static Reply tool(Object id,JSONObject receipt)throws Exception{
        if(!receipt.optBoolean("ok")||"UNKNOWN".equals(receipt.optJSONObject("result")==null?"":receipt.getJSONObject("result").optString("state"))){
            String operation=receipt.optString("operationId"),error=receipt.optString("error");JSONArray steps=new JSONArray();String action="Inspect the error and exact target before issuing a command.";
            java.util.regex.Matcher pending=java.util.regex.Pattern.compile("HOOK_OUTCOME_QUERY_REQUIRED:([A-Za-z0-9_-]{1,80})").matcher(error);
            if(pending.find()){operation=pending.group(1);receipt.put("pendingOperationId",operation);receipt.put("errorCode","HOOK_OUTCOME_QUERY_REQUIRED");steps.put(new JSONObject().put("tool","operation_get").put("arguments",new JSONObject().put("operationId",operation)));action="Query the original operation and hook_status. Never replay an unknown mutation.";}
            else if("UNKNOWN".equals(receipt.optString("state"))&&!operation.isEmpty()){steps.put(new JSONObject().put("tool","operation_get").put("arguments",new JSONObject().put("operationId",operation)));action="Query the original operationId; do not replay.";}
            else if(receipt.optBoolean("ok")){action="The original journal still records UNKNOWN. Inspect actual state; do not assume success or replay.";}
            receipt.put("recovery",new JSONObject().put("action",action).put("steps",steps).put("replayAllowed",false));
        }
        return new Reply(200,result(id,new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",receipt.toString()))).put("isError",!receipt.optBoolean("ok"))),null);
    }
    static JSONObject result(Object id,JSONObject result)throws Exception{return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",result);}
    static JSONObject error(Object id,int code,String message)throws Exception{return new JSONObject().put("jsonrpc","2.0").put("id",id==null?JSONObject.NULL:id).put("error",new JSONObject().put("code",code).put("message",message));}
}
