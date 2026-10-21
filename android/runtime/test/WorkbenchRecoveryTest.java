package dev.tripoder.hooklab;

import org.json.JSONObject;

/** Execute the production device request handler with an injected engine boundary. */
public final class WorkbenchRecoveryTest {
    static int checks;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static JSONObject request(){return new JSONObject().put("protocol",2).put("requestId","query-id").put("method","operation.get").put("params",new JSONObject().put("operationId","original-mutation"));}
    public static void main(String[] args)throws Exception{
        WorkbenchApplication app=new WorkbenchApplication();WorkbenchAgentControl control=new WorkbenchAgentControl(app);control.listening=true;
        JSONObject response=control.wire(request());
        check(response.getBoolean("ok")&&response.getJSONObject("result").getString("state").equals("UNKNOWN"),"sessionless operation reconciliation");
        response=control.wire(request().put("session","closed-old-session"));
        check(response.getBoolean("ok"),"legacy session field accepted for operation.get");
        check(app.engine.calls==2&&app.engine.operation.equals("original-mutation"),"query uses original operation, never request id or mutation replay");
        response=control.wire(request().put("session","old-session").put("extra","unexpected"));
        check(!response.getBoolean("ok")&&response.getString("error").contains("AGENT_FIELDS_UNSUPPORTED")&&app.engine.calls==2,"unrelated envelope fields still rejected");
        response=control.wire(request().put("params",new JSONObject().put("operationId","original-mutation").put("extra",true)));
        check(!response.getBoolean("ok")&&app.engine.calls==2,"query argument contract stays strict");
        check(app.uiChanges==0,"read-only reconciliation does not trigger UI refresh");
        app.engine.unavailable=true;
        response=control.wire(new JSONObject().put("protocol",2).put("requestId","stop-space").put("method","space.delete").put("params",new JSONObject().put("space","explicit-space")));
        check(!response.getBoolean("ok")&&response.getString("state").equals("UNKNOWN")&&response.getString("operationId").equals("stop-space"),"failed reconciliation cannot turn an uncertain mutation into a rejection");
        check(app.uiChanges==1,"external mutation invalidates UI even when its result is uncertain");
        check(app.engine.calls==4,"one dispatch and one reconciliation attempt, without mutation replay");
        System.out.println("WORKBENCH_RECOVERY_PASS checks="+checks);
    }
}
