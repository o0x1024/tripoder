package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Own host fixtures only: no runtime instrumentation, target application or device access. */
public final class ScriptContractTest {
    static int checks;
    static void check(boolean ok,String name) {checks++;if(!ok)throw new AssertionError(name);}
    interface Action {void run()throws Throwable;}
    static void rejects(Action action,String code)throws Throwable {
        try{action.run();throw new AssertionError("accepted "+code);}catch(ScriptFault expected){check(expected.getMessage().startsWith(code),"fault "+code+" got "+expected);}
    }
    public static Map<String,Object> m(Object... entries){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)out.put((String)entries[i],entries[i+1]);return out;}
    public static List<Object> l(Object... values){return Arrays.asList(values);}
    static Map<String,Object> id(String name){return m("type","Identifier","name",name);}
    static Map<String,Object> num(double value){return m("type","NumericLiteral","value",value);}
    static Map<String,Object> lit(String value){return m("type","StringLiteral","value",value);}
    static Map<String,Object> member(Object target,String key){return m("type","MemberExpression","object",target,"property",id(key),"computed",false);}
    static Map<String,Object> index(Object target,double index){return m("type","MemberExpression","object",target,"property",num(index),"computed",true);}
    static Map<String,Object> binary(String op,Object left,Object right){return m("type","BinaryExpression","operator",op,"left",left,"right",right);}
    static Map<String,Object> arrow(Object expression){return m("type","ArrowFunctionExpression","params",l(id("call")),"body",expression,"async",false);}
    static RestrictedAst.Handler handler(Object expr){return RestrictedAst.compile(arrow(expr),Map.of());}
    static Object arg(){return index(member(id("call"),"args"),0);}
    static HookRule rule(RestrictedAst.Handler before,RestrictedAst.Handler replace,RestrictedAst.Handler after){return new HookRule("owned",List.of("int"),"int",before,replace,after);}
    static final class Backend implements HookInvocationSession.PreparedBackend {
        HookInvocationSession.Dispatch dispatch;int published,retired,fatal;boolean failPublish,failRetire,nativeKind,busyRetire,rejectPublish;
        public void publish(HookInvocationSession.Dispatch d)throws HookPublicationRejected{dispatch=d;published++;if(rejectPublish)throw new HookPublicationRejected(new IllegalStateException("CLEAN_REFUSAL"));if(failPublish)throw new ScriptFault("PARTIAL_PUBLISH");}
        public boolean retire(){retired++;if(failRetire)throw new ScriptFault("PARTIAL_RETIRE");return !busyRetire;}
        public void failStop(Throwable t){fatal++;}
        public boolean nativeSymbol(String s){return nativeKind;}
        Object call(List<Object> a,HookRule.Original original)throws Throwable{return dispatch.call("owned",a,original);}
    }
    public static void main(String[] ignored)throws Throwable {
        values();ast();execution();lifecycle();failure();parallel();commands();SourceControlTest.run();JavaHookValuesTest.run();NativeHookValuesTest.run();
        OracleFixtures.run();NativeLifecycleResultsTest.run();
        System.out.println("SCRIPT_CONTRACT_PASS checks="+checks);
    }
    static void values()throws Throwable {
        check(ScriptValues.abi("long",new BigInteger("9223372036854775807")).equals(new BigInteger("9223372036854775807")),"long max exact");
        check(ScriptValues.abi("long",new BigInteger("-9223372036854775808")).equals(new BigInteger("-9223372036854775808")),"long min exact");
        check(ScriptValues.abi("uint64",new BigInteger("18446744073709551615")).equals(new BigInteger("18446744073709551615")),"u64 max");
        rejects(()->ScriptValues.abi("long",9223372036854775807d),"HOOK_TYPE");
        rejects(()->ScriptValues.abi("long",BigInteger.ONE.shiftLeft(63)),"HOOK_TYPE");
        rejects(()->ScriptValues.abi("uint64",BigInteger.valueOf(-1)),"HOOK_TYPE");
        rejects(()->ScriptValues.abi("uint64",BigInteger.ONE.shiftLeft(64)),"HOOK_TYPE");
        rejects(()->ScriptValues.abi("float",Double.MAX_VALUE),"HOOK_TYPE");
        check(ScriptValues.abi("float",16777217d).equals(16777216d),"float round at boundary");
        check(Double.doubleToRawLongBits((Double)ScriptValues.abi("float",-0d))==Long.MIN_VALUE,"negative float zero");
        check(ScriptValues.abi("char","\ud800").equals("\ud800"),"char UTF16 code unit");
        rejects(()->ScriptValues.abi("char","😀"),"HOOK_TYPE");
        rejects(()->ScriptValues.freeze(new Object()),"DRAFT_HOST_OBJECT");
        List<Object> cyclic=new ArrayList<>();cyclic.add(cyclic);rejects(()->ScriptValues.freeze(cyclic),"DRAFT_CYCLIC_VALUE");
        rejects(()->ScriptValues.freeze(m("constructor","x")),"DRAFT_FORBIDDEN_PROPERTY");
        rejects(()->ScriptValues.limit(BigInteger.ONE.shiftLeft(127)),"DRAFT_BIGINT_SIZE");
        ParameterSpec unicode=new ParameterSpec("string","",null,null,1);
        check(unicode.validate("😀").equals("😀"),"parameter code points");rejects(()->unicode.validate("😀😀"),"PARAM_RANGE");
        ParameterSpec integer=new ParameterSpec("integer",1d,0d,5d,null);rejects(()->integer.validate(1.5d),"PARAM_RANGE");
        rejects(()->ParameterSpec.resolve(Map.of("x",integer),Map.of("y",1d)),"UNKNOWN_PARAM");
    }
    static void ast()throws Throwable {
        RestrictedAst.Handler h=handler(binary("+",arg(),num(1)));
        check(h.invoke(m("args",l(4d))).equals(5d),"AST addition");
        Map<String,Object> mutable=arrow(num(1));RestrictedAst.Handler frozen=RestrictedAst.compile(mutable,Map.of());mutable.put("body",num(2));
        check(frozen.invoke(Map.of()).equals(1d),"AST immutable");
        rejects(()->RestrictedAst.compile(arrow(m("type","NewExpression")),Map.of()),"DRAFT_UNSUPPORTED_SYNTAX");
        rejects(()->handler(member(id("call"),"constructor")),"DRAFT_FORBIDDEN_PROPERTY");
        rejects(()->handler(member(id("call"),"getClass")).invoke(Map.of()),"DRAFT_UNAVAILABLE_PROPERTY");
        rejects(()->handler(id("Java")).invoke(Map.of()),"DRAFT_UNKNOWN_IDENTIFIER");
        rejects(()->handler(binary("+",m("type","BigIntLiteral","value","1"),num(1))).invoke(Map.of()),"DRAFT_BIGINT_MIXED_ARITHMETIC");
        rejects(()->handler(binary("/",num(1),num(0))).invoke(Map.of()),"DRAFT_NON_FINITE");
        // All branches are checked before invocation, even when unreachable.
        rejects(()->handler(m("type","ConditionalExpression","test",m("type","BooleanLiteral","value",false),"consequent",m("type","WhileStatement"),"alternate",num(1))),"DRAFT_UNSUPPORTED_SYNTAX");
        Map<String,Object> inner=m("type","ArrowFunctionExpression","params",l(id("x")),"body",m("type","CallExpression","callee",id("x"),"arguments",l(id("x"))));
        Map<String,Object> invoke=m("type","CallExpression","callee",inner,"arguments",l(inner));
        rejects(()->handler(invoke).invoke(Map.of()),"DRAFT_CALL_DEPTH");
        List<Object> expressions=new ArrayList<>();for(int i=0;i<1600;i++)expressions.add(m("type","ExpressionStatement","expression",num(0)));
        rejects(()->handler(m("type","BlockStatement","body",expressions)).invoke(Map.of()),"DRAFT_EXECUTION_BUDGET");
        List<Object> captureList=l(3d);Map<String,Object> capture=m("v",captureList);RestrictedAst.Handler captured=RestrictedAst.compile(arrow(index(id("v"),0)),capture);captureList.set(0,7d);
        check(captured.invoke(Map.of()).equals(3d),"closure values immutable");
    }
    static void execution()throws Throwable {
        AtomicInteger original=new AtomicInteger();
        HookRule beforeAfter=rule(handler(m("type","ArrayExpression","elements",l(binary("+",arg(),num(2))))),null,handler(binary("*",member(id("call"),"result"),num(3))));
        check(beforeAfter.execute(Map.of(),l(4d),a->{original.incrementAndGet();return (Double)a.get(0)+1;}).equals(21d),"before original after");
        check(original.get()==1,"original once");
        HookRule replace=rule(null,handler(num(7)),null);
        check(replace.execute(Map.of(),l(4d),a->{throw new AssertionError("replace invoked original");}).equals(7d),"replace never original");
        HookRule badBefore=rule(handler(lit("invalid")),null,null);
        rejects(()->badBefore.execute(Map.of(),l(1d),a->{original.incrementAndGet();return 0d;}),"HOOK_BEFORE_ARGUMENT_ARRAY_REQUIRED");
        check(original.get()==1,"bad before no original");
        HookRule badAfter=rule(null,null,handler(lit("invalid")));
        rejects(()->badAfter.execute(Map.of(),l(1d),a->{original.incrementAndGet();return 0d;}),"HOOK_TYPE");
        check(original.get()==2,"bad after no retry");
        Throwable sentinel=new ScriptFault("OWNED_GUEST_THROWABLE");
        try{beforeAfter.execute(Map.of(),l(1d),a->{throw sentinel;});throw new AssertionError("missing original failure");}
        catch(Throwable actual){check(actual==sentinel,"original Throwable identity");}
        rejects(()->new HookRule("owned",List.of("java.lang.Object"),"int",null,handler(num(1)),null),"HOOK_ABI_UNSUPPORTED");
        rejects(()->rule(handler(num(1)),handler(num(1)),null),"HOOK_CONFLICT");
        HookRule floatBefore=new HookRule("owned",List.of("float"),"double",handler(m("type","ArrayExpression","elements",l(num(16777217d)))),null,handler(arg()));
        AtomicReference<Object> physicalFloat=new AtomicReference<>();Object unchangedSnapshot=floatBefore.execute(Map.of(),l(1d),a->{physicalFloat.set(ScriptValues.abi("float",a.get(0)));return 0d;});
        check(physicalFloat.get().equals(16777216d)&&unchangedSnapshot.equals(16777217d),"float ABI rounds without mutating after args snapshot");
    }
    static HookInvocationSession session(Backend b,HookRule r){return new HookInvocationSession("generation-1","revision-1",List.of(r),Map.of("delta",new ParameterSpec("integer",1d,0d,100d,null)),Map.of(),b);}
    static void lifecycle()throws Throwable {
        Backend backend=new Backend();HookRule r=rule(null,null,handler(binary("+",member(id("call"),"result"),member(member(id("call"),"params"),"delta"))));
        HookInvocationSession session=session(backend,r);check(backend.published==0,"prepare no publication");
        rejects(()->session.activate("old","revision-1"),"HOOK_PROCESS_STALE");session.activate("generation-1","revision-1");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Object> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=new Thread(()->{try{result.set(backend.call(l(1d),a->{entered.countDown();check(release.await(5,TimeUnit.SECONDS),"release original");return 10d;}));}catch(Throwable t){error.set(t);}});
        worker.start();check(entered.await(5,TimeUnit.SECONDS),"original entered");
        check(session.update("generation-1","revision-1",1,Map.of("delta",5d)).parameterVersion()==2,"parameter CAS");
        rejects(()->session.update("generation-1","revision-1",1,Map.of("delta",9d)),"HOOK_PARAMETER_VERSION_STALE");
        check(session.disable("generation-1","revision-1").state()==HookInvocationSession.State.DRAINING,"inflight draining");
        check(backend.retired==0,"not retired while original in flight");
        check(backend.call(l(2d),a->20d).equals(20d),"new calls after disable use original");
        release.countDown();worker.join(5000);check(!worker.isAlive()&&error.get()==null,"worker joined");check(result.get().equals(11d),"single immutable parameter version");
        check(session.status().state()==HookInvocationSession.State.DISABLED&&backend.retired==1,"retired after drain");
        session.status();check(backend.retired==1,"retire at most once");
        check(backend.call(l(2d),a->22d).equals(22d),"queued callback remains valid after retirement");
        rejects(()->session.update("generation-1","revision-1",2,Map.of("delta",6d)),"HOOK_PARAMETER_VERSION_STALE");
        rejects(()->session.disable("generation-1","other"),"HOOK_REVISION_STALE");
        check(session.status().hits()==1&&session.status().inFlight()==0,"hit and inflight counters");
    }
    static void failure()throws Throwable {
        Backend b=new Backend();b.failPublish=true;HookInvocationSession s=session(b,rule(null,handler(num(1)),null));
        try{s.activate("generation-1","revision-1");throw new AssertionError("publish accepted");}catch(HookBackendUncertain e){check(b.fatal==1&&s.status().state()==HookInvocationSession.State.FAILED,"partial publish invalidates");}
        Backend retire=new Backend();retire.failRetire=true;HookInvocationSession rs=session(retire,rule(null,handler(num(1)),null));rs.activate("generation-1","revision-1");
        try{rs.disable("generation-1","revision-1");throw new AssertionError("retire accepted");}catch(HookBackendUncertain e){check(retire.fatal==1&&rs.status().state()==HookInvocationSession.State.FAILED,"partial retire invalidates");}
        Backend java=new Backend();HookInvocationSession js=session(java,rule(null,handler(lit("bad")),null));js.activate("generation-1","revision-1");
        AtomicInteger originals=new AtomicInteger();try{java.call(l(1d),a->{originals.incrementAndGet();return 0d;});throw new AssertionError("bad java return");}
        catch(IllegalStateException e){check(e.getMessage().startsWith("HOOK_EXECUTION:")&&java.fatal==0&&originals.get()==0,"Java script error no original");}
        Backend nat=new Backend();nat.nativeKind=true;HookInvocationSession ns=session(nat,rule(null,handler(lit("bad")),null));ns.activate("generation-1","revision-1");
        try{nat.call(l(1d),a->0d);throw new AssertionError("native error returned");}catch(HookBackendUncertain e){check(nat.fatal==1&&ns.status().state()==HookInvocationSession.State.FAILED,"Native error failstop");}
        Backend guest=new Backend();HookInvocationSession gs=session(guest,rule(null,null,handler(member(id("call"),"result"))));gs.activate("generation-1","revision-1");
        Throwable sentinel=new ScriptFault("ORIGINAL");try{guest.call(l(1d),a->{throw sentinel;});}catch(Throwable actual){check(actual==sentinel,"guest ScriptFault not mislabeled");}
        check(gs.status().errors()==0,"original throw not script error");
    }
    static void parallel()throws Throwable {
        Backend b=new Backend();HookInvocationSession s=session(b,rule(null,null,handler(binary("+",member(id("call"),"result"),member(member(id("call"),"params"),"delta")))));s.activate("generation-1","revision-1");
        ExecutorService pool=Executors.newFixedThreadPool(8);AtomicInteger original=new AtomicInteger();List<Future<?>> workers=new ArrayList<>();
        for(int t=0;t<8;t++)workers.add(pool.submit(()->{for(int i=0;i<1000;i++)try{Object result=b.call(l((double)i),a->{original.incrementAndGet();return a.get(0);});if(!result.equals((double)i+1))throw new AssertionError("cross-thread result");}catch(Throwable e){throw new AssertionError(e);}}));
        try{for(Future<?> w:workers)w.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        check(original.get()==8000&&s.status().hits()==8000&&s.status().inFlight()==0,"8-thread 8000 exact original counts");s.disable("generation-1","revision-1");
    }
    static void commands()throws Throwable {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicInteger effects=new AtomicInteger(),responses=new AtomicInteger(),rejections=new AtomicInteger(),unknown=new AtomicInteger();
        HookCommandDispatcher dispatcher=new HookCommandDispatcher(raw->{effects.incrementAndGet();entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("command deadline");return "owned-response";});
        HookCommandDispatcher.Sink sink=new HookCommandDispatcher.Sink(){
            public void response(String id,String response){responses.incrementAndGet();done.countDown();}
            public void rejected(String id,Exception error){rejections.incrementAndGet();}
            public void unknown(String id,Error error){unknown.incrementAndGet();done.countDown();}
        };
        dispatcher.submit("one","owned",sink);check(entered.await(5,TimeUnit.SECONDS),"command entered");
        dispatcher.submit("two","owned",sink);check(rejections.get()==1&&effects.get()==1,"busy command never queued or replayed");
        // The caller may already have timed out; completion is delivered exactly once with its original id.
        release.countDown();check(done.await(5,TimeUnit.SECONDS)&&responses.get()==1&&effects.get()==1,"late ACK no replay");
        CountDownLatch uncertain=new CountDownLatch(1);AtomicInteger definitive=new AtomicInteger();
        HookCommandDispatcher failing=new HookCommandDispatcher(raw->{throw new HookBackendUncertain(new Exception("owned partial publish"));});
        failing.submit("uncertain","owned",new HookCommandDispatcher.Sink(){
            public void response(String id,String response){definitive.incrementAndGet();uncertain.countDown();}
            public void rejected(String id,Exception error){definitive.incrementAndGet();uncertain.countDown();}
            public void unknown(String id,Error error){unknown.incrementAndGet();uncertain.countDown();}
        });
        check(uncertain.await(5,TimeUnit.SECONDS)&&definitive.get()==0&&unknown.get()==1,"uncertain backend has no success or failed ACK");
    }
}
