package dev.tripoder.runtime;

import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Only the owned instrumentation/main thread's frame names; no heap or guest trace. */
final class FixedRegistryProgress implements AutoCloseable {
    private final File progress,stacks;
    private final Thread instrumented=Thread.currentThread();
    private final AtomicBoolean finished=new AtomicBoolean();
    private volatile String phase="CREATED";
    FixedRegistryProgress(Context context,String run)throws Exception{
        progress=new File(context.getExternalFilesDir(null),"fixed-registry-"+run+".progress.json");
        stacks=new File(context.getExternalFilesDir(null),"fixed-registry-"+run+".stacks.json");
        stage("CREATED");
        Thread sampler=new Thread(()->{
            try{
                Thread.sleep(20000);if(finished.get())return;
                JSONObject record=new JSONObject().put("phase",phase).put("sampleStarted",true);
                Files.writeString(stacks.toPath(),record.toString(2)+"\n");
                record.put("instrumentation",frames(instrumented));
                record.put("main",frames(Looper.getMainLooper().getThread())).put("sampleCompleted",true);
                Files.writeString(stacks.toPath(),record.toString(2)+"\n");
            }catch(Exception error){android.util.Log.w("TripoderFixedProgress","OWNED_STACK_OBSERVATION_UNAVAILABLE:"+error.getClass().getName());}
        },"owned-fixed-registry-observer");sampler.setDaemon(true);sampler.start();
    }
    private static org.json.JSONArray frames(Thread thread){
        org.json.JSONArray rows=new org.json.JSONArray();StackTraceElement[] frames=thread.getStackTrace();
        for(int i=0;i<Math.min(48,frames.length);i++)rows.put(frames[i].toString());return rows;
    }
    void stage(String value)throws Exception{
        phase=value;Files.writeString(progress.toPath(),new JSONObject().put("phase",value).put("elapsedRealtime",android.os.SystemClock.elapsedRealtime()).toString()+"\n");
    }
    @Override public void close(){finished.set(true);}
}
