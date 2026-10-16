package dev.tripoder.p2;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import org.json.*;

/** Real AMS task reuse; coordination only observes lifecycle, never invokes it. */
public final class TaskModes {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static JSONObject result;private static JSONArray events;
    private static String rootInstance;private static int rootTask,rootCreates,newIntents,bDestroyed,cDestroyed,phase;
    private static void event(String value){events.put(value);}
    public static final class Coordinator extends Activity {
        private int attempts;
        @Override public void onCreate(Bundle state){super.onCreate(state);if(state!=null)throw new IllegalStateException("TASK_COORDINATOR_RECREATED");
            result=null;events=new JSONArray();rootCreates=newIntents=bDestroyed=cDestroyed=phase=0;
            startActivity(new Intent(this,Root.class).putExtra("entry",0));main.postDelayed(this::collect,50);
        }
        private void collect(){if(result==null){if(++attempts>600)throw new IllegalStateException("TASK_MODE_TIMEOUT");main.postDelayed(this::collect,50);return;}
            try{result.put("coordinatorTask",getTaskId()).put("events",events);setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));finish();}catch(Exception error){throw new IllegalStateException(error);}}
    }
    public static final class Root extends Activity {
        private final String instance=UUID.randomUUID().toString();
        @Override public void onCreate(Bundle state){super.onCreate(state);rootCreates++;rootInstance=instance;rootTask=getTaskId();event("root-create");
            main.postDelayed(()->startActivity(new Intent(this,B.class)),100);
        }
        @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);try{
            int step=intent.getIntExtra("entry",-1);newIntents++;event("root-new-"+step);
            if(!instance.equals(rootInstance)||getTaskId()!=rootTask||!getCurrentCaller().getPackage().equals(getPackageName())||getCallingActivity()!=null||getCallingPackage()!=null)throw new IllegalStateException("TASK_REUSE_IDENTITY");
            if(step==1){if(!intent.getComponent().getClassName().equals("dev.tripoder.p2.TaskAlias"))throw new IllegalStateException("TASK_ALIAS_INTENT_LOST");main.postDelayed(()->startActivity(new Intent(this,Root.class).putExtra("entry",2)),100);}
            else if(step==2){phase=1;main.postDelayed(this::finishWhenDestroyed,50);}
            else throw new IllegalStateException("TASK_ENTRY_INVALID");
        }catch(Exception error){throw new IllegalStateException(error);}}
        private void finishWhenDestroyed(){if(bDestroyed!=1||cDestroyed!=1){main.postDelayed(this::finishWhenDestroyed,25);return;}
            try{result=new JSONObject().put("rootCreates",rootCreates).put("newIntents",newIntents).put("bDestroyed",bDestroyed).put("cDestroyed",cDestroyed).put("rootTask",rootTask).put("rootInstance",rootInstance).put("getIntentUnchanged",getIntent().getIntExtra("entry",-1)==0).put("pid",android.os.Process.myPid());finish();}catch(Exception error){throw new IllegalStateException(error);}}
        @Override protected void onDestroy(){event("root-destroy");super.onDestroy();}
    }
    public static final class B extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);if(getTaskId()!=rootTask)throw new IllegalStateException("B_WRONG_TASK");event("b-create");main.postDelayed(()->startActivity(new Intent(this,C.class)),100);}
        @Override protected void onDestroy(){bDestroyed++;event("b-destroy");super.onDestroy();}
    }
    public static final class C extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);if(getTaskId()!=rootTask)throw new IllegalStateException("C_WRONG_TASK");event("c-create");main.postDelayed(()->startActivity(new Intent().setComponent(new ComponentName(getPackageName(),"dev.tripoder.p2.TaskAlias")).putExtra("entry",1)),100);}
        @Override protected void onDestroy(){cDestroyed++;event("c-destroy");super.onDestroy();}
    }
}
