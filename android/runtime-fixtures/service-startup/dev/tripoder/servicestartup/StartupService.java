package dev.tripoder.servicestartup;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import org.json.*;

public class StartupService extends Service {
    private volatile int starts,startId;private volatile boolean intentLogical;
    @Override public int onStartCommand(Intent intent,int flags,int id){starts++;startId=id;intentLogical=intent!=null&&new ComponentName(this,StartupService.class).equals(intent.getComponent());return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        if(code!=1)return super.onTransact(code,data,reply,flags);data.enforceInterface(StartupApplication.DESCRIPTOR);
        try{JSONObject result=new JSONObject().put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("process",Application.getProcessName()).put("package",getPackageName()).put("owned",getApplication() instanceof StartupApplication&&getApplicationContext()==getApplication()).put("starts",starts).put("startId",startId).put("intentLogical",intentLogical&&new ComponentName(StartupService.this,StartupService.class).equals(intent.getComponent())).put("returnedMarker",new File(getFilesDir(),"startup-request-returned").exists());reply.writeNoException();reply.writeString(result.toString());return true;}catch(Exception error){throw new IllegalStateException(error);}
    }};}
    public static final class Absent extends StartupService {}
}
