package dev.tripoder.nativeserviceprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;

public final class ProbeActivity extends Activity {
    private void note(String line){android.util.Log.i("NativeProbeClient",line);}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        Intent intent=new Intent(this,HostService.class).putExtra("apk",getIntent().getStringExtra("apk")).putExtra("entry",getIntent().getStringExtra("entry")).putExtra("function",getIntent().getStringExtra("function"));
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){
                new Thread(()->{try{note("connected descriptor="+binder.getInterfaceDescriptor()+" alive="+binder.pingBinder());}catch(Throwable error){note("ERROR "+error);}}).start();
            }
            @Override public void onServiceDisconnected(ComponentName name){note("disconnected");}
            @Override public void onBindingDied(ComponentName name){note("bindingDied");}
            @Override public void onNullBinding(ComponentName name){note("nullBinding");}
        };
        note("bind="+bindIsolatedService(intent,Context.BIND_AUTO_CREATE,"host_"+System.nanoTime(),getMainExecutor(),connection));
    }
}
