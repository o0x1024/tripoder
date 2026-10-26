package dev.tripoder.runtime.script;

import java.util.*;
import java.util.concurrent.atomic.*;
import static dev.tripoder.runtime.script.ScriptContractTest.*;

@SuppressWarnings("try")
final class JavaObjectScopeTest {
    static final class Hostile extends RuntimeException {
        private static final long serialVersionUID=1L;
        int calls;
        Hostile(){super("base-message",new IllegalArgumentException("base-cause"));}
        @Override public String getMessage(){calls++;throw new AssertionError("virtual getMessage");}
        @Override public synchronized Throwable getCause(){calls++;throw new AssertionError("virtual getCause");}
        @Override public StackTraceElement[] getStackTrace(){calls++;throw new AssertionError("virtual getStackTrace");}
        @Override public String toString(){calls++;throw new AssertionError("virtual toString");}
    }
    static void run()throws Throwable{
        System.load(System.getProperty("tripoder.snapshotLibrary"));
        Class<?>[] types={Thread.class,Throwable.class,Object.class,int[].class};Hostile error=new Hostile();error.addSuppressed(new IllegalStateException("suppressed"));Object retained;
        try(JavaObjectScope scope=JavaObjectScope.open("process-a","revision-a",types,Object.class)){
            Object thread=JavaHookValues.decode(Thread.class,Thread.currentThread()),exception=JavaHookValues.decode(Throwable.class,error);retained=exception;
            check(JavaHookValues.encode(Thread.class,thread)==Thread.currentThread(),"thread original identity");
            check(JavaHookValues.encode(Throwable.class,exception)==error,"Throwable original identity");
            check(JavaHookValues.decode(Object.class,error)==exception,"same object same invocation token");
            check(JavaHookValues.encode(Object.class,exception)==error,"assignable reference transfer");
            check(JavaHookValues.decode(Throwable.class,null)==null&&JavaHookValues.encode(Throwable.class,null)==null,"object null roundtrip");
            int[] array={1,2};Object ref=JavaHookValues.decode(int[].class,array);check(JavaHookValues.encode(int[].class,ref)==array,"array reference identity");
            rejects(()->JavaHookValues.encode(Thread.class,exception),"HOOK_OBJECT_TYPE_MISMATCH");
            rejects(()->JavaHookValues.encode(Throwable.class,Map.of("className","java.lang.Throwable")),"HOOK_OBJECT_REFERENCE_REQUIRED");
            rejects(()->ScriptValues.freeze(exception),"DRAFT_HOST_OBJECT");
            check(ScriptValues.freezeInvocation(exception)==exception,"invocation freeze preserves token");
            rejects(()->HookWire.encode(exception),"HOOK_JSON_VALUE");
            rejects(()->ScriptValues.text(exception),"DRAFT_SCALAR_REQUIRED");
            Map<String,Object> snapshot=JavaObjectSnapshots.throwable(exception,8,4);
            check(error.calls==0,"framework snapshot never invokes overrides");
            List<?> causes=(List<?>)snapshot.get("causes");check(causes.size()==2,"Throwable cause chain");
            Map<?,?> first=(Map<?,?>)causes.get(0);check(first.get("message").equals("base-message"),"base Throwable message");check(((List<?>)first.get("suppressed")).size()==1,"suppressed snapshot");
            check(JavaObjectSnapshots.thread(thread).get("name").equals(Thread.currentThread().getName()),"thread snapshot");
            try(JavaObjectScope nested=JavaObjectScope.open("process-a","revision-a",types,Object.class)){rejects(()->JavaHookValues.encode(Throwable.class,exception),"HOOK_OBJECT_REFERENCE_EXPIRED");}
            check(JavaHookValues.encode(Throwable.class,exception)==error,"outer scope restored after nested invocation");
            AtomicReference<Throwable> failed=new AtomicReference<>();Thread other=new Thread(()->{try(JavaObjectScope nested=JavaObjectScope.open("process-a","revision-a",types,Object.class)){JavaHookValues.encode(Throwable.class,exception);}catch(Throwable expected){failed.set(expected);}});other.start();other.join();
            check(failed.get()!=null&&failed.get().getMessage().contains("HOOK_OBJECT_REFERENCE_EXPIRED"),"cross-thread reference rejected");
            for(int i=0;i<16;i++){System.gc();check(JavaHookValues.encode(Throwable.class,exception)==error,"managed GC root identity");}
        }
        rejects(()->JavaObjectScope.info(retained),"HOOK_OBJECT_REFERENCE_EXPIRED");
        compilerRoundTrip(error);
    }
    static void compilerRoundTrip(Hostile error)throws Throwable{
        Map<String,Object> evidence=m("id","object-evidence","source","fixture","target",m("packageName","owned.fixture","versionCode","1","apkSha256","a".repeat(64)),"processInstanceId","process-a","abi","arm64-v8a","capabilities",l("java-hook"),"symbols",l(m("id","owned","kind","java","loaded",true,"className","owned.Fixture","methodName","call","classLoaderId","loader-a","argumentTypes",l("java.lang.Thread","java.lang.Throwable"),"returnType","void")));
        String source="import {definePlan} from '@abox/hook';export default definePlan({id:'objects',target,params:{},setup(ctx){ctx.java.method('owned.Fixture','call',['java.lang.Thread','java.lang.Throwable']).before(({args})=>{const snapshot=ctx.java.snapshotThrowable(args[1]);ctx.debug.captureEvidence('exception',snapshot);return args;});}});";
        AtomicInteger captured=new AtomicInteger(),original=new AtomicInteger();DraftCompiler.Plan plan=DraftCompiler.compile(source,new HookEvidence(evidence),id->{throw new AssertionError();},(tag,payload)->{captured.incrementAndGet();return Map.of("artifactId","owned-evidence","state","COMPLETE");});
        try(JavaObjectScope scope=JavaObjectScope.open("process-a","revision-a",new Class<?>[]{Thread.class,Throwable.class},void.class)){
            List<Object> incoming=JavaHookValues.decodeArguments(new Class<?>[]{Thread.class,Throwable.class},new Object[]{Thread.currentThread(),error});
            plan.rules().get(0).execute(Map.of(),incoming,args->{Object[] values=JavaHookValues.encodeArguments(new Class<?>[]{Thread.class,Throwable.class},args);check(values[1]==error,"compiled before preserves Throwable");original.incrementAndGet();return ScriptValues.UNDEFINED;});
        }
        check(captured.get()==1&&original.get()==1&&error.calls==0,"compiled object callback once with explicit evidence snapshot");
    }
}
