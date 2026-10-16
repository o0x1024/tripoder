package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.TextView;

/** Visible, authenticated engine startup. Does not accept guest components or grant a control lease. */
public final class RuntimeControllerGateway extends Activity {
    private boolean started;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{
            ControllerTrust.requireActivity(this,getInitialCaller());
            Intent input=getIntent();
            if(input.getExtras()!=null||input.getData()!=null||input.getClipData()!=null||input.getSelector()!=null)throw new SecurityException("CONTROL_GATEWAY_INPUT_REJECTED");
            TextView text=new TextView(this);text.setText("正在连接 Tripoder 引擎…");text.setTextSize(20);text.setGravity(android.view.Gravity.CENTER);setContentView(text);
        }catch(Exception error){reject(error);}
    }
    @Override protected void onPostResume(){
        super.onPostResume();if(started||isFinishing())return;started=true;
        // Post only after the Activity is resumed, so this is an actual foreground start.
        getWindow().getDecorView().post(()->{
            if(isFinishing()||isDestroyed())return;
            try{
                ControllerTrust.requireActivity(this,getInitialCaller());
                ComponentName service=startForegroundService(new Intent(this,RuntimeControllerService.class));
                if(service==null)throw new IllegalStateException("ENGINE_START_REJECTED");
                setResult(RESULT_OK);finish();
            }catch(Exception error){reject(error);}
        });
    }
    private void reject(Exception error){setResult(RESULT_CANCELED,new Intent().putExtra("error",error.getClass().getSimpleName()+":"+error.getMessage()));finish();}
}
