package dev.tripoder.p0.b;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import dev.tripoder.p0.shared.*;
import java.io.*;

public final class BActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        setContentView(getResources().getIdentifier("screen","layout",getPackageName()));
        // Always written on real system launches; virtual Context points to space data instead.
        try(var out=new FileOutputStream(new File(getExternalFilesDir(null),"decoy-launch.txt"))){out.write("launched".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        var report=Observation.read(this).put("challenge",getIntent().getStringExtra("challenge"));
        setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));
        if("return-without-finish".equals(getIntent().getStringExtra("scenario"))){new android.os.Handler().postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),700);return;}
        finish();
        if("finish-then-die".equals(getIntent().getStringExtra("scenario")))new android.os.Handler().postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),100);
    }catch(Exception e){throw new IllegalStateException("FIXTURE_B_FAILED",e);}}
}
