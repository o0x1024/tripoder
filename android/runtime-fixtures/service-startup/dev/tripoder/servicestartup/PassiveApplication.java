package dev.tripoder.servicestartup;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import org.json.*;

/** A real non-creating binding waits for a separate start request, without a guest process. */
public final class PassiveApplication extends Application {
    static final String REPORT="passive-contract.json",BOUND="passive-bound.json",GATE="passive-start-gate",CREATED="passive-created",DESTROYED="passive-destroyed";
    static final String DESCRIPTOR="dev.tripoder.owned.passive";
    private final Handler main=new Handler(Looper.getMainLooper());private final JSONArray checks=new JSONArray();
    private boolean started;private int callbacks;private long began;private Link link;
    @Override public void onCreate(){super.onCreate();if(getProcessName().endsWith(":passive"))return;
        try{
            for(String name:new String[]{REPORT,BOUND,GATE,CREATED,DESTROYED})Files.deleteIfExists(new File(getFilesDir(),name).toPath());
            began=SystemClock.elapsedRealtime();link=new Link();
            check("non-creating-binding-accepted",bindService(new Intent(this,PassiveService.class),link,BIND_WAIVE_PRIORITY));
            Link canceled=new Link();check("second-passive-binding-accepted",bindService(new Intent(this,PassiveService.NeverCreated.class),canceled,0));unbindService(canceled);
            StartupApplication.write(new File(getFilesDir(),BOUND),new JSONObject().put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("at",began).toString());
            awaitGate();main.postDelayed(()->{if(!new File(getFilesDir(),REPORT).exists())publish(new IllegalStateException("PASSIVE_BIND_TIMEOUT"));},30000);
        }catch(Throwable error){publish(error);}
    }
    private void awaitGate(){try{
        if(new File(getFilesDir(),REPORT).exists())return;
        if(!new File(getFilesDir(),GATE).exists()){main.postDelayed(this::awaitGate,50);return;}
        check("no-service-created-before-explicit-start",!new File(getFilesDir(),CREATED).exists()&&callbacks==0&&!started);
        check("non-creating-binding-held-before-start",SystemClock.elapsedRealtime()-began>=1000);
        started=true;check("later-start-returns-logical-component",new ComponentName(this,PassiveService.class).equals(startService(new Intent(this,PassiveService.class))));
    }catch(Throwable error){publish(error);}}
    /** Called only by the native baseline Activity; the runtime probe has its own authority gate. */
    void approveNative(){main.postDelayed(()->{try{
        boolean remote=false;for(var process:((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getRunningAppProcesses())if(process.processName.equals(getPackageName()+":passive"))remote=true;
        check("native-non-creating-bind-has-no-target-process",!remote);
        StartupApplication.write(new File(getFilesDir(),GATE),"native-observed-no-process");
    }catch(Throwable error){publish(error);}},1200);}
    private final class Link implements ServiceConnection {
        @Override public void onServiceConnected(ComponentName name,IBinder binder){try{
            callbacks++;check("pending-binding-connects-only-after-later-start",started&&callbacks==1&&name.equals(new ComponentName(PassiveApplication.this,PassiveService.class)));
            observe(binder,SystemClock.elapsedRealtime()+10000);
        }catch(Throwable error){publish(error);}}
        @Override public void onServiceDisconnected(ComponentName name){publish(new IllegalStateException("PASSIVE_DISCONNECTED"));}
    }
    private void observe(IBinder binder,long deadline){try{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();JSONObject remote;
        try{data.writeInterfaceToken(DESCRIPTOR);if(!binder.transact(1,data,reply,0))throw new IllegalStateException("PASSIVE_BINDER_REJECTED");reply.readException();remote=new JSONObject(reply.readString());}finally{data.recycle();reply.recycle();}
        if(remote.getInt("starts")==0&&SystemClock.elapsedRealtime()<deadline){main.postDelayed(()->observe(binder,deadline),30);return;}
        check("pending-binding-receives-real-remote-binder",remote.getInt("pid")!=android.os.Process.myPid()&&remote.getInt("uid")==android.os.Process.myUid()&&remote.getString("process").equals(getPackageName()+":passive"));
        check("passive-promoted-service-keeps-logical-owner",remote.getBoolean("owned")&&remote.getBoolean("intentLogical")&&remote.getString("package").equals(getPackageName()));
        check("passive-promoted-service-starts-exactly-once",remote.getInt("starts")==1);
        unbindService(link);check("promoted-service-real-stop-accepted",stopService(new Intent(this,PassiveService.class)));
        awaitDestroyed(SystemClock.elapsedRealtime()+5000);
    }catch(Throwable error){publish(error);}}
    private void awaitDestroyed(long deadline){try{
        if(!new File(getFilesDir(),DESTROYED).exists()&&SystemClock.elapsedRealtime()<deadline){main.postDelayed(()->awaitDestroyed(deadline),30);return;}
        check("promoted-service-real-onDestroy-observed",new File(getFilesDir(),DESTROYED).exists());publish(null);
    }catch(Throwable error){publish(error);}}
    private void check(String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private synchronized void publish(Throwable error){try{File output=new File(getFilesDir(),REPORT);if(output.exists())return;JSONObject result=new JSONObject().put("passed",error==null).put("completed",true).put("checks",checks).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid());if(error!=null)result.put("error",android.util.Log.getStackTraceString(error));StartupApplication.write(output,result.toString());}catch(Exception failure){throw new IllegalStateException("PASSIVE_REPORT_FAILED",failure);}}
}
