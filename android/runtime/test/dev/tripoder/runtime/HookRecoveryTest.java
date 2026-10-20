package dev.tripoder.runtime;

import android.os.Bundle;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;

/** Production journal/claim/dispatch logic with an injected host worker boundary. */
public final class HookRecoveryTest {
    static int checks,calls;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    interface Work {void run()throws Exception;}
    static void rejects(Work work,String code)throws Exception{
        try{work.run();throw new AssertionError("accepted: "+code);}catch(Exception failure){check(String.valueOf(failure).contains(code),"rejected "+code+": "+failure);}
    }
    static final class Journal implements ExecutionContract.Store {
        final Map<String,ExecutionContract.Operation> rows=new HashMap<>();
        public ExecutionContract.Operation read(String id){return rows.get(id);}
        public void write(ExecutionContract.Operation row){rows.put(row.id,row);}
        public List<ExecutionContract.Operation> incomplete(){return List.of();}
    }
    public static void main(String[] args)throws Exception{
        RuntimeBroker broker=new RuntimeBroker(new ExecutionContract(new Journal(),()->1000L));broker.contract.onRecover();
        var allocation=broker.contract.allocate("space","owned.fixture","install","owned.fixture",12345);
        broker.contract.attach(allocation.bootstrapToken,123,12345);
        String generation=allocation.session.processGeneration;broker.contract.markRunning(generation,123,12345);
        RuntimeHookClaims claims=new RuntimeHookClaims(broker);
        String request=new JSONObject().put("processGeneration",generation).put("command","observe").toString();
        RuntimeWire.handler=(peer,code,input)->{calls++;return reply(generation,"{\"ok\":false,\"outcome\":\"REJECTED\",\"error\":\"HOOK_FIELD:id\"}");};
        RuntimeHookClaims.Claim first;
        synchronized(broker){first=claims.begin(generation,"project","binding","lease","observe");}
        try{RuntimeHookOperations.execute(broker,"bad-selector",generation,request,"host");throw new AssertionError("accepted invalid selector");}
        catch(RuntimeHookOperations.RejectedCommandException expected){synchronized(broker){claims.rejected(first);}}
        check(broker.contract.query("bad-selector").state==ExecutionContract.OperationState.FAILED,"explicit rejection is terminal failed");
        RuntimeHookClaims.Claim next;
        synchronized(broker){next=claims.begin(generation,"project","binding","lease","observe");check(next!=first&&!next.uncertain,"rejected initial observe does not poison process");claims.complete(next,"observe",new JSONObject().put("state","OBSERVED"));}
        synchronized(broker){var claim=claims.begin(generation,"project","binding","lease","prepare");claims.rejected(claim);check(claim.state.equals("OBSERVED")&&!claim.busy&&!claim.uncertain,"rejected prepare retains previous observation");}
        RuntimeWire.handler=(peer,code,input)->{calls++;throw new IllegalStateException("LOST_ACK");};
        synchronized(broker){next=claims.begin(generation,"project","binding","lease","activate");}
        try{RuntimeHookOperations.execute(broker,"unknown-activate",generation,request,"host");throw new AssertionError("lost ack accepted");}
        catch(Exception expected){synchronized(broker){claims.uncertain(next,"unknown-activate");}}
        check(broker.contract.query("unknown-activate").state==ExecutionContract.OperationState.UNKNOWN,"dispatch failure preserves unknown journal");
        synchronized(broker){
            rejects(()->claims.begin(generation,"project","binding","lease","events"),"HOOK_OUTCOME_QUERY_REQUIRED:unknown-activate");
            rejects(()->claims.begin(generation,"project","binding","lease","uiDump"),"HOOK_OUTCOME_QUERY_REQUIRED:unknown-activate");
            var status=claims.begin(generation,"project","binding","lease","status");claims.rejected(status);
            check(status.uncertain&&status.pendingOperation.equals("unknown-activate"),"rejected status cannot erase original uncertainty");
            status=claims.begin(generation,"project","binding","lease","status");claims.uncertain(status,"failed-status");
            check(status.pendingOperation.equals("unknown-activate"),"failed recovery preserves original operation id");
            status=claims.begin(generation,"project","binding","lease","status");claims.complete(status,"status",new JSONObject().put("state","IDLE"));
            var fresh=claims.begin(generation,"project","binding","lease","events");claims.complete(fresh,"events",new JSONObject());
        }
        check(broker.contract.query("unknown-activate").state==ExecutionContract.OperationState.UNKNOWN,"status never invents original mutation success");
        int before=calls;
        rejects(()->RuntimeHookOperations.execute(broker,"unknown-activate",generation,request,"host"),"HOOK_ALREADY_ACCEPTED_QUERY_REQUIRED");
        check(calls==before,"unknown operation is never dispatched again");
        RuntimeWire.handler=(peer,code,input)->{calls++;return reply(generation,"{\"ok\":false,\"error\":\"legacy worker error\"}");};
        rejects(()->RuntimeHookOperations.execute(broker,"legacy-error",generation,request,"host"),"legacy worker error");
        check(broker.contract.query("legacy-error").state==ExecutionContract.OperationState.UNKNOWN,"unclassified legacy errors remain unknown");
        synchronized(broker){
            var owner=claims.begin(generation,"project","binding","lease","observe");claims.complete(owner,"observe",new JSONObject());
            rejects(()->claims.begin(generation,"other","binding","lease","observe"),"HOOK_PROJECT_OWNER_CONFLICT");
            rejects(()->claims.begin(generation,"project","binding","other-lease","prepare"),"HOOK_PREPARATION_LEASE_REVOKED");
        }
        var field=RuntimeHookOperations.class.getDeclaredField("calls");field.setAccessible(true);((ExecutorService)field.get(null)).shutdownNow();
        try{RuntimeHookOperations.execute(broker,"queue-rejected",generation,request,"host");throw new AssertionError("closed executor accepted");}
        catch(RuntimeHookOperations.RejectedCommandException expected){check(broker.contract.query("queue-rejected").state==ExecutionContract.OperationState.FAILED,"pre-dispatch rejection is known failed");}
        System.out.println("HOOK_RECOVERY_PASS checks="+checks);
    }
    static Bundle reply(String generation,String response){Bundle reply=new Bundle();reply.putInt("pid",123);reply.putString("generation",generation);reply.putString("result",response);return reply;}
}
