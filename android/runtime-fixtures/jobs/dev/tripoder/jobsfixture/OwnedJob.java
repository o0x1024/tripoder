package dev.tripoder.jobsfixture;

import android.app.job.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import org.json.*;

/** Only actual Android callbacks create these receipts. */
public class OwnedJob extends JobService {
    @Override public boolean onStartJob(JobParameters parameters){try{
        int id=parameters.getJobId();File count=new File(getFilesDir(),"job-count-"+id);int starts=count.exists()?Integer.parseInt(Files.readString(count.toPath()))+1:1;write(count,Integer.toString(starts));
        boolean denied=false;try{stopSelf();}catch(UnsupportedOperationException expected){denied=expected.getMessage().contains("JOB_SERVICE_TOKEN_CONTROL_DENIED");}
        if(!getPackageName().equals("dev.tripoder.jobsfixture")||!getApplicationContext().getPackageName().equals(getPackageName())||parameters.getExtras().containsKey("dev.tripoder.job.version")||!"owned-payload".equals(parameters.getExtras().getString("payload"))||!denied)throw new AssertionError("JOB_LOGICAL_IDENTITY");
        report(parameters,"start",starts);String mode=parameters.getExtras().getString("mode");
        if("async".equals(mode)){new Handler(getMainLooper()).postDelayed(()->{try{jobFinished(parameters,false);report(parameters,"finish-requested",starts);boolean rejected=false;try{jobFinished(parameters,false);}catch(SecurityException expected){rejected=true;}if(!rejected)throw new AssertionError("DUPLICATE_FINISH_ACCEPTED");}catch(Exception error){throw new IllegalStateException(error);}},300);return true;}
        if("stop".equals(mode))return true;
        if("recover".equals(mode)&&starts==1){new Handler(getMainLooper()).postDelayed(()->{try{jobFinished(parameters,true);report(parameters,"retry-requested",starts);}catch(Exception error){throw new IllegalStateException(error);}},300);return true;}
        return false;
    }catch(Exception error){throw new IllegalStateException(error);}}
    @Override public boolean onStopJob(JobParameters parameters){try{report(parameters,"stop",0);return false;}catch(Exception error){throw new IllegalStateException(error);}}
    private void report(JobParameters p,String event,int count)throws Exception{
        JSONObject report=new JSONObject().put("id",p.getJobId()).put("event",event).put("count",count).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("process",android.app.Application.getProcessName()).put("package",getPackageName()).put("stopReason",p.getStopReason());
        write(new File(getFilesDir(),"job-"+p.getJobId()+".json"),report.toString());
    }
    static void write(File file,String value)throws Exception{File temp=new File(file+".tmp");try(var out=new FileOutputStream(temp)){out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}if(!temp.renameTo(file))throw new IOException("REPORT_RENAME_FAILED");}
    public static final class Peer extends OwnedJob {}
    public static final class Unprotected extends OwnedJob {}
    public static final class Disabled extends OwnedJob {}
    public static final class Isolated extends OwnedJob {}
}
