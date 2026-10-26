package dev.tripoder.closurefixture;

import android.app.Activity;
import android.net.Uri;
import java.util.concurrent.*;

/** Real concurrent Binder requests to a worker not started by an Activity or Job. */
final class ColdProviderChecks {
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        ExecutorService callers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        Callable<int[]> query=()->{
            if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("COLD_PROVIDER_START_BARRIER");
            try(var cursor=activity.getContentResolver().query(Uri.parse("content://dev.tripoder.closurefixture.cold-owned/owned"),null,null,null,null)){
                if(cursor==null||!cursor.moveToFirst()||!activity.getPackageName().equals(cursor.getString(5)))throw new AssertionError("COLD_PROVIDER_REAL_CALLER");
                return new int[]{cursor.getInt(0),cursor.getInt(1),cursor.getInt(2),cursor.getInt(3),cursor.getInt(4)};
            }
        };
        try{
            Future<int[]> first=callers.submit(query),second=callers.submit(query);start.countDown();
            int[] a=first.get(40,TimeUnit.SECONDS),b=second.get(2,TimeUnit.SECONDS);
            check.test("cold-provider-concurrent-requests-return-one-distinct-real-process",a[0]>0&&a[0]!=android.os.Process.myPid()&&a[0]==b[0]);
            check.test("cold-provider-constructor-and-attach-run-once",a[1]==1&&a[2]==1&&b[1]==1&&b[2]==1);
            check.test("cold-provider-onCreate-runs-once-on-real-main-thread",a[3]==1&&a[4]==1&&b[3]==1&&b[4]==1);
        }finally{callers.shutdownNow();}
    }
    private ColdProviderChecks(){}
}
