package dev.tripoder.selfhookprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import dev.tripoder.selfhook.ManagedHook;
import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Negative evidence: a pre-existing AOT frame is not a new entry invocation. */
public final class InFlightProbeActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);TextView view=new TextView(this);setContentView(view);
        String run=getIntent().getStringExtra("runId");boolean early=getIntent().getBooleanExtra("earlyPolicy",false);
        new Thread(()->run(run,early,view),"owned-inflight-check").start();
    }
    private void run(String id,boolean early,TextView view) {
        JSONObject result=new JSONObject();File directory=null;
        try {
            String run=UUID.fromString(id).toString();
            directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_INFLIGHT_RUN");
            result.put("schema",1).put("phase","H3m-inflight-boundary").put("runId",run).put("artCompilation","aot");
            ProbeEnvironment.load(this,result);
            JSONObject evidence=new JSONObject();result.put("inflight",evidence);check(evidence,early);
            for(String name:new String[]{"maps","smaps"}) {
                File file=new File(directory,name+".txt");ProbeFiles.write(file,ProbeFiles.read("/proc/self/"+name));
                result.put(name+"Sha256",ProbeFiles.sha(file));
            }
            result.put("passed",true);
        } catch(Throwable error) {try{result.put("passed",false).put("error",error.toString());}catch(Exception ignored){}}
        try {
            if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");
            String message="Owned in-flight boundary "+result.optBoolean("passed")+" "+result.optString("error");
            runOnUiThread(()->view.setText(message));
        } catch(Throwable error){runOnUiThread(()->view.setText("In-flight evidence failed: "+error));}
    }
    private static void check(JSONObject evidence,boolean early) throws Exception {
        evidence.put("earlyPolicy",early).put("policyBefore",new JSONObject(ManagedHook.processPolicy()));
        Method caller=InFlightCaller.class.getDeclaredMethod("call",boolean.class,int.class);
        Method target=EntryWriterFixture.class.getDeclaredMethod("value",int.class);
        JSONObject before,targetBefore;long compileDeadline=SystemClock.elapsedRealtime()+10000;
        do {
            for(int i=0;i<1000;i++) {
                ProbeFiles.require((Integer)caller.invoke(null,false,9)==10,"INFLIGHT_BASELINE");
                ProbeFiles.require((Integer)target.invoke(null,9)==10,"INFLIGHT_TARGET_BASELINE");
            }
            before=ArtProbe.sample(caller);targetBefore=ArtProbe.sample(target);
            evidence.put("callerBefore",before).put("targetBefore",targetBefore);
            if(ArtCompilation.kind(before).equals("aot")&&ArtCompilation.kind(targetBefore).equals("aot"))break;
            ProbeFiles.require(SystemClock.elapsedRealtime()<compileDeadline,"ACTUAL_AOT_PAIR_REQUIRED");Thread.sleep(10);
        } while(true);
        if(early)ManagedHook.initializeProcess();
        evidence.put("policyBeforeFrame",new JSONObject(ManagedHook.processPolicy())).put("callerBeforeFrame",ArtProbe.sample(caller));
        ManagedHook.Handle handle=ManagedHook.prepare(target,new Method[0]);
        AtomicInteger value=new AtomicInteger(-1);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=new Thread(()->{try{value.set((Integer)caller.invoke(null,true,9));}catch(Throwable failure){error.set(failure);}},"owned-preexisting-aot-frame");
        boolean active=false;int initial=EntryWriterFixture.bodies.get();worker.start();
        try {
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(!InFlightCaller.enteredNative()){ProbeFiles.require(SystemClock.elapsedRealtime()<deadline,"INFLIGHT_BARRIER_TIMEOUT");Thread.sleep(1);}
            handle.enable(call->(Integer)call.original(call.arguments())+100);active=true;
            JSONObject enabled=new JSONObject(handle.report());
            InFlightCaller.releaseNative();worker.join(5000);
            ProbeFiles.require(!worker.isAlive()&&error.get()==null,"INFLIGHT_WORKER_FAILED:"+error.get());
            int fresh=(Integer)caller.invoke(null,false,9);
            JSONObject after=new JSONObject(handle.report());
            ProbeFiles.require(fresh==110&&enabled.getString("targetQuick").equals(enabled.getString("relay")),"FRESH_ENTRY_MUST_INTERCEPT");
            ProbeFiles.require(EntryWriterFixture.bodies.get()-initial==2,"INFLIGHT_BODIES_EXACT");
            ProbeFiles.require(value.get()==(early?110:10),"INFLIGHT_EXECUTION_BOUNDARY_CHANGED");
            evidence.put("enabled",enabled).put("after",after)
                .put("resumedValue",value.get()).put("freshValue",fresh).put("expectedHookedValue",110)
                .put("resumedCallCovered",value.get()==110).put("bodyCalls",2).put("productAccepted",false)
                .put("policyAfter",new JSONObject(ManagedHook.processPolicy()));
        } finally {
            InFlightCaller.releaseNative();worker.join(5000);
            ProbeFiles.require(!worker.isAlive(),"INFLIGHT_WORKER_NOT_JOINED");if(active)handle.disable();
        }
    }
}
