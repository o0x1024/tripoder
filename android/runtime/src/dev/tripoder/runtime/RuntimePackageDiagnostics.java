package dev.tripoder.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import dev.tripoder.selfhook.PackageFrameworkAccess;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded observational relations; no endpoint publication, unwrapping or repair. */
final class RuntimePackageDiagnostics {
    private final RuntimeEngine engine;
    private final AtomicInteger observations=new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean failureObserved=new java.util.concurrent.atomic.AtomicBoolean();
    RuntimePackageDiagnostics(RuntimeEngine engine){this.engine=engine;}
    void query(java.lang.reflect.Method method,Object[] arguments,Object result){
        if(!engine.traceStartup||observations.getAndIncrement()>=128)return;
        String name=method.getName();
        if(!name.equals("getPackageInfo")&&!name.equals("getApplicationInfo"))return;
        Object selector=arguments==null||arguments.length==0?null:arguments[0];
        String role=engine.packageName.equals(selector)?"GUEST":engine.host.getPackageName().equals(selector)?"HOST":"OTHER";
        String flags=arguments!=null&&arguments.length>1?String.valueOf(arguments[1]):"";
        int signatures=result instanceof android.content.pm.PackageInfo info&&info.signatures!=null?info.signatures.length:0;
        android.util.Log.i("TripoderPackageQuery","method="+name+";role="+role+";flags="+flags+";present="+(result!=null)+";signatures="+signatures);
    }
    void record(String phase,Context context,PackageManager manager,Object root,Object argument,IBinder binder,boolean registered,boolean system){
        if(!engine.traceStartup)return;
        boolean failure=phase.equals("CACHED_ENDPOINT_CHANGED");
        if(failure?!failureObserved.compareAndSet(false,true):observations.getAndIncrement()>=64)return;
        try{
            Map<String,Object> report=PackageFrameworkAccess.endpointRelations(context,manager,root,argument,binder);
            report.put("phase",phase);report.put("registered",registered);report.put("frameworkSystemContext",system);
            report.put("elapsedNanos",android.os.SystemClock.elapsedRealtimeNanos());
            report.put("threadId",Thread.currentThread().getId());report.put("mainThread",Looper.myLooper()==Looper.getMainLooper());
            android.util.Log.i("TripoderPackageRelations",new org.json.JSONObject(report).toString());
            if(failure){
                StackTraceElement[] frames=Thread.currentThread().getStackTrace();
                for(int i=0;i<Math.min(20,frames.length);i+=4)android.util.Log.i("TripoderPackageRelations","failureFrames="+java.util.Arrays.toString(java.util.Arrays.copyOfRange(frames,i,Math.min(i+4,Math.min(20,frames.length)))));
            }
        }catch(RuntimeException error){android.util.Log.w("TripoderPackageRelations","OBSERVATION_FAILED:"+error.getClass().getName());}
    }
}
