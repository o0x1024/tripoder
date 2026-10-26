package dev.tripoder.componentrestartfixture;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.TextView;
import java.nio.file.Files;
import java.util.Arrays;

/** Four fresh launches: change/verify in THIN, then change/verify in THICK. */
public final class Main extends Activity {
    private TextView text;
    private final byte[] durableBytes=new byte[]{7,0,3,19,38};
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName component,IBinder binder){
            try{
                Messenger replies=new Messenger(new Handler(Looper.getMainLooper()){@Override public void handleMessage(Message message){inspect(message.arg1);}});
                Message request=Message.obtain();request.replyTo=replies;new Messenger(binder).send(request);
            }catch(Throwable failure){fail(failure);}
        }
        @Override public void onServiceDisconnected(ComponentName component){}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);text=new TextView(this);text.setText("WAITING FOR REAL REMOTE PROCESS");setContentView(text);
        if(!bindService(new Intent(this,Remote.class),connection,BIND_AUTO_CREATE))fail(new AssertionError("REAL_REMOTE_BIND_REJECTED"));
    }
    private void inspect(int remotePid){
        try{
            int pid=android.os.Process.myPid();require(remotePid>0&&remotePid!=pid,"REAL_DISTINCT_REMOTE_PROCESS");
            var record=getSharedPreferences("component-raw-restart-v3-evidence",MODE_PRIVATE);int stage=record.getInt("stage",0);
            // Declared enabled=true: DEFAULT and ENABLED have equal effective
            // state, but changing the raw state must still restart the package.
            var component=new ComponentName(this,Remote.class);PackageManager pm=getPackageManager();var bytes=new java.io.File(getFilesDir(),"retained-owned-bytes").toPath();
            if(stage==0||stage==2){
                if(stage==0)Files.write(bytes,durableBytes);else require(Arrays.equals(durableBytes,Files.readAllBytes(bytes)),"SECOND_MODE_RETAINS_DATA");
                int desired=stage==0?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
                require(pm.getComponentEnabledSetting(component)!=desired,"RAW_SETTING_REALLY_CHANGES");
                require(record.edit().putInt("stage",stage+1).putInt("oldMainPid",pid).putInt("oldRemotePid",remotePid).putInt("desired",desired).commit(),"RESTART_MARKER_COMMITTED");
                text.setText("REQUESTING REAL COMPONENT RESTART");
                android.util.Log.i("TripoderComponentRestart","REQUEST stage="+stage+" mainPid="+pid+" remotePid="+remotePid+" state="+desired);
                // Main-thread synchronous setter reproduces the self-death Binder
                // cycle if the authority mistakenly waits for this caller to die.
                pm.setComponentEnabledSetting(component,desired,0);
                getSystemService(ActivityManager.class).getRunningAppProcesses();
                require(pm.getComponentEnabledSetting(component)==desired,"CLOSING_COMPONENT_QUERY_RETURNS_REAL_COMMITTED_STATE");
                android.util.Log.i("TripoderComponentRestart","CLOSING_READS_RETURNED stage="+stage);
                android.util.Log.i("TripoderComponentRestart","SETTING_RPC_RETURNED stage="+stage);
            }else if(stage==1||stage==3){
                require(pm.getComponentEnabledSetting(component)==record.getInt("desired",-1),"COMMITTED_SETTING_SURVIVES_PROCESS_DEATH");
                require(Arrays.equals(durableBytes,Files.readAllBytes(bytes)),"DATA_BYTES_SURVIVE_RESTART");
                require(pid!=record.getInt("oldMainPid",0)&&remotePid!=record.getInt("oldRemotePid",0),"BOTH_REAL_PROCESS_IDENTITIES_CHANGED");
                for(var process:getSystemService(ActivityManager.class).getRunningAppProcesses())require(process.pid!=record.getInt("oldMainPid",0)&&process.pid!=record.getInt("oldRemotePid",0),"OLD_TARGETS_ABSENT");
                int desired=record.getInt("desired",-1);pm.setComponentEnabledSetting(component,desired,0);
                require(pm.getComponentEnabledSetting(component)==desired,"UNCHANGED_FLAGS_ZERO_IS_REAL_NOOP");
                require(record.edit().putInt("stage",stage+1).commit(),"VERIFICATION_MARKER_COMMITTED");
                text.setText("PASS: BOTH PROCESSES RESTARTED; DATA RETAINED; UNCHANGED SETTING DOES NOT KILL");
                android.util.Log.i("TripoderComponentRestart","PASS stage="+stage+" mainPid="+pid+" remotePid="+remotePid);
            }else{
                require(stage==4&&Arrays.equals(durableBytes,Files.readAllBytes(bytes)),"RETAINED_COMPLETED_EVIDENCE");
                text.setText("PASS: RETAINED COMPLETED COMPONENT RESTART EVIDENCE");
            }
        }catch(Throwable failure){fail(failure);}
    }
    private static void require(boolean value,String name){if(!value)throw new AssertionError(name);}
    private void fail(Throwable failure){text.setText("FAIL: "+failure);android.util.Log.e("TripoderComponentRestart","FAIL",failure);}
}
