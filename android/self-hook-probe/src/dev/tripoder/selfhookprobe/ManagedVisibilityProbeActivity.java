package dev.tripoder.selfhookprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.tripoder.selfhook.ManagedHook;
import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;
import org.json.*;
public final class ManagedVisibilityProbeActivity extends Activity {
    private static native boolean flushVisibility();
    static native long[] observe(Method method);
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView view=new TextView(this);setContentView(view);String id=getIntent().getStringExtra("runId");new Thread(()->run(id,view),"owned-visible-initialization").start();}
    private void run(String id,TextView view){JSONObject result=new JSONObject();File directory=null;
        try{String run=UUID.fromString(id).toString();directory=new File(getExternalFilesDir(null),run);ProbeFiles.require(directory.mkdir(),"FRESH_VISIBILITY_RUN");
            result.put("schema",1).put("phase","managed-visible-initialization").put("runId",run);ProbeEnvironment.load(this,result);ManagedHook.initializeProcess();
            Class<?> owner=Class.forName("dev.tripoder.selfhookprobe.ManagedAdmissionNamedTarget",true,getClassLoader());Method method=owner.getDeclaredMethod("read",String.class);
            result.put("beforeVisibility",new JSONArray(observe(method)));ProbeFiles.require(flushVisibility(),"VISIBILITY_BIND");result.put("afterVisibility",new JSONArray(observe(method)));
            ManagedHook.Handle handle=ManagedHook.prepare(method,new Method[0]);result.put("prepared",new JSONObject(handle.report()));ProbeFiles.write(new File(directory,"before-enable.json"),result.toString(2));
            handle.enable(call->false);ProbeFiles.require(Boolean.FALSE.equals(method.invoke(null,"owned")),"HOOK_VALUE");handle.disable();ProbeFiles.require(Boolean.TRUE.equals(method.invoke(null,"owned")),"ORIGINAL_VALUE");
            result.put("retired",new JSONObject(handle.report())).put("passed",true);
            File maps=new File(directory,"maps.txt");ProbeFiles.write(maps,ProbeFiles.read("/proc/self/maps"));result.put("mapsSha256",ProbeFiles.sha(maps));
        }catch(Throwable error){try{result.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
        try{if(directory!=null)ProbeFiles.publishResult(directory,result.toString(2)+"\n");String text=result.toString();runOnUiThread(()->view.setText(text));}catch(Throwable error){runOnUiThread(()->view.setText(error.toString()));}
    }
}
