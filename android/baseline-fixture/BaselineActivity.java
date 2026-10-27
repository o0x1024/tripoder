package dev.tripoder.baseline;

import android.app.Activity;
import android.os.*;
import android.view.*;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Standalone experiment: no Tripoder runtime/core, JNI, interception or scripts. */
public final class BaselineActivity extends Activity {
    private final long started=SystemClock.elapsedRealtimeNanos();
    private final List<Long> durations=new ArrayList<>();
    private long firstDraw,jank,deadlineSamples;
    private String run,payloadHash;
    private boolean hosted,done;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        run=getIntent().getStringExtra("run");if(run==null||!run.matches("[a-z0-9-]{1,80}"))throw new IllegalArgumentException("RUN_REQUIRED");
        hosted=getPackageName().endsWith(".host");
        WindowManager.LayoutParams display=getWindow().getAttributes();
        display.preferredRefreshRate=90;
        for(Display.Mode mode:getDisplay().getSupportedModes())if(Math.abs(mode.getRefreshRate()-90)<.1f){display.preferredDisplayModeId=mode.getModeId();break;}
        getWindow().setAttributes(display);
        try{
            ClassLoader loader=getClassLoader();
            File artifact=new File(getApplicationInfo().sourceDir);
            if(hosted){
                artifact=new File(getCodeCacheDir(),"fixture.apk");
                if(artifact.exists()&&!artifact.delete())throw new IOException("FIXTURE_REPLACE_FAILED");
                try(InputStream in=getAssets().open("fixture.apk");FileOutputStream out=new FileOutputStream(artifact)){
                    if(!artifact.setReadOnly())throw new IOException("FIXTURE_READ_ONLY_FAILED");in.transferTo(out);out.getFD().sync();
                }
                loader=new DexClassLoader(artifact.toString(),getCodeCacheDir().toString(),null,getClassLoader());
            }
            payloadHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact.toPath())));
            View scene=(View)loader.loadClass("dev.tripoder.baseline.fixture.Scene").getConstructor(android.content.Context.class).newInstance(this);
            getWindow().addOnFrameMetricsAvailableListener((window,metrics,dropped)->{
                if(done)return;
                long total=metrics.getMetric(FrameMetrics.TOTAL_DURATION),deadline=metrics.getMetric(FrameMetrics.DEADLINE);
                durations.add(total);if(deadline>0){deadlineSamples++;if(total>deadline)jank++;}
                if(durations.size()>=180)writeReceipt(null);
            },new Handler(Looper.getMainLooper()));
            scene.getViewTreeObserver().addOnDrawListener(()->{if(firstDraw==0)firstDraw=SystemClock.elapsedRealtimeNanos()-started;});
            setContentView(scene);
            new Handler().postDelayed(()->{if(!done)writeReceipt("FRAME_SAMPLE_TIMEOUT");},15000);
        }catch(Exception failure){writeReceipt(failure.toString());}
    }
    private void writeReceipt(String failure){
        if(done)return;done=true;
        try{
            JSONObject result=new JSONObject().put("run",run).put("hosted",hosted).put("pid",android.os.Process.myPid())
                .put("payloadSha256",payloadHash).put("firstDrawFromActivityNs",firstDraw).put("frames",durations.size())
                .put("deadlineSamples",deadlineSamples).put("deadlineJank",jank).put("durationsNs",new JSONArray(durations))
                .put("runtimeCorePackaged",false).put("fixedBindings",0).put("dynamicBindings",0).put("toggleBatchNativeCalls",0)
                .put("addedSignalClaims",0).put("addedSeccompFilters",0).put("failure",failure==null?JSONObject.NULL:failure)
                .put("deviceBuild",Build.FINGERPRINT).put("refreshRate",getDisplay().getRefreshRate());
            String status=Files.readString(java.nio.file.Path.of("/proc/self/status"));
            for(String line:status.split("\n"))if(line.startsWith("Seccomp:")||line.startsWith("Seccomp_filters:")||line.startsWith("SigCgt:"))result.put(line.substring(0,line.indexOf(':')),line.substring(line.indexOf(':')+1).trim());
            try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),run+".json"))){out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        }catch(Exception error){android.util.Log.e("TripoderLevel0","receipt failed",error);}
    }
}
