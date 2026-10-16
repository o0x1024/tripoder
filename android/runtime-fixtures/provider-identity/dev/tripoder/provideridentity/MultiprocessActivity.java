package dev.tripoder.provideridentity;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class MultiprocessActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TextView text=new TextView(this);text.setText("Tripoder · multiprocess Provider observation");setContentView(text);
        new Thread(()->{
            JSONObject result=new JSONObject();Link link=new Link();boolean baseline="true".equals(getIntent().getStringExtra("baseline"));
            try{
                result.put("main",MultiprocessPeer.sample(this));
                runOnUiThread(()->{link.bound=bindService(new Intent(this,MultiprocessPeer.class),link,BIND_AUTO_CREATE);if(!link.bound)link.ready.countDown();});
                // The runtime allows 30 seconds for a cold process, including
                // all pre-guest hooks. The observer must not expire before it.
                if(!link.ready.await(35,TimeUnit.SECONDS)||link.peer==null)throw new IllegalStateException("MULTIPROCESS_PEER_NOT_CONNECTED");
                Parcel input=Parcel.obtain(),output=Parcel.obtain();try{if(!link.peer.transact(1,input,output,0))throw new IllegalStateException("PEER_TRANSACTION_REJECTED");output.readException();result.put("peer",new JSONObject(output.readString()));}finally{input.recycle();output.recycle();}
                result.put("mainAfterPeer",MultiprocessPeer.sample(this));result.put("completed",true);
            }catch(Throwable error){try{result.put("completed",false).put("error",android.util.Log.getStackTraceString(error));}catch(Exception ignored){}}
            finally{
                if(link.bound)runOnUiThread(()->unbindService(link));
                try{
                    if(baseline){String run=getIntent().getStringExtra("runId");if(run==null||!run.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("BASELINE_RUN_REQUIRED");result.put("deviceBuild",Build.FINGERPRINT);try(var out=new FileOutputStream(new File(getExternalFilesDir(null),"result-"+run+".json"))){out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
                    runOnUiThread(()->{text.setText(result.toString());setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));if(!baseline)finish();});
                }catch(Exception error){throw new IllegalStateException("MULTIPROCESS_OBSERVATION_NOT_SAVED",error);}
            }
        },"multiprocess-observation").start();
    }
    static final class Link implements ServiceConnection{final CountDownLatch ready=new CountDownLatch(1);volatile IBinder peer;volatile boolean bound;public void onServiceConnected(ComponentName name,IBinder value){peer=value;ready.countDown();}public void onServiceDisconnected(ComponentName name){}public void onNullBinding(ComponentName name){ready.countDown();}}
}
