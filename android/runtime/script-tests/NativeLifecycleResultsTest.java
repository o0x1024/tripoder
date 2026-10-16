package dev.tripoder.runtime.script;
import static dev.tripoder.runtime.script.ScriptContractTest.*;
final class NativeLifecycleResultsTest {
    static void run()throws Throwable {
        Backend backend=new Backend();backend.nativeKind=true;backend.busyRetire=true;
        HookInvocationSession session=session(backend,rule(null,null,handler(member(id("call"),"result"))));
        session.activate("generation-1","revision-1");
        check(session.disable("generation-1","revision-1").state()==HookInvocationSession.State.DRAINING,"native admission busy stays draining");
        check(backend.fatal==0&&backend.retired==1,"busy never terminates or repeats publication");
        check(backend.call(l(1d),a->7d).equals(7d),"busy draining executes original only");
        backend.busyRetire=false;
        check(session.status().state()==HookInvocationSession.State.DISABLED&&backend.published==1,"later unchanged retirement completes once");
        Backend rejected=new Backend();rejected.rejectPublish=true;
        HookInvocationSession clean=session(rejected,rule(null,null,handler(member(id("call"),"result"))));
        try{clean.activate("generation-1","revision-1");throw new AssertionError("clean refusal accepted");}
        catch(IllegalStateException expected){check(expected.getCause() instanceof HookPublicationRejected,"known unpublished refusal retained");}
        check(clean.status().state()==HookInvocationSession.State.FAILED&&rejected.fatal==0,"clean refusal without process kill");
    }
}
