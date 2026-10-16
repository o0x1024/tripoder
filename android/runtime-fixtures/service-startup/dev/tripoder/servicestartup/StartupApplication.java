package dev.tripoder.servicestartup;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Ordinary Android startService from attachBaseContext, with a real remote Binder. */
public final class StartupApplication extends Application {
    static final String REPORT="service-startup-contract.json",DESCRIPTOR="dev.tripoder.owned.startup";
    private long startNanos;private boolean returned,created;private String bindWithoutCreate;private ComponentName accepted;private final JSONArray checks=new JSONArray();
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);if(getProcessName().endsWith(":startup"))return;
        try{java.nio.file.Files.deleteIfExists(new File(getFilesDir(),REPORT).toPath());java.nio.file.Files.deleteIfExists(new File(getFilesDir(),"startup-request-returned").toPath());long before=SystemClock.elapsedRealtimeNanos();accepted=startService(new Intent(this,StartupService.class));startNanos=SystemClock.elapsedRealtimeNanos()-before;returned=true;
            write(new File(getFilesDir(),"startup-request-returned"),Long.toString(android.os.Process.myPid()));
        }catch(Throwable error){publish(error);throw new IllegalStateException("OWNED_STARTUP_REQUEST_FAILED",error);}
    }
    @Override public void onCreate(){super.onCreate();if(getProcessName().endsWith(":startup"))return;created=true;
        try{
            check("startService-returns-logical-component-during-attach",returned&&new ComponentName(this,StartupService.class).equals(accepted));
            Link nonCreating=new Link();boolean bound=bindService(new Intent(this,StartupService.Absent.class),nonCreating,0);bindWithoutCreate=bound?"accepted":"rejected";if(bound)unbindService(nonCreating);
            Link link=new Link();check("real-remote-bind-with-platform-scheduling-flags-submitted",bindService(new Intent(this,StartupService.class),link,BIND_AUTO_CREATE|BIND_NOT_FOREGROUND|BIND_WAIVE_PRIORITY));
            new Handler(Looper.getMainLooper()).postDelayed(()->{if(!new File(getFilesDir(),REPORT).exists())publish(new IllegalStateException("SERVICE_CALLBACK_TIMEOUT"));},25000);
        }catch(Throwable error){publish(error);}
    }
    private final class Link implements ServiceConnection {
        @Override public void onServiceConnected(ComponentName name,IBinder binder){try{
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();JSONObject remote;
            try{data.writeInterfaceToken(DESCRIPTOR);if(!binder.transact(1,data,reply,0))throw new IllegalStateException("REMOTE_BINDER_REJECTED");reply.readException();remote=new JSONObject(reply.readString());}finally{data.recycle();reply.recycle();}
            check("callback-after-caller-application-created",created&&Looper.myLooper()==Looper.getMainLooper());
            check("service-connection-logical-name",name.equals(new ComponentName(StartupApplication.this,StartupService.class)));
            check("service-really-runs-in-declared-remote-process",remote.getInt("pid")!=android.os.Process.myPid()&&remote.getInt("uid")==android.os.Process.myUid()&&remote.getString("process").equals(getPackageName()+":startup"));
            check("service-context-and-application-logical",remote.getBoolean("owned")&&remote.getString("package").equals(getPackageName()));
            check("real-service-start-and-bind-callbacks",remote.getInt("starts")==1&&remote.getInt("startId")>0&&remote.getBoolean("intentLogical")&&remote.getBoolean("returnedMarker"));
            check("attach-start-request-under-two-seconds",startNanos<TimeUnit.SECONDS.toNanos(2));
            unbindService(this);check("real-service-stop-accepted",stopService(new Intent(StartupApplication.this,StartupService.class)));publish(null);
        }catch(Throwable error){publish(error);}}
        @Override public void onServiceDisconnected(ComponentName name){publish(new IllegalStateException("REMOTE_DISCONNECTED"));}
    }
    private void check(String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private synchronized void publish(Throwable error){try{File file=new File(getFilesDir(),REPORT);if(file.exists())return;JSONObject result=new JSONObject().put("passed",error==null).put("completed",true).put("checks",checks).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("bindWithoutCreate",bindWithoutCreate).put("startRequestNanos",startNanos);if(error!=null)result.put("error",android.util.Log.getStackTraceString(error));write(file,result.toString());}catch(Exception failure){throw new IllegalStateException("OWNED_STARTUP_REPORT_FAILED",failure);}}
    static void write(File file,String text)throws IOException{android.util.AtomicFile atomic=new android.util.AtomicFile(file);FileOutputStream stream=atomic.startWrite();try{stream.write(text.getBytes(StandardCharsets.UTF_8));atomic.finishWrite(stream);}catch(IOException error){atomic.failWrite(stream);throw error;}}
}
