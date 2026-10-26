package dev.tripoder.runtime;

import android.os.*;
import java.util.concurrent.*;
import org.json.*;

/** Shared journal and worker call boundary; never call a worker while holding the broker monitor. */
public final class RuntimeHookOperations {
    public static final class RejectedCommandException extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        RejectedCommandException(String message){super(message);}
        RejectedCommandException(Throwable cause){super(cause.getMessage(),cause);}
    }
    public static final class QueryUnavailableException extends IllegalStateException {public QueryUnavailableException(Throwable cause){super(cause.getMessage(),cause);}}
    private static final ExecutorService calls=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());
    public static Bundle execute(RuntimeBroker broker,String operation,String generation,String request,String caller)throws Exception{
        return execute(broker,operation,generation,request,caller,"");
    }
    public static Bundle execute(RuntimeBroker broker,String operation,String generation,String request,String caller,String scope)throws Exception{
        if(Thread.holdsLock(broker))throw new IllegalStateException("HOOK_CALL_INSIDE_BROKER_LOCK");
        if(request==null||request.length()>28000)throw new IllegalArgumentException("HOOK_REQUEST_SIZE");
        JSONObject command=new JSONObject(request);boolean query=dev.tripoder.runtime.script.HookCommands.readOnly(command.getString("command"));
        if(!generation.equals(command.getString("processGeneration")))throw new SecurityException("HOOK_PROCESS_STALE");
        RuntimeBroker.Slot slot;int pid;
        synchronized(broker){
            slot=broker.requiredSlot(generation);var session=broker.contract.getSession(generation);
            if(session.state!=ExecutionContract.ProcessState.RUNNING||slot.peer==null)throw new IllegalStateException("HOOK_PROCESS_NOT_RUNNING");pid=session.pid;
            String digest=ApkStore.textDigest("hook-command-v2\n"+scope+"\n"+generation+"\n"+request);
            try(RuntimeMutationGuard effect=RuntimeMutationGuard.generation(broker,generation,"hook:"+command.getString("command"))){
            if(!broker.contract.accept(operation,caller,digest).created)throw new IllegalStateException("HOOK_ALREADY_ACCEPTED_QUERY_REQUIRED");
            broker.contract.start(operation);
            // A new process has no accepted hook plan. Replaying its old component
            // would silently change execution semantics, including after ACK loss.
            if("activate".equals(command.getString("command")))broker.store.requireExplicitHookRecovery(generation,operation);
            }
        }
        boolean dispatched=false;
        try{
            Bundle input=new Bundle();input.putString("generation",generation);input.putString("request",request);
            Future<Bundle> pending=calls.submit(()->RuntimeWire.call(slot.peer,RuntimeWire.HOOK,input));dispatched=true;
            Bundle reply;
            try{reply=pending.get(15,TimeUnit.SECONDS);}catch(TimeoutException timeout){pending.cancel(true);throw new IllegalStateException("HOOK_WORKER_TIMEOUT_UNKNOWN",timeout);}
            if(reply.getInt("pid")!=pid||!generation.equals(reply.getString("generation")))throw new SecurityException("HOOK_ACK_IDENTITY_MISMATCH");
            JSONObject response=new JSONObject(reply.getString("result"));
            synchronized(broker){
                if(broker.contract.getSession(generation).state!=ExecutionContract.ProcessState.RUNNING)throw new IllegalStateException("HOOK_PROCESS_DIED_BEFORE_ACK");
                if(!response.getBoolean("ok")){
                    if(query){broker.contract.fail(operation,"HOOK_QUERY_UNAVAILABLE");throw new QueryUnavailableException(new IllegalStateException(response.getString("error")));}
                    if("REJECTED".equals(response.optString("outcome"))){broker.contract.fail(operation,"HOOK_COMMAND_REJECTED");throw new RejectedCommandException(response.getString("error"));}
                    broker.contract.markUnknown(operation,"HOOK_WORKER_OUTCOME_UNCONFIRMED");
                    throw new IllegalStateException(response.getString("error"));
                }
                JSONObject result=response.getJSONObject("result"),durable=new JSONObject(result.toString());durable.remove("token");
                broker.contract.succeed(operation,durable.toString());return reply;
            }
        }catch(Exception error){
            synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING){if(query)broker.contract.fail(operation,"HOOK_QUERY_UNAVAILABLE");else if(dispatched)broker.contract.markUnknown(operation,"HOOK_WORKER_OUTCOME_UNCONFIRMED");else {broker.contract.fail(operation,"HOOK_DISPATCH_REJECTED");throw new RejectedCommandException(error);}}}
            if(query&&!(error instanceof QueryUnavailableException))throw new QueryUnavailableException(error);
            throw error;
        }
    }
    private RuntimeHookOperations(){}
}
