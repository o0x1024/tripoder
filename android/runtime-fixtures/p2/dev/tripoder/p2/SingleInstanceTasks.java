package dev.tripoder.p2;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.UUID;

/** Single-instance roots may not absorb the children they launch. */
public final class SingleInstanceTasks {
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static JSONObject result;
    private static int creates,deliveries,rootTask,childTask,destroyed;
    private static String rootInstance;
    public static final class Coordinator extends Activity {
        private int attempts;
        @Override public void onCreate(Bundle state){super.onCreate(state);result=null;creates=deliveries=destroyed=0;
            startActivity(new Intent(this,Root.class));main.postDelayed(this::collect,50);}
        private void collect(){if(result==null){if(++attempts>600)throw new IllegalStateException("SINGLE_INSTANCE_TIMEOUT");main.postDelayed(this::collect,50);return;}
            try{result.put("coordinatorTask",getTaskId());setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));finish();}catch(Exception e){throw new IllegalStateException(e);}}
    }
    public static final class Root extends Activity {
        private final String instance=UUID.randomUUID().toString();
        @Override public void onCreate(Bundle state){super.onCreate(state);creates++;rootTask=getTaskId();rootInstance=instance;
            main.postDelayed(()->startActivity(new Intent(this,Child.class)),100);}
        @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);deliveries++;
            if(!instance.equals(rootInstance)||getTaskId()!=rootTask||!intent.getComponent().getClassName().equals("dev.tripoder.p2.InstanceAlias")||!getCurrentCaller().getPackage().equals(getPackageName())||getCallingActivity()!=null)throw new IllegalStateException("SINGLE_INSTANCE_IDENTITY");
            main.postDelayed(this::complete,50);}
        private void complete(){if(destroyed!=1){main.postDelayed(this::complete,50);return;}
            try{result=new JSONObject().put("creates",creates).put("deliveries",deliveries).put("rootTask",rootTask).put("childTask",childTask).put("childDestroyed",destroyed).put("instance",instance);finish();}catch(Exception e){throw new IllegalStateException(e);}}
    }
    public static final class Child extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);childTask=getTaskId();
            main.postDelayed(()->{startActivity(new Intent().setComponent(new ComponentName(getPackageName(),"dev.tripoder.p2.InstanceAlias")));finish();},100);}
        @Override protected void onDestroy(){destroyed++;super.onDestroy();}
    }
}
