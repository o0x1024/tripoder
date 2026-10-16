package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.os.*;
import org.json.JSONObject;

/** A real source awaits Android's final result after the target dies before delivery. */
public final class LateReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);
        sendOrderedBroadcast(new Intent(this,Receivers.Cold.class),null,new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){try{
                JSONObject value=new JSONObject().put("code",getResultCode()).put("data",getResultData()).put("extrasAbsent",getResultExtras(false)==null).put("sourcePid",android.os.Process.myPid());
                LateReceiverActivity.this.setResult(RESULT_OK,new Intent().putExtra("result",value.toString()));
                new Handler(Looper.getMainLooper()).post(LateReceiverActivity.this::finish);
            }catch(Exception error){throw new IllegalStateException("LATE_RECEIVER_RESULT_FAILED",error);}}
        },null,19,"untouched-initial",null);
    }
}
