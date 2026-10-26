package dev.tripoder.hooklab;

import java.nio.file.*;
import java.util.concurrent.*;
import org.json.*;

/** Runs the production schema and dispatcher, with a deliberately blocked control adapter. */
public final class AgentMcpConcurrencyTest {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static JSONObject rpc(int id,String method,JSONObject params)throws Exception{return new JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params);}
    private static String initialize(AgentMcp mcp)throws Exception{
        String session=mcp.handle(rpc(1,"initialize",new JSONObject().put("protocolVersion",AgentMcp.VERSION)),"","").session();
        mcp.handle(new JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),session,AgentMcp.VERSION);return session;
    }
    private static JSONObject receipt(AgentMcp.Reply reply)throws Exception{return new JSONObject(reply.body().getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"));}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);JSONObject contract=new JSONObject(Files.readString(root.resolve("assets/mcp-contract.json")));JSONArray entries=contract.getJSONArray("entries"),cases=new JSONArray(Files.readString(root.resolve("schema-cases.json")));
        int tested=0;for(int i=0;i<cases.length();i++){
            JSONObject fixture=cases.getJSONObject(i),schema=null;
            // application_import is the stdio-only local tool, validated in Node.
            for(int j=0;j<entries.length();j++){JSONObject tool=entries.getJSONObject(j).getJSONObject("tool");if(tool.getString("name").equals(fixture.getString("name"))){schema=tool.getJSONObject("inputSchema");}}
            if(schema==null)continue;boolean valid=true;
            try{AgentSchema.validate(fixture.getJSONObject("args"),schema,"arguments");AgentSchema.selectors(fixture.getJSONObject("args"));}catch(IllegalArgumentException rejected){valid=false;}
            check(valid==fixture.getBoolean("valid"),"schema disagreement: "+fixture);tested++;
        }
        for(boolean mutation:new boolean[]{false,true}){
            WorkbenchAgentControl control=new WorkbenchAgentControl();control.blockMethod=mutation?"space.rename":"runtime.resources";
            try(AgentMcp mcp=new AgentMcp(new android.content.Context(root.resolve("assets")),control,200,200)){
                String session=initialize(mcp);ExecutorService caller=Executors.newSingleThreadExecutor();
                JSONObject arguments=mutation?new JSONObject().put("action","rename").put("operationId","blocked-operation").put("space","space-a").put("name","Space"):new JSONObject().put("action","resources");
                try{
                    Future<AgentMcp.Reply> slow=caller.submit(()->mcp.handle(rpc(2,"tools/call",new JSONObject().put("name",mutation?"space_manage":"runtime_query").put("arguments",arguments)),session,AgentMcp.VERSION));
                    check(control.started.await(2,TimeUnit.SECONDS),"control did not start");
                    check(mcp.handle(rpc(3,"ping",new JSONObject()),session,AgentMcp.VERSION).status()==200,"ping blocked");
                    check(mcp.handle(rpc(4,"tools/list",new JSONObject()),session,AgentMcp.VERSION).status()==200,"discovery blocked");
                    JSONObject timeout=receipt(slow.get(2,TimeUnit.SECONDS));
                    check(timeout.getString("errorCode").equals("MCP_EXECUTION_TIMEOUT"),"missing timeout");
                    check(timeout.getString("state").equals(mutation?"UNKNOWN":"UNAVAILABLE"),"incorrect certainty");
                    if(mutation){check(timeout.getString("operationId").equals("blocked-operation"),"lost operation id");check(!timeout.getJSONObject("recovery").getBoolean("replayAllowed"),"allowed replay");}
                    check(receipt(mcp.handle(rpc(5,"tools/call",new JSONObject().put("name","runtime_query").put("arguments",new JSONObject().put("action","status"))),session,AgentMcp.VERSION)).getBoolean("ok"),"unrelated query blocked");
                    check(mcp.remove(session),"session removal blocked");
                    check(mcp.handle(rpc(6,"ping",new JSONObject()),session,AgentMcp.VERSION).status()==404,"removed session accepted");
                }finally{control.release.countDown();caller.shutdownNow();}
            }
            // Saturation uses fresh workers: a completed Future does not prove
            // its SynchronousQueue worker has returned to waiting for a handoff.
            // Do not race the unrelated status query's returning worker above.
            WorkbenchAgentControl saturated=new WorkbenchAgentControl();saturated.blockMethod=mutation?"space.rename":"runtime.resources";
            try(AgentMcp mcp=new AgentMcp(new android.content.Context(root.resolve("assets")),saturated,200,200)){
                String session=initialize(mcp);
                try{
                    for(int i=0;i<2;i++){
                        JSONObject arguments=mutation?new JSONObject().put("action","rename").put("operationId","saturated-"+i).put("space","space-a").put("name","Space"):new JSONObject().put("action","resources");
                        JSONObject timeout=receipt(mcp.handle(rpc(7+i,"tools/call",new JSONObject().put("name",mutation?"space_manage":"runtime_query").put("arguments",arguments)),session,AgentMcp.VERSION));
                        check(timeout.optString("errorCode").equals("MCP_EXECUTION_TIMEOUT"),"blocked call: "+timeout);
                    }
                    JSONObject busy=receipt(mcp.handle(rpc(9,"tools/call",new JSONObject().put("name","runtime_query").put("arguments",new JSONObject().put("action","status"))),session,AgentMcp.VERSION));
                    check(busy.has("dispatched")&&!busy.getBoolean("dispatched")&&busy.getString("errorCode").equals("MCP_DISPATCH_BUSY"),"unbounded dispatch or hidden queue: "+busy);
                }finally{saturated.release.countDown();}
            }
        }
        AgentMcp closed=new AgentMcp(new android.content.Context(root.resolve("assets")),new WorkbenchAgentControl());
        closed.close();check(closed.handle(rpc(1,"initialize",new JSONObject().put("protocolVersion",AgentMcp.VERSION)),"","").status()==503,"closed endpoint accepted initialization");
        JSONObject javaSelector=new JSONObject("{\"kind\":\"java\",\"argumentTypes\":[\"void\"]}");
        JSONObject selectorSchema=new JSONObject("{\"anyOf\":[{\"type\":\"object\",\"properties\":{\"kind\":{\"enum\":[\"java\"]},\"argumentTypes\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"int\"]}}}},{\"type\":\"object\",\"properties\":{\"kind\":{\"enum\":[\"native\"]}},\"required\":[\"moduleName\"]}]}");
        try{AgentSchema.validate(javaSelector,selectorSchema,"/selectors/0");throw new AssertionError("invalid Java type accepted");}catch(AgentSchema.Invalid invalid){check(invalid.issues.getJSONObject(0).getString("path").equals("/selectors/0/argumentTypes/0"),"lost leaf JSON pointer");check(!invalid.getMessage().contains("moduleName"),"unrelated native branch error");}
        JSONObject original=new JSONObject().put("ok",false).put("state","UNKNOWN").put("operationId","original").put("error","IllegalStateException:WORKER_DIED").put("result","kept").put("evidence","proof");
        JSONObject identity=new JSONObject().put("space","s").put("package","owned.app").put("installGeneration","g");
        JSONObject guided=AgentRecovery.enrich(original,"process.start",new JSONObject().put("target",identity));
        check(guided.getString("result").equals("kept")&&guided.getString("evidence").equals("proof"),"recovery replaced original evidence");
        check(guided.getString("errorCode").equals("WORKER_DIED"),"recovery lost native error code");
        check(!guided.getJSONObject("recovery").getBoolean("newIdRetryAllowed"),"new-ID blind retry allowed");
        check(guided.getJSONObject("recovery").getJSONArray("steps").length()==4,"launch recovery steps missing");
        JSONObject blocked=AgentRecovery.enrich(new JSONObject().put("ok",false).put("state","REJECTED").put("operationId","new-rejected").put("error","IllegalStateException:RECOVERY_SCOPE_UNKNOWN: original"),"probes.configure",new JSONObject().put("target",identity));
        check(blocked.getString("pendingOperationId").equals("original"),"scope error points to rejected id");
        System.out.println("Java schema cases passed: "+tested+"; blocked query/mutation timeout, discovery, ping and revocation passed");
    }
}
