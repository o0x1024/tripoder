package dev.tripoder.closurefixture;

import android.app.Activity;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class ContextTarget extends Activity {
    static final AtomicInteger created=new AtomicInteger();
    static final CountDownLatch resumed=new CountDownLatch(1),destroyed=new CountDownLatch(1);
    static volatile boolean originValid,callerValid;static volatile Throwable failure;
    @Override public void onCreate(Bundle state){super.onCreate(state);created.incrementAndGet();setContentView(new android.widget.TextView(this));}
    @Override public void onResume(){super.onResume();
        try{
            originValid=getCallingActivity()==null&&getCallingPackage()==null&&getReferrer()!=null&&getReferrer().toString().equals("android-app://"+getPackageName());
            var caller=getCaller();callerValid=caller!=null&&caller.getPackage().equals(getPackageName())&&caller.getUid()==android.os.Process.myUid();
        }catch(Throwable error){failure=error;}
        finally{resumed.countDown();finish();}
    }
    @Override public void onDestroy(){try{super.onDestroy();}finally{destroyed.countDown();}}
}
