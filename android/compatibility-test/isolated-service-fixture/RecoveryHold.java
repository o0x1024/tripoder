package dev.tripoder.isolatedservicecontract;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import java.io.*;
import java.nio.file.Files;
import org.json.JSONObject;

/** One explicit owned start, held until controller stop or real authority death. */
public final class RecoveryHold extends Activity {
    private boolean bound;private int starts;private TextView text;
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder peer){
            try{
                Parcel request=Parcel.obtain(),reply=Parcel.obtain();Bundle value;
                try(var fd=ParcelFileDescriptor.open(new File(getFilesDir(),"owned-isolated-marker"),ParcelFileDescriptor.MODE_READ_ONLY)){
                    request.writeInterfaceToken(OwnedService.WIRE);request.writeTypedObject(fd,0);
                    if(!peer.transact(1,request,reply,0))throw new SecurityException("OWNED_RECOVERY_BINDER_REQUIRED");reply.readException();value=reply.readBundle(getClassLoader());
                }finally{request.recycle();reply.recycle();}
                if(!new ComponentName(RecoveryHold.this,OwnedService.class).equals(name)||!value.getBoolean("isolated")||!android.os.Process.isIsolatedUid(value.getInt("uid"))||value.getInt("callerUid")!=android.os.Process.myUid()||!value.getBoolean("factoryCreation")||!value.getBoolean("privateFdVerified"))throw new SecurityException("OWNED_RECOVERY_ISOLATED_IDENTITY_REQUIRED");
                report("READY",new JSONObject().put("isolatedPid",value.getInt("pid")).put("isolatedUid",value.getInt("uid")));
            }catch(Exception failure){android.util.Log.e("TripoderIsolatedRecoveryFixture","OWNED_HOLD_FAILED",failure);}
        }
        @Override public void onServiceDisconnected(ComponentName name){try{report("DISCONNECTED",new JSONObject());}catch(Exception failure){throw new IllegalStateException(failure);}}
        @Override public void onNullBinding(ComponentName name){throw new IllegalStateException("OWNED_RECOVERY_NULL_BINDING");}
    };
    private void report(String state,JSONObject value)throws Exception{
        value.put("state",state).put("starts",starts).put("parentPid",android.os.Process.myPid()).put("parentUid",android.os.Process.myUid());String encoded=value.toString();
        if(state.equals("READY"))try(var stream=new FileOutputStream(new File(getFilesDir(),"owned-recovery-ready.json"))){stream.write(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));stream.getFD().sync();}
        android.util.Log.i("TripoderIsolatedRecoveryFixture",encoded);text.setText(encoded);
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);text=new TextView(this);setContentView(text);
        try{
            File marker=new File(getFilesDir(),"owned-isolated-marker");Files.writeString(marker.toPath(),"OWNED_PRIVATE_BOUNDARY\n");android.system.Os.chmod(marker.getPath(),0600);
            File counter=new File(getFilesDir(),"owned-recovery-start-count");starts=counter.isFile()?Integer.parseInt(Files.readString(counter.toPath()))+1:1;
            try(var stream=new FileOutputStream(counter)){stream.write(Integer.toString(starts).getBytes(java.nio.charset.StandardCharsets.UTF_8));stream.getFD().sync();}
            bound=bindIsolatedService(new Intent(this,OwnedService.class),BIND_AUTO_CREATE,"RECOVERY",getMainExecutor(),connection);
            if(!bound)throw new IllegalStateException("OWNED_RECOVERY_BIND_REJECTED");
        }catch(Exception failure){throw new IllegalStateException("OWNED_RECOVERY_HOLD_FAILED",failure);}
    }
    @Override public void onDestroy(){if(bound){bound=false;unbindService(connection);}super.onDestroy();}
}
