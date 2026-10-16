package dev.tripoder.servicestartup;

import android.app.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;

/** Native baseline result transport only; runtime acceptance starts just the Application. */
public final class StartupActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);String run=getIntent().getStringExtra("runId");if(run==null||!run.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_ID_INVALID");
        new Thread(()->{try{File source=new File(getFilesDir(),StartupApplication.REPORT);long deadline=SystemClock.elapsedRealtime()+30000;while(!source.exists()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);StartupApplication.write(new File(getExternalFilesDir(null),"result-"+run+".json"),Files.readString(source.toPath()));}catch(Exception error){throw new IllegalStateException(error);}},"owned-startup-report").start();
    }
}
