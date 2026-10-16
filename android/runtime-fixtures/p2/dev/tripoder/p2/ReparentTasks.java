package dev.tripoder.p2;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import org.json.*;

/** RESET_TASK_IF_NEEDED moves a live alias instance; no synthetic lifecycle calls. */
public final class ReparentTasks {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final List<Activity> cleanup=new ArrayList<>();
    private static int destination,source,creates,firstDestroyed,rootCreates;
    private static String firstInstance,secondInstance;
    private static JSONObject result;
    public static final class Coordinator extends Activity {
        private int attempts;
        @Override public void onCreate(Bundle state){super.onCreate(state);cleanup.clear();creates=firstDestroyed=rootCreates=0;result=null;
            startActivity(new Intent(this,Destination.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));main.postDelayed(this::collect,50);}
        private void collect(){if(result==null){if(++attempts>600)throw new IllegalStateException("REPARENT_TIMEOUT");main.postDelayed(this::collect,50);return;}
            for(Activity a:cleanup)if(!a.isFinishing()&&!a.isDestroyed())a.finish();
            setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));finish();}
    }
    public static final class Destination extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);cleanup.add(this);destination=getTaskId();rootCreates++;
            main.postDelayed(()->startActivity(alias("one")),100);}
    }
    public static final class Source extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);cleanup.add(this);source=getTaskId();main.postDelayed(()->startActivity(alias("two")),100);}
    }
    private static Intent alias(String entry){return new Intent().setComponent(new ComponentName("dev.tripoder.p2","dev.tripoder.p2.ReparentAlias"+(entry.equals("one")?"One":"Two"))).putExtra("entry",entry);}
    public static final class Moving extends Activity {
        private final String instance=UUID.randomUUID().toString();private String entry;private int before,attempts;
        @Override public void onCreate(Bundle state){super.onCreate(state);cleanup.add(this);creates++;entry=getIntent().getStringExtra("entry");before=getTaskId();
            if(entry.equals("one")){firstInstance=instance;if(before!=destination)throw new IllegalStateException("REPARENT_FIRST_TASK");main.postDelayed(()->startActivity(new Intent(this,Source.class)),100);}
            else{secondInstance=instance;if(before!=source)throw new IllegalStateException("REPARENT_SOURCE_TASK");main.postDelayed(()->{startActivity(new Intent(this,Destination.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));main.postDelayed(this::observe,50);},100);}
        }
        private void observe(){if(getTaskId()!=destination){if(++attempts>200)throw new IllegalStateException("REPARENT_NOT_MOVED");main.postDelayed(this::observe,50);return;}
            if(!instance.equals(secondInstance)||firstDestroyed!=0||creates!=2||rootCreates!=1)throw new IllegalStateException("REPARENT_ALIAS_INSTANCES_COLLAPSED");
            startActivityForResult(new Intent(this,AfterMove.class),19);}
        @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{
            if(request!=19||code!=RESULT_OK||data==null)throw new IllegalStateException("REPARENT_RESULT_MISSING");
            result=new JSONObject().put("beforeTask",before).put("afterTask",getTaskId()).put("destinationTask",destination).put("sourceTask",source).put("creates",creates).put("firstDestroyedBeforeCleanup",firstDestroyed).put("rootCreates",rootCreates)
                .put("firstInstance",firstInstance).put("secondInstance",instance).put("callerAfterMove",data.getStringExtra("result")).put("entryComponent",getComponentName().getClassName());finish();
        }catch(Exception error){throw new IllegalStateException(error);}}
        @Override protected void onDestroy(){if("one".equals(entry))firstDestroyed++;super.onDestroy();}
    }
    public static final class AfterMove extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);if(getTaskId()!=destination)throw new IllegalStateException("REPARENT_RESULT_TASK");setResult(RESULT_OK,new Intent().putExtra("result",getCallingActivity().flattenToString()));finish();}
    }
}
