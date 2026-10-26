package dev.tripoder.perffixture;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Deterministic workloads, each timed after a warm-up so JIT state is comparable between launch modes. */
public final class Main extends Activity {
    private static final String TAG="TripoderPerfFixture";
    private static volatile long sink;
    interface Work {long run(int n)throws Exception;}
    private final JSONObject out=new JSONObject();
    private void bench(String name,int n,Work work)throws Exception{
        work.run(Math.max(1,n/4));                       // warm-up: compile what will be hot
        long best=Long.MAX_VALUE;
        for(int round=0;round<5;round++){long t=System.nanoTime();sink+=work.run(n);best=Math.min(best,System.nanoTime()-t);}
        out.put(name,Math.round(best/(double)n*10)/10.0);   // best-of-5 nanoseconds per operation
    }
    private void run(){
        try{
            if(getIntent().getBooleanExtra("probe",false)){System.loadLibrary("perfnative");Log.i(TAG,"probe "+NativeProbe.run(getFilesDir().getPath()+"/perf-small.bin"));return;}
            long launch=SystemClock.elapsedRealtime();
            bench("cpu_int_loop",20_000_000,n->{long a=1;for(int i=0;i<n;i++){a=a*31+i;a^=a>>>7;}return a;});
            bench("cpu_double_math",4_000_000,n->{double a=0;for(int i=1;i<=n;i++)a+=Math.sqrt(i)*Math.sin(i);return (long)a;});
            bench("alloc_small_objects",3_000_000,n->{long a=0;for(int i=0;i<n;i++){int[] x=new int[8];x[i&7]=i;a+=x.length+x[i&7];}return a;});
            bench("collections_hashmap",300_000,n->{HashMap<String,Integer> m=new HashMap<>();for(int i=0;i<n;i++)m.put("k"+(i&4095),i);long a=0;for(int i=0;i<n;i++)a+=m.get("k"+(i&4095));return a;});
            bench("string_builder",400_000,n->{long a=0;for(int i=0;i<n;i++){StringBuilder b=new StringBuilder();b.append(i).append(':').append("abc");a+=b.length();}return a;});
            bench("sort_array",2000,n->{long a=0;Random r=new Random(7);for(int i=0;i<n;i++){int[] x=new int[256];for(int j=0;j<256;j++)x[j]=r.nextInt();Arrays.sort(x);a+=x[0];}return a;});
            Context context=this;String pkg=getPackageName();PackageManager pm=getPackageManager();
            bench("framework_getPackageName",2_000_000,n->{long a=0;for(int i=0;i<n;i++)a+=context.getPackageName().length();return a;});
            bench("framework_getApplicationInfo",200_000,n->{long a=0;for(int i=0;i<n;i++)a+=context.getApplicationInfo().flags;return a;});
            bench("pm_getPackageInfo",20_000,n->{long a=0;for(int i=0;i<n;i++)a+=pm.getPackageInfo(pkg,0).versionCode;return a;});
            bench("pm_resolveActivity",20_000,n->{long a=0;android.content.Intent it=new android.content.Intent(android.content.Intent.ACTION_MAIN).setPackage(pkg);for(int i=0;i<n;i++)a+=pm.resolveActivity(it,0)!=null?1:0;return a;});
            bench("getSystemService_lookup",500_000,n->{long a=0;for(int i=0;i<n;i++)a+=getSystemService(Context.CONNECTIVITY_SERVICE)!=null?1:0;return a;});
            ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            bench("connectivity_getActiveNetwork",20_000,n->{long a=0;for(int i=0;i<n;i++)a+=cm.getActiveNetwork()!=null?1:0;return a;});
            bench("system_clock",3_000_000,n->{long a=0;for(int i=0;i<n;i++)a+=SystemClock.elapsedRealtimeNanos()&1;return a;});
            File dir=getFilesDir();File small=new File(dir,"perf-small.bin");try(FileOutputStream f=new FileOutputStream(small)){f.write(new byte[4096]);}
            bench("file_exists_stat",100_000,n->{long a=0;for(int i=0;i<n;i++)a+=small.exists()?1:0;return a;});
            bench("file_read_4k",20_000,n->{long a=0;byte[] b=new byte[4096];for(int i=0;i<n;i++){try(FileInputStream f=new FileInputStream(small)){a+=f.read(b);}}return a;});
            bench("file_write_4k",5_000,n->{long a=0;byte[] b=new byte[4096];for(int i=0;i<n;i++){try(FileOutputStream f=new FileOutputStream(small)){f.write(b);}a++;}return a;});
            File big=new File(dir,"perf-big.bin");byte[] chunk=new byte[1<<20];
            bench("file_write_1m_stream",64,n->{long a=0;try(FileOutputStream f=new FileOutputStream(big)){for(int i=0;i<n;i++){f.write(chunk);a+=chunk.length;}}return a;});
            bench("file_read_1m_stream",64,n->{long a=0;try(FileInputStream f=new FileInputStream(big)){int r;while((r=f.read(chunk))>0)a+=r;}return a;});
            bench("prefs_commit_read",300,n->{long a=0;android.content.SharedPreferences p=getSharedPreferences("perf",MODE_PRIVATE);for(int i=0;i<n;i++){p.edit().putInt("k",i).commit();a+=p.getInt("k",0);}return a;});
            bench("thread_handoff",20_000,n->{long a=0;java.util.concurrent.SynchronousQueue<Integer> q=new java.util.concurrent.SynchronousQueue<>();Thread t=new Thread(()->{try{for(int i=0;i<n;i++)q.put(i);}catch(InterruptedException e){}});t.start();for(int i=0;i<n;i++)a+=q.take();t.join();return a;});
            // Convergence: the same short workload over many rounds shows whether slowness is warm-up or steady state.
            {Random r=new Random(7);long[] times=new long[60];
             for(int round=0;round<times.length;round++){long t0=System.nanoTime();long a=0;for(int i=0;i<2000;i++){int[] x=new int[256];for(int j=0;j<256;j++)x[j]=r.nextInt();Arrays.sort(x);a+=x[0];}sink+=a;times[round]=(System.nanoTime()-t0)/2000;}
             JSONObject c=new JSONObject();for(int round:new int[]{0,1,2,4,8,16,32,59})c.put("r"+round,times[round]);out.put("sort_convergence_ns_per_op",c);}
            try{System.loadLibrary("perfnative");out.put("probe_primitives",NativeProbe.run(small.getPath()));}catch(Throwable error){out.put("probe_primitives","unavailable:"+error);}
            out.put("total_wall_ms",SystemClock.elapsedRealtime()-launch);
            out.put("mode_hint",getApplicationInfo().sourceDir.startsWith("/data/app/")?"native-or-thin":"other");
            out.put("passed",true).put("checks",1);
        }catch(Throwable error){Log.e(TAG,"perf failure",error);try{out.put("passed",false).put("checks",1).put("error",String.valueOf(error));}catch(Exception ignored){}}
        Log.i(TAG,out.toString());
    }
    @Override protected void onCreate(Bundle state){super.onCreate(state);new Thread(this::run,"perf-fixture").start();}
}
