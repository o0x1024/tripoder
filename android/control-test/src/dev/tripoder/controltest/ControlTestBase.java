package dev.tripoder.controltest;

import android.app.*;
import android.content.*;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

abstract class ControlTestBase extends Instrumentation {
    final JSONArray checks=new JSONArray();final JSONObject report=new JSONObject();
    final List<Link> links=new ArrayList<>();Bundle args;String run;
    interface Attempt {void run()throws Exception;}
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;run=args.getString("runId");if(run==null||!run.matches("control-[a-f0-9]{16}"))throw new IllegalArgumentException("CONTROL_TEST_RUN_REQUIRED");start();}
    abstract void test()throws Exception;
    void startThroughWorkbench()throws Exception{
        startThroughWorkbench(true);
    }
    void startThroughWorkbench(boolean handoff)throws Exception{
        java.util.concurrent.atomic.AtomicReference<Activity> observed=new java.util.concurrent.atomic.AtomicReference<>();
        Application app=(Application)getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().equals("dev.tripoder.hooklab.WorkbenchActivity"))observed.set(a);}
            public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };
        app.registerActivityLifecycleCallbacks(observer);
        File signal=new File(getTargetContext().getExternalFilesDir(null),"launch-workbench-"+run+".json");
        try(var output=new FileOutputStream(signal)){output.write(new JSONObject().put("runId",run).put("component","dev.tripoder.workbench/dev.tripoder.hooklab.WorkbenchActivity").toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        Object application=getTargetContext().getApplicationContext();var field=application.getClass().getDeclaredField("engine");field.setAccessible(true);Object engine=field.get(application);
        var ready=engine.getClass().getDeclaredMethod("ready");ready.setAccessible(true);long deadline=SystemClock.elapsedRealtime()+25000;
        try{
            while(!(Boolean)ready.invoke(engine)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(25);
            var state=engine.getClass().getDeclaredField("failure");state.setAccessible(true);
            check("real-workbench-foreground-startup-connected",(Boolean)ready.invoke(engine),String.valueOf(state.get(engine)));
            var status=engine.getClass().getDeclaredField("status");status.setAccessible(true);report.put("workbenchStartupStatus",json((Bundle)status.get(engine)));
            if(handoff){var peer=engine.getClass().getDeclaredField("session");peer.setAccessible(true);execute((IBinder)peer.get(engine),"disconnect");}
        }finally{app.unregisterActivityLifecycleCallbacks(observer);Activity page=observed.get();if(handoff&&page!=null)runOnMainSync(page::finish);}
    }
    @Override public void onStart(){boolean passed=false;try{
        report.put("runId",run).put("startedAt",System.currentTimeMillis()).put("uid",android.os.Process.myUid()).put("pid",android.os.Process.myPid()).put("deviceBuild",Build.FINGERPRINT).put("scope","control-install-project-binding-limited");test();passed=true;
    }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
    finally{
        for(Link link:links)if(link.bound)getTargetContext().unbindService(link);
        Bundle result=new Bundle();try{report.put("passed",passed).put("checks",checks).put("fullP5Accepted",false).put("finishedAt",System.currentTimeMillis());File file=new File(getTargetContext().getExternalFilesDir(null),"result-"+run+".json");try(var stream=new FileOutputStream(file)){stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));stream.getFD().sync();}result.putString("result",report.toString());}catch(Exception error){passed=false;result.putString("error",error.toString());}finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }}
    IBinder bind(String pkg,String service)throws Exception{Link link=new Link();links.add(link);link.bound=getTargetContext().bindService(new Intent().setComponent(new ComponentName(pkg,service)),link,Context.BIND_AUTO_CREATE);if(!link.bound||!link.ready.await(15,TimeUnit.SECONDS)||link.peer==null)throw new IllegalStateException("TEST_BIND_FAILED:"+pkg);return link.peer;}
    IBinder engine()throws Exception{return bind("dev.tripoder.engine","dev.tripoder.runtime.RuntimeControllerService");}
    Bundle connect(IBinder entry,IBinder life)throws Exception{Bundle input=new Bundle();input.putBinder("owner",life);return ControlWire.call(entry,ControlWire.CONNECT,input);}
    ControlWire.Owner life()throws Exception{return new ControlWire.Owner(getTargetContext().getPackageManager().getApplicationInfo("dev.tripoder.engine",0).uid);}
    Bundle execute(IBinder peer,String command,String...pairs)throws Exception{return ControlWire.call(peer,ControlWire.EXECUTE,ControlWire.request(command,pairs));}
    void check(String name,boolean passed,Object value)throws Exception{
        checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",value));
        checkpoint(name);
        if(!passed)throw new IllegalStateException("ASSERTION_FAILED:"+name);
    }
    private void checkpoint(String step)throws Exception{
        JSONObject progress=new JSONObject().put("runId",run).put("lastCompletedCheck",step).put("checks",checks).put("time",System.currentTimeMillis()).put("terminal",false);
        android.util.AtomicFile file=new android.util.AtomicFile(new File(getTargetContext().getExternalFilesDir(null),"progress-"+run+".json"));FileOutputStream stream=file.startWrite();
        try{stream.write(progress.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}catch(Exception error){file.failWrite(stream);throw error;}
    }
    boolean rejected(Attempt action,Class<? extends RuntimeException> type,String message)throws Exception{try{action.run();return false;}catch(RuntimeException error){if(!type.isInstance(error)||!String.valueOf(error.getMessage()).contains(message))throw error;return true;}}
    JSONObject json(Bundle value)throws Exception{JSONObject out=new JSONObject();for(String key:value.keySet())if(!(value.get(key) instanceof IBinder))out.put(key,value.get(key));return out;}
    static final class Link implements ServiceConnection {final CountDownLatch ready=new CountDownLatch(1);IBinder peer;boolean bound;public void onServiceConnected(ComponentName name,IBinder value){peer=value;ready.countDown();}public void onServiceDisconnected(ComponentName name){}public void onNullBinding(ComponentName name){ready.countDown();}}
}
