package dev.tripoder.runtimeprobe;

import android.content.*;
import android.os.*;
import dev.tripoder.runtime.*;
import java.io.*;
import java.nio.file.Files;
import org.json.JSONObject;

/** Test-only delay at the real platform send boundary; the original receiver/result remain intact. */
public final class ReceiverGateApplication extends RuntimeApplication {
    @Override public void sendOrderedBroadcast(Intent intent,String permission,BroadcastReceiver receiver,Handler scheduler,int code,String data,Bundle extras){
        if(RuntimeEngine.current==null){super.sendOrderedBroadcast(intent,permission,receiver,scheduler,code,data,extras);return;}
        File directory=new File(RuntimeEngine.current.data,"files"),armed=new File(directory,"receiver-gate-arm");
        try{
            if(!armed.exists()||!Files.readString(armed.toPath()).equals(RuntimeEngine.current.generation)){
                super.sendOrderedBroadcast(intent,permission,receiver,scheduler,code,data,extras);return;
            }
            if(!armed.delete()||!intent.hasExtra("tripoder.delivery"))throw new IllegalStateException("RECEIVER_GATE_SHAPE");
            JSONObject captured=new JSONObject().put("ticket",intent.getStringExtra("tripoder.delivery")).put("stub",intent.getComponent().getClassName()).put("source",RuntimeEngine.current.generation);
            ProbeReports.publish(new File(directory,"receiver-gate-held"),captured.toString());
            long deadline=SystemClock.elapsedRealtime()+30000;
            new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){
                File release=new File(directory,"receiver-gate-release");
                if(!release.exists()){
                    if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("RECEIVER_GATE_TIMEOUT");
                    new Handler(Looper.getMainLooper()).postDelayed(this,25);return;
                }
                if(!release.delete())throw new IllegalStateException("RECEIVER_GATE_RELEASE_FAILED");
                ReceiverGateApplication.super.sendOrderedBroadcast(intent,permission,receiver,scheduler,code,data,extras);
            }});
        }catch(Exception error){throw new IllegalStateException("RECEIVER_TEST_GATE_FAILED",error);}
    }
}
