package dev.tripoder.servicestartup;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import org.json.*;

public final class PassiveService extends Service {
    public static final class NeverCreated extends Service {
        @Override public void onCreate(){throw new AssertionError("NON_CREATING_BIND_STARTED_CANCELED_SERVICE");}
        @Override public IBinder onBind(Intent intent){throw new AssertionError("CANCELED_SERVICE_BOUND");}
    }
    private volatile int starts;private volatile boolean intentLogical;
    @Override public void onCreate(){super.onCreate();marker(PassiveApplication.CREATED);}
    @Override public void onDestroy(){marker(PassiveApplication.DESTROYED);super.onDestroy();}
    private void marker(String name){try{StartupApplication.write(new File(getFilesDir(),name),Integer.toString(android.os.Process.myPid()));}catch(IOException error){throw new IllegalStateException(error);}}
    @Override public int onStartCommand(Intent intent,int flags,int id){starts++;intentLogical=new ComponentName(this,PassiveService.class).equals(intent.getComponent());return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        if(code!=1)return super.onTransact(code,data,reply,flags);data.enforceInterface(PassiveApplication.DESCRIPTOR);
        try{JSONObject result=new JSONObject().put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("process",Application.getProcessName()).put("package",getPackageName()).put("owned",getApplication() instanceof PassiveApplication&&getApplicationContext()==getApplication()).put("starts",starts).put("intentLogical",intentLogical&&new ComponentName(PassiveService.this,PassiveService.class).equals(intent.getComponent()));reply.writeNoException();reply.writeString(result.toString());return true;}catch(Exception error){throw new IllegalStateException(error);}
    }};}
}
