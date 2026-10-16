package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;

/** More distinct child components than the physical pool, with real Android destruction. */
public final class ActivityChurn {
    private static final Set<String> destroyed=new HashSet<>();
    private static int creates,recreates;
    public static final class Parent extends Activity {
        private final JSONArray children=new JSONArray();private int index;
        private volatile boolean collecting;private Thread collector;private long gcBefore;
        @Override public void onCreate(Bundle state){super.onCreate(state);destroyed.clear();creates=recreates=0;
            gcBefore=Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count"));collecting=true;
            collector=new Thread(()->{while(collecting){byte[][] pressure=new byte[8][];for(int i=0;i<pressure.length;i++){pressure[i]=new byte[128*1024];pressure[i][0]=(byte)i;}System.gc();SystemClock.sleep(10);}},"owned-gc-stress");collector.start();
            new Handler(Looper.getMainLooper()).postDelayed(this::next,100);}
        private void next(){
            if(index==24){try{collecting=false;collector.join(5000);if(collector.isAlive())throw new IllegalStateException("GC_STRESS_THREAD_UNCONFIRMED");setResult(RESULT_OK,new Intent().putExtra("result",new JSONObject().put("children",children).put("creates",creates).put("recreates",recreates).put("destroyed",destroyed.size()).put("gcCollections",Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count"))-gcBefore).put("pid",android.os.Process.myPid()).put("task",getTaskId()).toString()));finish();}catch(Exception error){throw new IllegalStateException(error);}return;}
            String name=ActivityChurn.class.getName()+"$C"+index;
            startActivityForResult(new Intent().setComponent(new ComponentName(getPackageName(),name)).putExtra("index",index),7);
        }
        @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);if(request!=7||code!=RESULT_OK)throw new IllegalStateException("CHURN_CHILD_FAILED");
            try{JSONObject child=new JSONObject(data.getStringExtra("result"));if(!child.getString("component").equals(ActivityChurn.class.getName()+"$C"+index)||child.getInt("pid")!=android.os.Process.myPid()||child.getInt("task")!=getTaskId())throw new IllegalStateException("CHURN_IDENTITY_CHANGED");children.put(child);awaitDestroy(0);}catch(Exception error){throw new IllegalStateException(error);}
        }
        private void awaitDestroy(int attempt){
            if(destroyed.contains(ActivityChurn.class.getName()+"$C"+index)){index++;new Handler(Looper.getMainLooper()).postDelayed(this::next,80);return;}
            if(attempt>300)throw new IllegalStateException("CHURN_DESTROY_TIMEOUT");new Handler(Looper.getMainLooper()).postDelayed(()->awaitDestroy(attempt+1),20);
        }
        @Override protected void onDestroy(){collecting=false;super.onDestroy();}
    }
    public static class Child extends Activity {
        private boolean restored;
        @Override public void onCreate(Bundle state){super.onCreate(state);creates++;restored=state!=null;
            new Handler(Looper.getMainLooper()).postDelayed(()->{
                if(getIntent().getIntExtra("index",-1)==5&&!restored){recreates++;recreate();return;}
                try{setResult(RESULT_OK,new Intent().putExtra("result",new JSONObject().put("component",getComponentName().getClassName()).put("pid",android.os.Process.myPid()).put("task",getTaskId()).put("restored",restored).toString()));finish();}catch(Exception error){throw new IllegalStateException(error);}
            },80);
        }
        @Override protected void onDestroy(){if(isFinishing())destroyed.add(getComponentName().getClassName());super.onDestroy();}
    }
    public static final class C0 extends Child {} public static final class C1 extends Child {}
    public static final class C2 extends Child {} public static final class C3 extends Child {}
    public static final class C4 extends Child {} public static final class C5 extends Child {}
    public static final class C6 extends Child {} public static final class C7 extends Child {}
    public static final class C8 extends Child {} public static final class C9 extends Child {}
    public static final class C10 extends Child {} public static final class C11 extends Child {}
    public static final class C12 extends Child {} public static final class C13 extends Child {}
    public static final class C14 extends Child {} public static final class C15 extends Child {}
    public static final class C16 extends Child {} public static final class C17 extends Child {}
    public static final class C18 extends Child {} public static final class C19 extends Child {}
    public static final class C20 extends Child {} public static final class C21 extends Child {}
    public static final class C22 extends Child {} public static final class C23 extends Child {}
}
