package dev.tripoder.hooklab;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** MCP dispatch shared by native HTTP clients and the desktop stdio adapter. */
final class AgentMcp implements AutoCloseable {
    static final String VERSION="2025-11-25";
    private final WorkbenchAgentControl control;
    private final Map<String,JSONObject> entries=new LinkedHashMap<>();
    private final Map<String,JSONObject> retired=new LinkedHashMap<>();
    private final Map<String,Session> sessions=new LinkedHashMap<>();
    // Binder transactions cannot be forcibly cancelled. Bound both local waits
    // and the number of possibly stuck transactions; never queue/replay commands.
    private final ThreadPoolExecutor dispatch=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>());
    private final long queryTimeoutMs,mutationTimeoutMs;
    private volatile boolean closed;
    private static final class Session {boolean ready;long used=System.currentTimeMillis();}
    record Reply(int status,JSONObject body,String session){}
    private record Call(Object id,JSONObject wire,boolean mutation,String session){}
    AgentMcp(Context context,WorkbenchAgentControl control)throws Exception{
        this(context,control,15000,60000);
    }
    AgentMcp(Context context,WorkbenchAgentControl control,long queryTimeoutMs,long mutationTimeoutMs)throws Exception{
        this.queryTimeoutMs=queryTimeoutMs;this.mutationTimeoutMs=mutationTimeoutMs;
        this.control=control;JSONObject contract;
        try(var input=context.getAssets().open("mcp-contract.json")){contract=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        if(!VERSION.equals(contract.getString("protocolVersion")))throw new IllegalArgumentException("MCP_CONTRACT_VERSION");
        JSONArray records=contract.getJSONArray("entries");for(int i=0;i<records.length();i++){JSONObject entry=records.getJSONObject(i),tool=entry.getJSONObject("tool");String name=tool.getString("name");entries.put(name,entry);JSONObject previous=tool.getJSONObject("_meta").optJSONObject("dev.tripoder/previousTools");if(previous!=null)for(var keys=previous.keys();keys.hasNext();){String old=keys.next();if(!old.equals(name))retired.put(old,new JSONObject().put("tool",name).put("action",previous.get(old)));}}
    }
    boolean remove(String id){synchronized(sessions){return sessions.remove(id)!=null;}}
    Reply handle(JSONObject request,String sessionId,String version,java.util.function.BooleanSupplier authorized)throws Exception{
        Object prepared;synchronized(sessions){prepared=prepare(request,sessionId,version,authorized);}
        if(prepared instanceof Reply reply)return reply;
        Call call=(Call)prepared;Future<JSONObject> future;
        try{
            future=dispatch.submit(()->{
                // Recheck revocation immediately before dispatch, outside network parsing.
                synchronized(sessions){if(closed||!authorized.getAsBoolean()||!sessions.containsKey(call.session))return unavailable(call,"MCP_SESSION_CLOSED",false);}
                return control.wire(call.wire);
            });
        }catch(RejectedExecutionException busy){return tool(call.id,unavailable(call,"MCP_DISPATCH_BUSY",false));}
        try{return callTool(call,future.get(call.mutation?mutationTimeoutMs:queryTimeoutMs,TimeUnit.MILLISECONDS));}
        catch(TimeoutException timeout){future.cancel(false);return tool(call.id,unavailable(call,"MCP_EXECUTION_TIMEOUT",true));}
        catch(InterruptedException interrupted){future.cancel(false);Thread.currentThread().interrupt();return tool(call.id,unavailable(call,"MCP_WAIT_INTERRUPTED",true));}
        catch(ExecutionException failed){return tool(call.id,unavailable(call,"MCP_DISPATCH_FAILED",true));}
    }
    private static JSONObject unavailable(Call call,String code,boolean dispatched)throws Exception{
        JSONObject receipt=new JSONObject().put("protocol",2).put("requestId",call.wire.getString("requestId")).put("ok",false).put("dispatched",dispatched).put("state",dispatched?(call.mutation?"UNKNOWN":"UNAVAILABLE"):"REJECTED").put("errorCode",code).put("error",code);
        if(call.mutation)receipt.put("operationId",call.wire.getString("requestId"));
        return receipt;
    }
    @Override public void close(){synchronized(sessions){closed=true;sessions.clear();}dispatch.shutdownNow();}
    private Object prepare(JSONObject request,String sessionId,String version,java.util.function.BooleanSupplier authorized)throws Exception{
        Object id=request.opt("id");
        if(closed||!authorized.getAsBoolean())return new Reply(401,null,null);
        if(!"2.0".equals(request.optString("jsonrpc"))||!(request.opt("method") instanceof String)||id!=null&&!(id instanceof String)&&!(id instanceof Number))return new Reply(400,error(id,-32600,"Invalid Request"),null);
        if(request.has("params")&&!(request.opt("params") instanceof JSONObject))return new Reply(400,error(id,-32602,"Object params required"),null);
        String method=request.getString("method");JSONObject params=request.optJSONObject("params");if(params==null)params=new JSONObject();
        if("initialize".equals(method)){
            if(id==null||!sessionId.isEmpty()||!VERSION.equals(params.optString("protocolVersion")))return new Reply(400,error(id,-32602,"MCP_INITIALIZE_INVALID"),null);
            long now=System.currentTimeMillis();sessions.values().removeIf(s->now-s.used>86400000L);if(sessions.size()>=64)return new Reply(503,error(id,-32000,"MCP_SESSION_LIMIT"),null);
            String created=UUID.randomUUID().toString();sessions.put(created,new Session());
            return new Reply(200,result(id,new JSONObject().put("protocolVersion",VERSION).put("capabilities",new JSONObject().put("tools",new JSONObject().put("listChanged",false))).put("serverInfo",new JSONObject().put("name","tripoder").put("version","0.3.0")).put("instructions","Global Tripoder control. Grouped tools require a fixed action enum and only its declared parameters. Every scoped call requires an explicit target. Unknown mutations require operation_get with the original operationId; never automatically replay. Disconnecting does not stop guests or disable Hooks.")),created);
        }
        Session session=sessions.get(sessionId);if(session==null)return new Reply(404,error(id,-32000,"MCP_SESSION_NOT_FOUND"),null);
        if(!VERSION.equals(version))return new Reply(400,error(id,-32602,"MCP_PROTOCOL_VERSION_REQUIRED"),null);session.used=System.currentTimeMillis();
        if(id==null){if("notifications/initialized".equals(method))session.ready=true;return new Reply(202,null,null);}
        if("ping".equals(method))return new Reply(200,result(id,new JSONObject()),null);
        if(!session.ready)return new Reply(400,error(id,-32000,"MCP_NOT_INITIALIZED"),null);
        if("tools/list".equals(method)){JSONArray list=new JSONArray();for(JSONObject entry:entries.values())list.put(entry.getJSONObject("tool"));return new Reply(200,result(id,new JSONObject().put("tools",list)),null);}
        if(!"tools/call".equals(method))return new Reply(200,error(id,-32601,"Method not found"),null);
        String name=params.optString("name");JSONObject entry=entries.get(name);if(entry==null){JSONObject replacement=retired.get(name);if(replacement!=null)return tool(id,new JSONObject().put("ok",false).put("state","REJECTED").put("dispatched",false).put("errorCode","TOOL_RETIRED").put("error","Refresh tools and use the replacement's action contract. This call was not dispatched.").put("replacement",replacement));return new Reply(200,error(id,-32602,"TOOL_NOT_FOUND"),null);}
        Object raw=params.has("arguments")?params.get("arguments"):new JSONObject();JSONObject receipt;
        JSONObject route=null;
        try{if(!(raw instanceof JSONObject))throw new IllegalArgumentException("arguments must be an object");JSONArray routes=entry.getJSONArray("routes");for(int i=0;i<routes.length();i++){JSONObject candidate=routes.getJSONObject(i);if(candidate.isNull("action")||candidate.getString("action").equals(((JSONObject)raw).optString("action"))){route=candidate;break;}}if(route==null)throw new IllegalArgumentException("INVALID_ACTION: "+name+" requires one of "+entry.getJSONObject("tool").getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("action").getJSONArray("enum"));AgentSchema.validate(raw,route.getJSONObject("inputSchema"),"arguments");AgentSchema.selectors((JSONObject)raw);}
        catch(Exception invalid){receipt=new JSONObject().put("protocol",2).put("ok",false).put("state","REJECTED").put("dispatched",false).put("errorCode","INVALID_TOOL_ARGUMENTS").put("error",invalid.getMessage());return tool(id,receipt);}
        JSONObject args=new JSONObject(raw.toString());boolean mutation=route.getBoolean("mutation");String operation=mutation?args.getString("operationId"):UUID.randomUUID().toString();if(mutation)args.remove("operationId");if(!route.isNull("action"))args.remove("action");
        JSONObject wire=new JSONObject().put("protocol",2).put("requestId",operation).put("method",route.getString("method")).put("params",args);
        return new Call(id,wire,mutation,sessionId);
    }
    private static Reply callTool(Call call,JSONObject receipt)throws Exception{
        Reply reply=tool(call.id,receipt);if(reply.body()!=null&&receipt.has("recovery")&&call.wire.getJSONObject("params").has("target")&&"HOOK_OUTCOME_QUERY_REQUIRED".equals(receipt.optString("errorCode"))){JSONArray steps=receipt.getJSONObject("recovery").getJSONArray("steps");steps.put(new JSONObject().put("tool","process_inspect").put("arguments",new JSONObject().put("action","hook_status").put("target",call.wire.getJSONObject("params").getJSONObject("target"))));return new Reply(200,result(call.id,new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",receipt.toString()))).put("isError",!receipt.optBoolean("ok"))),null);}return reply;
    }
    private static Reply tool(Object id,JSONObject receipt)throws Exception{
        if(!receipt.optBoolean("ok")||"UNKNOWN".equals(receipt.optJSONObject("result")==null?"":receipt.getJSONObject("result").optString("state"))){
            String operation=receipt.optString("operationId"),error=receipt.optString("error");JSONArray steps=new JSONArray();String action="Inspect the error and exact target before issuing a command.";
            java.util.regex.Matcher pending=java.util.regex.Pattern.compile("HOOK_OUTCOME_QUERY_REQUIRED:([A-Za-z0-9_-]{1,80})").matcher(error);
            if(pending.find()){operation=pending.group(1);receipt.put("pendingOperationId",operation);receipt.put("errorCode","HOOK_OUTCOME_QUERY_REQUIRED");steps.put(new JSONObject().put("tool","operation_get").put("arguments",new JSONObject().put("operationId",operation)));action="Query the original operation and process_inspect(action=hook_status). Never replay an unknown mutation.";}
            else if("UNKNOWN".equals(receipt.optString("state"))&&!operation.isEmpty()){steps.put(new JSONObject().put("tool","operation_get").put("arguments",new JSONObject().put("operationId",operation)));action="Query the original operationId; do not replay.";}
            else if(receipt.optBoolean("ok")){action="The original journal still records UNKNOWN. Inspect actual state; do not assume success or replay.";}
            receipt.put("recovery",new JSONObject().put("action",action).put("steps",steps).put("replayAllowed",false));
        }
        return new Reply(200,result(id,new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",receipt.toString()))).put("isError",!receipt.optBoolean("ok"))),null);
    }
    static JSONObject result(Object id,JSONObject result)throws Exception{return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",result);}
    static JSONObject error(Object id,int code,String message)throws Exception{return new JSONObject().put("jsonrpc","2.0").put("id",id==null?JSONObject.NULL:id).put("error",new JSONObject().put("code",code).put("message",message));}
}
