package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import static dev.tripoder.runtime.script.ScriptContractTest.*;

final class NativeBufferScopeTest {
    private static final List<String> TYPES=List.of("uint64","int32");
    static void run()throws Throwable{
        rejects(()->NativeBufferScope.capture(0,1,8),"NATIVE_BUFFER_OUTSIDE");
        inspect(4,2,8,new byte[]{1,2},"COMPLETE","0102",false);
        inspect(4096,2048,4,new byte[]{1,2,3,4},"COMPLETE","01020304",true);
        inspect(8,0,8,null,"NO_DATA","",false);
        inspect(8,-1,8,null,"NO_DATA","",false);
        inspect(1,2,8,null,"INVALID_LENGTH","",false);
        inspect(8,2,8,null,"UNAVAILABLE","",false);
        inspect(8,2,8,new byte[]{1},"UNAVAILABLE","",false);
        var pool=Executors.newFixedThreadPool(4);
        try{List<Future<?>> runs=new ArrayList<>();for(int i=0;i<16;i++)runs.add(pool.submit(()->{try{inspect(8,1,8,new byte[]{7},"COMPLETE","07",false);}catch(Throwable error){throw new RuntimeException(error);}}));for(var run:runs)run.get();}
        finally{pool.shutdownNow();}
    }
    private static void inspect(int capacity,int actual,int limit,byte[] bytes,String state,String hex,boolean truncated)throws Throwable{
        Backend backend=new Backend();backend.nativeKind=true;
        Map<String,Object> captured=new LinkedHashMap<>();int[] reads={0};
        // Real restricted AST capability executes inside the actual invocation session.
        var after=RestrictedAst.compile(arrow(member(id("call"),"result")),Map.of());
        var capture=RestrictedAst.capability(arguments->{
            @SuppressWarnings("unchecked") Map<String,Object> call=(Map<String,Object>)arguments.get(0);
            captured.putAll(NativeBufferScope.capture(0,1,limit));
            try{NativeBufferScope.capture(0,1,limit);throw new AssertionError("duplicate capture accepted");}catch(ScriptFault expected){check(expected.getMessage().contains("NATIVE_BUFFER_ONCE"),"duplicate rejected");}return after.invoke(call);
        });
        var expression=m("type","CallExpression","callee",id("capture"),"arguments",l(id("call")));
        var hook=new HookRule("owned",TYPES,"int32",null,null,RestrictedAst.capturedHandler(capture));
        var session=new HookInvocationSession("g","r",List.of(hook),Map.of(),Map.of(),backend);session.activate("g","r");
        try(var scope=new NativeBufferScope("owned",TYPES,"int32",(index,count)->{reads[0]++;check(index==0&&count==Math.min(actual,limit),"native actual-count read");return bytes;})){
            rejects(()->NativeBufferScope.capture(0,1,limit),"NATIVE_BUFFER_AFTER");
            var args=List.<Object>of(BigInteger.valueOf(4096),(double)capacity);
            Object value=backend.call(args,a->{scope.complete(a,(double)actual);return (double)actual;});
            check(value.equals((double)actual),"native original result unchanged");
        }
        check(captured.get("state").equals(state)&&captured.get("hex").equals(hex)&&captured.get("truncated").equals(truncated),"native capture state "+state);
        check(reads[0]==(state.equals("NO_DATA")||state.equals("INVALID_LENGTH")?0:1),"native failed reads never touch memory");
        rejects(()->NativeBufferScope.capture(0,1,limit),"NATIVE_BUFFER_OUTSIDE");
    }
}
