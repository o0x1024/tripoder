package dev.tripoder.hooklab;

import java.nio.file.*;
import java.util.concurrent.*;
import org.json.*;

/** Runs the production schema and dispatcher, with a deliberately blocked control adapter. */
public final class AgentMcpConcurrencyTest {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static JSONObject rpc(int id,String method,JSONObject params)throws Exception{return new JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params);}
    private static String initialize(AgentMcp mcp)throws Exception{
        String session=mcp.handle(rpc(1,"initialize",new JSONObject().put("protocolVersion",AgentMcp.VERSION)),"","",()->true).session();
        mcp.handle(new JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized"),session,AgentMcp.VERSION,()->true);return session;
    }
    private static JSONObject receipt(AgentMcp.Reply reply)throws Exception{return new JSONObject(reply.body().getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"));}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);JSONObject contract=new JSONObject(Files.readString(root.resolve("assets/mcp-contract.json")));JSONArray entries=contract.getJSONArray("entries"),cases=new JSONArray(Files.readString(root.resolve("schema-cases.json")));
        int tested=0;for(int i=0;i<cases.length();i++){
            JSONObject fixture=cases.getJSONObject(i),schema=null;
            // application_import is the stdio-only local tool, validated in Node.
            for(int j=0;j<entries.length();j++){JSONObject tool=entries.getJSONObject(j).getJSONObject("tool");if(tool.getString("name").equals(fixture.getString("name")))schema=tool.getJSONObject("inputSchema");}
            if(schema==null)continue;boolean valid=true;
            try{AgentSchema.validate(fixture.getJSONObject("args"),schema,"arguments");AgentSchema.selectors(fixture.getJSONObject("args"));}catch(IllegalArgumentException rejected){valid=false;}
            check(valid==fixture.getBoolean("valid"),"schema disagreement: "+fixture);tested++;
        }
        for(boolean mutation:new boolean[]{false,true}){
            WorkbenchAgentControl control=new WorkbenchAgentControl();control.blockMethod=mutation?"space.rename":"runtime.resources";
            try(AgentMcp mcp=new AgentMcp(new android.content.Context(root.resolve("assets")),control,200,200)){
                String session=initialize(mcp);ExecutorService caller=Executors.newSingleThreadExecutor();
                JSONObject arguments=mutation?new JSONObject().put("operationId","blocked-operation").put("space","space-a").put("name","Space"):new JSONObject();
                try{
                    Future<AgentMcp.Reply> slow=caller.submit(()->mcp.handle(rpc(2,"tools/call",new JSONObject().put("name",mutation?"space_rename":"runtime_resources").put("arguments",arguments)),session,AgentMcp.VERSION,()->true));
                    check(control.started.await(2,TimeUnit.SECONDS),"control did not start");
                    check(mcp.handle(rpc(3,"ping",new JSONObject()),session,AgentMcp.VERSION,()->true).status()==200,"ping blocked");
                    check(mcp.handle(rpc(4,"tools/list",new JSONObject()),session,AgentMcp.VERSION,()->true).status()==200,"discovery blocked");
                    JSONObject timeout=receipt(slow.get(2,TimeUnit.SECONDS));
                    check(timeout.getString("errorCode").equals("MCP_EXECUTION_TIMEOUT"),"missing timeout");
                    check(timeout.getString("state").equals(mutation?"UNKNOWN":"UNAVAILABLE"),"incorrect certainty");
                    if(mutation){check(timeout.getString("operationId").equals("blocked-operation"),"lost operation id");check(!timeout.getJSONObject("recovery").getBoolean("replayAllowed"),"allowed replay");}
                    check(receipt(mcp.handle(rpc(5,"tools/call",new JSONObject().put("name","runtime_status").put("arguments",new JSONObject())),session,AgentMcp.VERSION,()->true)).getBoolean("ok"),"unrelated query blocked");
                    JSONObject secondArgs=new JSONObject(arguments.toString());if(mutation)secondArgs.put("operationId","second-blocked-operation");
                    receipt(mcp.handle(rpc(7,"tools/call",new JSONObject().put("name",mutation?"space_rename":"runtime_resources").put("arguments",secondArgs)),session,AgentMcp.VERSION,()->true));
                    JSONObject busy=receipt(mcp.handle(rpc(8,"tools/call",new JSONObject().put("name","runtime_status").put("arguments",new JSONObject())),session,AgentMcp.VERSION,()->true));
                    check(!busy.getBoolean("dispatched")&&busy.getString("errorCode").equals("MCP_DISPATCH_BUSY"),"unbounded dispatch or hidden queue");
                    check(mcp.remove(session),"session removal blocked");
                    check(mcp.handle(rpc(6,"ping",new JSONObject()),session,AgentMcp.VERSION,()->true).status()==404,"removed session accepted");
                }finally{control.release.countDown();caller.shutdownNow();}
            }
        }
        System.out.println("Java schema cases passed: "+tested+"; blocked query/mutation timeout, discovery, ping and revocation passed");
    }
}
