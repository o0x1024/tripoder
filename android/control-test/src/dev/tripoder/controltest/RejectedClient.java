package dev.tripoder.controltest;

import android.app.Service;
import android.content.*;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.util.concurrent.*;

/** A different installed package with the same development certificate. Not part of either product APK. */
public final class RejectedClient extends Service {
    private final IBinder endpoint=new ControlWire.Endpoint(){
        @Override protected void authorize(int code,int pid,int uid){if(code!=ControlWire.EXECUTE)throw new SecurityException("TEST_PROTOCOL_REJECTED");}
        @Override protected Bundle handle(int code,Bundle input,int pid,int uid)throws Exception{
            Bundle result=new Bundle();String command=input.getString("command");
            if("owner".equals(command)){result.putBinder("owner",new ControlWire.Owner(getPackageManager().getApplicationInfo("dev.tripoder.engine",0).uid));return result;}
            if(!"attempt".equals(command))throw new IllegalArgumentException("TEST_COMMAND_REJECTED");
            ControlTestBase.Link link=new ControlTestBase.Link();boolean bound=bindService(new Intent().setComponent(new ComponentName("dev.tripoder.engine","dev.tripoder.runtime.RuntimeControllerService")),link,BIND_AUTO_CREATE);
            try{
                if(!bound||!link.ready.await(10,TimeUnit.SECONDS)||link.peer==null)throw new IllegalStateException("TEST_BIND_FAILED");
                Bundle owner=new Bundle();owner.putBinder("owner",new Binder());try{ControlWire.call(link.peer,ControlWire.CONNECT,owner);result.putBoolean("blocked",false);}catch(SecurityException expected){result.putBoolean("blocked",true);result.putString("message",expected.getMessage());}
                result.putInt("uid",android.os.Process.myUid());result.putInt("pid",android.os.Process.myPid());return result;
            }finally{if(bound)unbindService(link);}
        }
    };
    @Override public IBinder onBind(Intent intent){return endpoint;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_NOT_STICKY;}
}
