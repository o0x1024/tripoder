package dev.tripoder.p2;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Owned arbitrary Java targets. The controller compares observations; no runtime APIs here. */
public final class Hooks {
    public static final class Init { public static int count; }
    public static final class Target {
        static { Init.count++; }
        public static final AtomicInteger addCalls=new AtomicInteger(),textCalls=new AtomicInteger(),longCalls=new AtomicInteger(),throwCalls=new AtomicInteger(),beforeCalls=new AtomicInteger(),afterCalls=new AtomicInteger(),recursiveCalls=new AtomicInteger(),threadCalls=new AtomicInteger();
        public static final IllegalArgumentException failure=new IllegalArgumentException("owned-original");
        public static int add(int a,int b){addCalls.incrementAndGet();return a+b;}
        public String add(String value){textCalls.incrementAndGet();return value==null?"original-null":"original:"+value;}
        public static long exact(long value){longCalls.incrementAndGet();return value;}
        public static int throwing(){throwCalls.incrementAndGet();throw failure;}
        public static int beforeFailure(int value){beforeCalls.incrementAndGet();return value;}
        public static int afterFailure(int value){afterCalls.incrementAndGet();return value;}
        public static int recursive(int value){recursiveCalls.incrementAndGet();return value==0?0:recursive(value-1)+1;}
        public static int threaded(int value){threadCalls.incrementAndGet();return value+1;}
        public static int blocking()throws InterruptedException{Hold.entered.countDown();if(!Hold.release.await(25,TimeUnit.SECONDS))throw new IllegalStateException("OWNED_HOLD_TIMEOUT");return 7;}
        public static void reset(){for(AtomicInteger value:new AtomicInteger[]{addCalls,textCalls,longCalls,throwCalls,beforeCalls,afterCalls,recursiveCalls,threadCalls})value.set(0);}
    }
    public static final class Hold {
        static CountDownLatch entered,release,finished;static volatile int result;static volatile String error;
        static void start(){entered=new CountDownLatch(1);release=new CountDownLatch(1);finished=new CountDownLatch(1);result=0;error=null;new Thread(()->{try{result=Target.blocking();}catch(Throwable e){error=e.toString();}finally{finished.countDown();}},"owned-inflight-hook").start();}
    }
    public static final class Main extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);new Thread(()->{
            JSONObject result=new JSONObject();try{
                result.put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("package",getPackageName());
                if("init".equals(getIntent().getStringExtra("mode")))result.put("initializations",Init.count);
                else if("churn-start".equals(getIntent().getStringExtra("mode")))result.put("churn",HookStackChurn.start());
                else if("churn-stop".equals(getIntent().getStringExtra("mode")))result.put("churn",HookStackChurn.stop());
                else if(getIntent().getStringExtra("mode").startsWith("native-"))result.put("native",NativeHooks.observe(getIntent().getStringExtra("mode"),getApplicationInfo().nativeLibraryDir,getFilesDir()));
                else if("hold".equals(getIntent().getStringExtra("mode"))){Hold.start();result.put("entered",Hold.entered.await(10,TimeUnit.SECONDS));}
                else if("release".equals(getIntent().getStringExtra("mode"))){Hold.release.countDown();result.put("finished",Hold.finished.await(10,TimeUnit.SECONDS)).put("heldResult",Hold.result).put("heldError",nullable(Hold.error));}
                else {
                    Target.reset();Target instance=new Target();
                    result.put("add",Target.add(2,3)).put("text",instance.add("owned")).put("nullText",nullable(instance.add(null)));
                    result.put("exact",Long.toString(Target.exact(9007199254740993L)));
                    try{Target.throwing();result.put("throwMissing",true);}catch(Throwable e){result.put("sameOriginalException",e==Target.failure).put("originalException",e.getClass().getName());}
                    try{result.put("beforeValue",Target.beforeFailure(7));}catch(Throwable e){result.put("beforeError",e.getClass().getName()+":"+e.getMessage());}
                    try{result.put("afterValue",Target.afterFailure(7));}catch(Throwable e){result.put("afterError",e.getClass().getName()+":"+e.getMessage());}
                    result.put("recursive",Target.recursive(3));
                    AtomicInteger sum=new AtomicInteger();ConcurrentLinkedQueue<String> failures=new ConcurrentLinkedQueue<>();CountDownLatch done=new CountDownLatch(8);
                    for(int t=0;t<8;t++)new Thread(()->{try{for(int i=0;i<25;i++)sum.addAndGet(Target.threaded(i));}catch(Throwable e){failures.add(e.toString());}finally{done.countDown();}},"owned-hook-caller").start();
                    if(!done.await(20,TimeUnit.SECONDS))throw new IllegalStateException("OWNED_THREADS_TIMEOUT");
                    result.put("threadSum",sum.get()).put("threadErrors",new JSONArray(failures));
                    result.put("counts",new JSONObject().put("add",Target.addCalls.get()).put("text",Target.textCalls.get()).put("long",Target.longCalls.get()).put("throw",Target.throwCalls.get()).put("before",Target.beforeCalls.get()).put("after",Target.afterCalls.get()).put("recursive",Target.recursiveCalls.get()).put("thread",Target.threadCalls.get()));
                }
            }catch(Throwable error){try{result.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
            runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));finish();});
        },"owned-hook-invocations").start();}
        private static Object nullable(Object value){return value==null?JSONObject.NULL:value;}
    }
    private Hooks(){}
}
