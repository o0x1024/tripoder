package dev.tripoder.p0.shared;
import android.app.Application;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Ordinary Android application: no Tripoder library, process name or runtime call. */
public final class ProbeApplication extends Application {
    public int starts;
    public String nativeResult;
    @Override public void onCreate(){super.onCreate();try{
        File counter=new File(getFilesDir(),"application-count.txt");
        starts=counter.isFile()?Integer.parseInt(new String(java.nio.file.Files.readAllBytes(counter.toPath()),StandardCharsets.UTF_8))+1:1;
        try(FileOutputStream out=new FileOutputStream(counter)){out.write(Integer.toString(starts).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        nativeResult=NativeProbe.writeRead(new File(getFilesDir(),"native.txt").toString(),getPackageName()+":"+starts);
    }catch(Exception e){throw new IllegalStateException("FIXTURE_APPLICATION_FAILED",e);}}
}
