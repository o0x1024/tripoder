package dev.tripoder.selfhookprobe;

import static dev.tripoder.selfhookprobe.ProbeFiles.*;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import dev.tripoder.selfhook.OwnedIntHook;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Owned ABI only. Never installs into Framework or another application's code. */
public final class ActiveEntryProbeActivity extends Activity {
    private int checks;
    private final AtomicInteger attemptedCalls=new AtomicInteger();
    private void check(boolean value,String reason){checks++;require(value,reason);}
    private int invoke(Method method,int value) throws Exception {attemptedCalls.incrementAndGet();return (Integer)method.invoke(null,value);}
    private static JSONObject awaitCompilation(Method method,String expected) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+10000;JSONObject sample;
        do {for(int i=0;i<10000;i++)method.invoke(null,1);sample=ArtProbe.sample(method);
            if(expected.equals(ArtCompilation.kind(sample)))return sample;
            require(SystemClock.elapsedRealtime()<deadline,"ACTIVE_ENTRY_COMPILATION_NOT_OBSERVED");Thread.sleep(10);
        }while(true);
    }
    private static void pressure(){byte[][] pressure=new byte[8][];for(int i=0;i<8;i++)pressure[i]=new byte[32768];System.gc();}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);view.setPadding(32,64,32,32);setContentView(view);
        String id=getIntent().getStringExtra("runId"),compilation=getIntent().getStringExtra("artCompilation");
        new Thread(()->execute(id,compilation,view),"tp-active-entry-probe").start();
    }
    private void execute(String id,String compilation,TextView view){
        JSONObject report=new JSONObject();File directory=null;boolean loaded=false,owns=false;
        try {
            String runId=UUID.fromString(id).toString();require("jit".equals(compilation)||"aot".equals(compilation),"EXPLICIT_ART_COMPILATION_REQUIRED");
            File base=getExternalFilesDir(null);require(base!=null,"EVIDENCE_DIRECTORY_UNAVAILABLE");
            directory=new File(base,runId);require(directory.mkdir(),"RUN_DIRECTORY_EXISTS_OR_UNAVAILABLE");owns=true;
            report.put("schema",1).put("phase","H3g-owned-active-entry").put("runId",runId).put("artCompilation",compilation);
            ProbeEnvironment.load(this,report);loaded=true;
            Method target=ActiveIntFixture.class.getDeclaredMethod("compute",int.class),caller=ActiveIntCaller.class.getDeclaredMethod("call",int.class);
            report.put("targetBefore",awaitCompilation(target,compilation)).put("callerBefore",awaitCompilation(caller,compilation));
            ActiveIntFixture.calls.set(0);
            ActiveIntFixture.entered=new CountDownLatch(1);ActiveIntFixture.release=new CountDownLatch(1);
            AtomicInteger oldResult=new AtomicInteger(Integer.MIN_VALUE),oldErrors=new AtomicInteger();
            Thread oldFrame=new Thread(()->{try{oldResult.set(invoke(target,42));}catch(Throwable error){oldErrors.incrementAndGet();}},"tp-old-managed-frame");oldFrame.start();
            check(ActiveIntFixture.entered.await(3,TimeUnit.SECONDS),"OLD_MANAGED_FRAME_ENTERED");
            check(OwnedIntHook.prepare(target,new Method[]{caller},100),"PREPARE_SEPARATE_NATIVE_METADATA");
            report.put("prepared",new JSONObject(OwnedIntHook.report())).put("callerAfterDeopt",ArtProbe.sample(caller));
            check(OwnedIntHook.toggle(true),"ACTIVATE_WITH_OLD_FRAME_PRESENT");
            JSONObject active=ArtProbe.sample(target);report.put("targetActive",active);
            check((active.getJSONArray("words").getLong(1)&0x100)==0,"TARGET_REMAINS_MANAGED");
            check(invoke(target,2)==103,"NEW_CALL_USES_NATIVE_HELPER");
            for(int i=0;i<1000;i++)check(invoke(caller,2)==103,"DEOPTIMIZED_OWNED_CALLER_INTERCEPTED");
            attemptedCalls.incrementAndGet();check(OwnedIntHook.callOriginal(4)==5,"EXPLICIT_ORIGINAL_WITHOUT_REENTRY");
            try{invoke(target,-1);throw new IllegalStateException("ORIGINAL_EXCEPTION_MISSING");}
            catch(InvocationTargetException error){check(error.getCause()==ActiveIntFixture.marker,"ORIGINAL_EXCEPTION_OBJECT_IDENTITY");}
            for(int i=0;i<4;i++){pressure();check(oldFrame.isAlive()&&invoke(target,2)==103,"GC_WITH_OLD_MANAGED_FRAME");}
            ActiveIntFixture.release.countDown();oldFrame.join(3000);
            check(!oldFrame.isAlive()&&oldErrors.get()==0&&oldResult.get()==43,"OLD_FRAME_COMPLETES_ORIGINAL_VERSION");
            ActiveIntFixture.entered=new CountDownLatch(1);ActiveIntFixture.release=new CountDownLatch(1);
            AtomicInteger callbackResult=new AtomicInteger(Integer.MIN_VALUE),callbackErrors=new AtomicInteger();
            Thread callback=new Thread(()->{try{callbackResult.set(invoke(target,42));}catch(Throwable error){callbackErrors.incrementAndGet();}},"tp-inflight-native-helper");callback.start();
            check(ActiveIntFixture.entered.await(3,TimeUnit.SECONDS),"NATIVE_HELPER_ORIGINAL_IN_FLIGHT");
            check(new JSONObject(OwnedIntHook.report()).getLong("inflight")==1,"CALLBACK_IN_FLIGHT_OBSERVED");
            check(OwnedIntHook.toggle(false),"DISABLE_WITH_HELPER_FRAME_PRESENT");
            long callbacksBefore=new JSONObject(OwnedIntHook.report()).getLong("callbacks");
            check(invoke(target,3)==4&&new JSONObject(OwnedIntHook.report()).getLong("callbacks")==callbacksBefore,"NEW_DISABLED_CALL_BYPASSES_HELPER");
            for(int i=0;i<4;i++){pressure();check(callback.isAlive(),"GC_WITH_NATIVE_HELPER_ORIGINAL_FRAME");}
            ActiveIntFixture.release.countDown();callback.join(3000);
            check(!callback.isAlive()&&callbackErrors.get()==0&&callbackResult.get()==143,"INFLIGHT_CALLBACK_RETAINS_CAPTURED_VERSION");
            AtomicBoolean stop=new AtomicBoolean();AtomicInteger workerErrors=new AtomicInteger();AtomicLong workerCalls=new AtomicLong();
            CountDownLatch started=new CountDownLatch(4);Thread[] workers=new Thread[4];
            for(int i=0;i<4;i++){workers[i]=new Thread(()->{started.countDown();
                while(!stop.get()){try{int value=invoke(target,7);if(value!=8&&value!=108)workerErrors.incrementAndGet();}
                    catch(Throwable error){workerErrors.incrementAndGet();}workerCalls.incrementAndGet();}},"tp-managed-entry-worker-"+i);workers[i].start();}
            check(started.await(3,TimeUnit.SECONDS),"CONCURRENT_WORKERS_STARTED");
            while(workerCalls.get()<1000)Thread.yield();
            try{for(int i=0;i<20;i++){check(OwnedIntHook.toggle(true),"CONCURRENT_ENABLE");Thread.sleep(2);
                    check(OwnedIntHook.toggle(false),"CONCURRENT_DISABLE");Thread.sleep(2);}}
            finally{stop.set(true);for(Thread worker:workers)worker.join(3000);}
            check(workerErrors.get()==0&&workerCalls.get()>=1000,"CONCURRENT_ENTRY_RESULTS");
            for(Thread worker:workers)check(!worker.isAlive(),"CONCURRENT_WORKER_JOINED");
            check(ActiveIntFixture.calls.get()==attemptedCalls.get(),"ORIGINAL_EXECUTED_EXACTLY_ONCE");
            JSONObject nativeState=new JSONObject(OwnedIntHook.report());
            check(nativeState.getLong("inflight")==0&&nativeState.getLong("originals")==nativeState.getLong("callbacks")+1,"NATIVE_ORIGINAL_AND_INFLIGHT_COUNTS");
            check(nativeState.getInt("transitions")==42&&nativeState.getInt("retainedRoots")==5&&nativeState.getInt("retainedRelayPages")==1,"BOUNDED_PROCESS_LIFETIME_STORAGE");
            JSONObject after=ArtProbe.sample(target);
            check((after.getJSONArray("words").getLong(1)&0x100)==0,"TARGET_REMAINS_MANAGED_AFTER_DISABLE");
            report.put("storage",new JSONObject(dev.tripoder.selfhook.OriginalMethodStorage.report()));
            report.put("targetAfter",after).put("native",nativeState).put("attemptedCalls",attemptedCalls.get()).put("originalBodyCalls",ActiveIntFixture.calls.get())
                .put("workerCalls",workerCalls.get()).put("workerErrors",workerErrors.get()).put("oldFrameResult",oldResult.get()).put("inflightResult",callbackResult.get())
                .put("gcRequestsWithFrames",8).put("checks",checks).put("passed",true);
            String maps=read("/proc/self/maps");write(new File(directory,"maps.txt"),maps);report.put("mapsSha256",sha(new File(directory,"maps.txt")));
            String smaps=read("/proc/self/smaps");write(new File(directory,"smaps.txt"),smaps);report.put("smapsSha256",sha(new File(directory,"smaps.txt")));
            byte[] relay=OwnedIntHook.relayBytes();require(relay!=null&&relay.length==4096,"OWNED_RELAY_EVIDENCE");
            File relayFile=new File(directory,"relay.bin");try(java.io.FileOutputStream out=new java.io.FileOutputStream(relayFile)){out.write(relay);out.getFD().sync();}
            report.put("relaySha256",sha(relayFile));
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString()).put("checks",checks);
            if(error.getCause()!=null)report.put("cause",error.getCause().toString());}catch(Exception ignored){}}
        finally{if(ActiveIntFixture.release!=null)ActiveIntFixture.release.countDown();}
        try {
            if(loaded){report.put("nativeStatus",new JSONObject(OwnedIntHook.report()));
                report.put("storage",new JSONObject(dev.tripoder.selfhook.OriginalMethodStorage.report()));}
            if(!owns||directory==null){runOnUiThread(()->view.setText("Active entry probe needs a fresh runId"));return;}
            publishResult(directory,report.toString(2)+"\n");
            String text="Active managed entry "+(report.optBoolean("passed")?"PASS":"FAIL")+"\n"+report.optString("runId");runOnUiThread(()->view.setText(text));
        }catch(Throwable error){String text="Active entry evidence failed: "+error;runOnUiThread(()->view.setText(text));}
    }
}
