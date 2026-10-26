package dev.tripoder.hooklab;

import org.json.*;
import java.util.regex.*;

/** Read-only guidance. Never rewrites journal outcomes or dispatches recovery. */
final class AgentRecovery {
    static JSONObject enrich(JSONObject receipt,String method,JSONObject args)throws Exception{
        JSONObject value=receipt.optJSONObject("result");if(value==null)value=new JSONObject();
        boolean unknown="UNKNOWN".equals(receipt.optString("state"))||"UNKNOWN".equals(value.optString("state"));
        String error=receipt.optString("error");
        Matcher blocked=Pattern.compile("(?:RECOVERY_SCOPE_UNKNOWN|HOOK_OUTCOME_QUERY_REQUIRED):\\s*([A-Za-z0-9_-]{1,80})").matcher(error);
        boolean hasBlocked=blocked.find();
        if(receipt.optBoolean("ok")&&!unknown)return receipt;
        String code=receipt.optString("errorCode",unknown&&receipt.optBoolean("ok")?"OPERATION_OUTCOME_UNKNOWN":"");
        if(code.isEmpty()){Matcher match=Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b").matcher(error);code=match.find()?match.group():"COMMAND_FAILED";}
        String original=hasBlocked?blocked.group(1):receipt.optString("operationId",value.optString("operationId",args.optString("operationId")));
        JSONObject candidate=args.optJSONObject("target");if(candidate==null)candidate=value.optJSONObject("target");
        if(value.has("targetVerified")&&!value.getBoolean("targetVerified"))candidate=null;
        JSONObject target=null;
        if(candidate!=null&&candidate.opt("space") instanceof String&&candidate.opt("package") instanceof String&&candidate.opt("installGeneration") instanceof String)
            target=new JSONObject().put("space",candidate.getString("space")).put("package",candidate.getString("package")).put("installGeneration",candidate.getString("installGeneration"));
        JSONArray steps=new JSONArray();String action="Inspect the exact published action schema and error before issuing a command.";
        if(unknown||hasBlocked){
            if(!original.isEmpty()&&!method.equals("operation.get"))add(steps,"operation_get",new JSONObject().put("operationId",original));
            if(code.equals("HOOK_OUTCOME_QUERY_REQUIRED")){
                if(candidate!=null&&candidate.has("processGeneration"))add(steps,"process_inspect",new JSONObject().put("action","hook_status").put("target",candidate));
                action="Query the original operation and exact Hook status. Reading events does not clear the gate. Never replay this mutation, including under a new operationId.";
            }else{
                add(steps,"application_query",new JSONObject().put("action","targets"));
                if(target!=null){
                    if(!original.isEmpty()&&!method.equals("launch.recovery"))add(steps,"application_query",new JSONObject().put("action","launch_recovery").put("operationId",original).put("target",target));
                    add(steps,"application_query",new JSONObject().put("action","process_history").put("target",target).put("cursor",0).put("limit",16));
                }
                action="Preserve the original UNKNOWN result and evidence. Query the original operation and exact launch recovery. targets=[] is not proof of failure. If entryIssued=false, do not call launch_manage(close). A deliberate application_stop with its own new operationId may establish stop/dead-session evidence; inspect launch_recovery again before planning a new launch. Changing the launch ID alone never authorizes retry.";
            }
        }else if(code.equals("LAUNCH_RECOVERY_BINDING_STALE")){
            if(!original.isEmpty())add(steps,"operation_get",new JSONObject().put("operationId",original));
            action="The supplied installation does not own the original launch. Keep its UNKNOWN result and recover its initiating identity; never substitute a newly installed target.";
        }else if(code.equals("LAUNCH_ENTRY_BINDING_MISMATCH")){
            if(args.has("launchOperationId")&&target!=null)add(steps,"application_query",new JSONObject().put("action","launch_recovery").put("operationId",args.getString("launchOperationId")).put("target",target));
            action="Read the original launch recovery. A preparation with entryIssued=false has no entry to close. Do not guess a ticket, change target identity or change launch IDs to retry.";
        }else if(code.equals("AGENT_REQUEST_ID_CONTENT_MISMATCH")){
            if(args.has("operationId"))add(steps,"operation_get",new JSONObject().put("operationId",args.getString("operationId")));
            action="This ID belongs to different content. Inspect its original receipt; do not overwrite, replay or infer failure.";
        }else if(receipt.has("dispatched")&&!receipt.getBoolean("dispatched"))action="No command was dispatched. Correct the named fields using this action schema. This rejection does not authorize retrying any earlier UNKNOWN operation.";
        receipt.put("errorCode",code);if(!original.isEmpty())receipt.put("pendingOperationId",original);
        JSONObject recovery=new JSONObject().put("action",action).put("steps",steps).put("replayAllowed",false).put("newIdRetryAllowed",false).put("preserveOriginalEvidence",true).put("pollingDoesNotResolve",true);
        if(unknown||hasBlocked)recovery.put("requiresOriginalTarget",target==null);
        return receipt.put("recovery",recovery);
    }
    private static void add(JSONArray steps,String tool,JSONObject args)throws Exception{steps.put(new JSONObject().put("tool",tool).put("arguments",args));}
    private AgentRecovery(){}
}
