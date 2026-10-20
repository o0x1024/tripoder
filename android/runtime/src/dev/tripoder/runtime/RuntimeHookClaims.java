package dev.tripoder.runtime;

import org.json.JSONObject;
import java.util.*;

/** Process-local hook ownership. Authority death already terminates every worker. */
final class RuntimeHookClaims {
    static final class Claim {
        final String process,project,binding,lease;boolean busy,retained,uncertain;String state="IDLE";
        Claim(String process,String project,String binding,String lease){this.process=process;this.project=project;this.binding=binding;this.lease=lease;}
    }
    private final RuntimeBroker broker;private final Map<String,Claim> claims=new HashMap<>();
    RuntimeHookClaims(RuntimeBroker broker){this.broker=broker;}
    Claim begin(String process,String project,String binding,String lease,String command){
        locked();
        Set<String> stateless=Set.of("status","modules","events","dexSearch","dexInspect","nativeExports","uiDump","uiAction","diagnostics","manifestInspect","storageInspect","networkTraffic","httpRequest","guestInvoke","probeStatus","probeStop","classLoaders","classLoaderCreate","dexList","dexSnapshot","codeArtifacts","runtimeEvents","classInspect");
        if(!stateless.contains(command)&&!Set.of("observe","prepare","activate","disable","updateParameters","cancelPreparation","discard").contains(command))throw new IllegalArgumentException("HOOK_COMMAND_UNSUPPORTED");
        if(broker.contract.getSession(process).state!=ExecutionContract.ProcessState.RUNNING)throw new IllegalStateException("HOOK_PROCESS_NOT_RUNNING");
        Claim claim=claims.get(process);
        if(claim==null){
            if(!command.equals("observe")&&!stateless.contains(command))throw new IllegalStateException("HOOK_PROJECT_OBSERVATION_REQUIRED");
            claim=new Claim(process,project,binding,lease);claims.put(process,claim);
        }else{
            if(!claim.project.equals(project)||!claim.binding.equals(binding))throw new SecurityException("HOOK_PROJECT_OWNER_CONFLICT");
            if(claim.busy)throw new IllegalStateException("HOOK_CONTROL_BUSY");
            if(claim.uncertain&&!command.equals("status"))throw new IllegalStateException("HOOK_OUTCOME_QUERY_REQUIRED");
            boolean newOwnerControl=stateless.contains(command)||Set.of("disable","discard").contains(command)||command.equals("updateParameters")&&claim.state.equals("ACTIVE");
            if(!claim.lease.equals(lease)&&!newOwnerControl)throw new SecurityException("HOOK_PREPARATION_LEASE_REVOKED");
        }
        claim.busy=true;if(command.equals("observe"))claim.retained=true;return claim;
    }
    void complete(Claim claim,String command,JSONObject result){
        locked();if(claims.get(claim.process)!=claim)return;
        claim.busy=false;claim.uncertain=false;
        if(command.equals("observe"))claim.state="OBSERVED";
        else if(command.equals("cancelPreparation"))claim.state="OBSERVED";
        else if(result.has("state"))claim.state=result.optString("state");
        if(Set.of("IDLE","DISABLED").contains(claim.state)){claims.remove(claim.process);return;}
        claim.retained=true;
    }
    void uncertain(Claim claim){locked();if(claims.get(claim.process)==claim){claim.busy=false;claim.uncertain=true;claim.retained=true;}}
    void requireUnclaimedProject(String project){locked();for(Claim claim:claims.values())if(claim.project.equals(project))throw new IllegalStateException("PROJECT_HOOK_CONTEXT_IN_USE");}
    void dead(String generation){locked();claims.remove(generation);}
    private void locked(){if(!Thread.holdsLock(broker))throw new IllegalStateException("HOOK_CLAIM_LOCK_REQUIRED");}
}
