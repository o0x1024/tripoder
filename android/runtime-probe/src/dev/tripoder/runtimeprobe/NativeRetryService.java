package dev.tripoder.runtimeprobe;

import android.app.job.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import org.json.JSONObject;

/** Native Android control: no RuntimeEngine, authority, JobLedger or Hook library. */
public final class NativeRetryService extends JobService {
    @Override public boolean onStartJob(JobParameters p){try{
        String run=p.getExtras().getString("run");if(run==null||!run.matches("jobs-native-[a-f0-9]{16}"))throw new SecurityException("NATIVE_RETRY_RUN_INVALID");
        File file=new File(getFilesDir(),run+".json");int count=file.exists()?new JSONObject(Files.readString(file.toPath())).getInt("count")+1:1;
        write(file,new JSONObject().put("run",run).put("count",count).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("event","start"));
        if(count==1){new Handler(getMainLooper()).postDelayed(()->{jobFinished(p,true);new Handler(getMainLooper()).post(()->{try{write(file,new JSONObject().put("run",run).put("count",count).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("event","finish-enqueued-and-main-drained"));}catch(Exception error){throw new IllegalStateException(error);}});},300);return true;}
        return false;
    }catch(Exception error){throw new IllegalStateException(error);}}
    @Override public boolean onStopJob(JobParameters p){return true;}
    static void write(File file,JSONObject value)throws Exception{File stage=new File(file+".pending");try(var out=new FileOutputStream(stage)){out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}if(!stage.renameTo(file))throw new IOException("NATIVE_RETRY_RENAME_FAILED");}
}
