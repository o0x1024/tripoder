package dev.tripoder.runtime.script;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit Worker command executor. It stores no approvals, operations, results or replay queue. */
public final class HookCommandDispatcher {
    @FunctionalInterface public interface Handler {String handle(String request)throws Exception;}
    public interface Sink {
        void response(String requestId,String response);
        void rejected(String requestId,Exception error);
        /** Do not acknowledge a definitive failure; the existing timeout/journal path records UNKNOWN. */
        void unknown(String requestId,Error error);
    }
    private final Handler handler;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicBoolean poisoned=new AtomicBoolean();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new SynchronousQueue<>(),job->{
        Thread thread=new Thread(job,"TripoderSelfHookControl");thread.setDaemon(true);return thread;
    });
    public HookCommandDispatcher(Handler handler) {this.handler=java.util.Objects.requireNonNull(handler);}
    public void submit(String id,String request,Sink sink) {
        if(poisoned.get()){sink.rejected(id,new ScriptFault("HOOK_PROCESS_INVALIDATED"));return;}
        if(!busy.compareAndSet(false,true)){sink.rejected(id,new ScriptFault("HOOK_CONTROL_BUSY"));return;}
        if(poisoned.get()){busy.set(false);sink.rejected(id,new ScriptFault("HOOK_PROCESS_INVALIDATED"));return;}
        try {
            executor.execute(()->{
                try {sink.response(id,handler.handle(request));}
                catch(Exception error){sink.rejected(id,error);}
                catch(Error error){poisoned.set(true);sink.unknown(id,error);}
                finally {busy.set(false);}
            });
        } catch(RejectedExecutionException error) {busy.set(false);sink.rejected(id,new ScriptFault("HOOK_CONTROL_BUSY"));}
    }
}
