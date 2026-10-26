package dev.tripoder.nativeserviceprobe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Ordinary isolated Java service that hosts a native-service entry point from another installed APK. */
public final class HostService extends Service {
    static native IBinder nativeStart(String apk,String entry,String function,String action,String data);
    @Override public IBinder onBind(Intent intent){
        try{
            System.loadLibrary("nativehost");
            IBinder binder=nativeStart(intent.getStringExtra("apk"),intent.getStringExtra("entry"),intent.getStringExtra("function"),intent.getAction(),intent.getDataString());
            android.util.Log.i("NativeHostJava","binder="+binder);
            return binder;
        }catch(Throwable error){android.util.Log.e("NativeHostJava","FAILED",error);return null;}
    }
}
