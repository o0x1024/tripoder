package dev.tripoder.p2;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.UUID;

/** Android drives every creation, reuse and return; no direct lifecycle invocation. */
public final class SingleTop {
    private static JSONObject report;private static JSONArray events;
    private static int creates,ones,twos,siblings;private static boolean nestedDestroyed,siblingDestroyed;
    private static String firstInstance;private static ComponentCaller firstCaller;
    public static class Base extends Activity {
        // A legal guest override must not merge runtime bookkeeping for distinct instances.
        @Override public boolean equals(Object other){return other instanceof Base;}
        @Override public int hashCode(){return 7;}
        private final String instance=UUID.randomUUID().toString();private int phase;private boolean awaiting;
        @Override public void onCreate(Bundle state){super.onCreate(state);phase=getIntent().getIntExtra("phase",0);try{
            if(phase==0){report=new JSONObject();events=new JSONArray();creates=ones=twos=siblings=0;nestedDestroyed=siblingDestroyed=false;firstInstance=instance;firstCaller=null;report.put("double",this instanceof Double).put("task",getTaskId()).put("pid",android.os.Process.myPid());}
            creates++;events.put("create:"+phase);if(phase==0)new Handler(Looper.getMainLooper()).postDelayed(()->startActivity(next(1)),150);
            else if(phase==2){report.put("nonTopNewInstance",!firstInstance.equals(instance)).put("sameTask",getTaskId()==report.getInt("task"));new Handler(Looper.getMainLooper()).postDelayed(this::finish,150);}
            else throw new IllegalStateException("UNEXPECTED_CREATE_PHASE");
        }catch(Exception error){throw new IllegalStateException(error);}}
        private Intent next(int value){return new Intent().setComponent(getComponentName()).putExtra("phase",value);}
        @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);try{
            ones++;int incoming=intent.getIntExtra("phase",-1);events.put("new:"+incoming);ComponentCaller caller=getCurrentCaller();
            if(!instance.equals(firstInstance)||!caller.getPackage().equals(getPackageName())||caller.getUid()!=android.os.Process.myUid()||!getReferrer().toString().equals("android-app://"+getPackageName()))throw new IllegalStateException("CALLER_OR_INSTANCE_MISMATCH");
            for(String key:intent.getExtras().keySet())if(key.startsWith("tripoder."))throw new IllegalStateException("PRIVATE_ENVELOPE_EXPOSED");
            boolean denied=false;try{caller.checkContentUriPermission(android.net.Uri.parse("content://owned/test"),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(UnsupportedOperationException expected){denied=expected.getMessage().contains("URI_GRANT_NOT_IMPLEMENTED");}if(!denied)throw new IllegalStateException("PHYSICAL_URI_PERMISSION_EXPOSED");
            if(incoming==1){firstCaller=caller;report.put("oldIntentPreservedFirst",getIntent().getIntExtra("phase",0)==0);startActivity(next(11));}
            else if(incoming==11){report.put("oldIntentPreservedSecond",getIntent().getIntExtra("phase",0)==0).put("savedCallerStable",firstCaller.getPackage().equals(getPackageName()));
                setIntent(intent);report.put("oneArgSetIntentClearsCaller",getCaller()==null);setIntent(intent,caller);report.put("twoArgSetIntentStoresCaller",getIntent()==intent&&getCaller().getPackage().equals(getPackageName()));
                startActivity(new Intent().setComponent(new ComponentName(getPackageName(),Sibling.class.getName())).putExtra("root",getComponentName().getClassName()));
            }else throw new IllegalStateException("UNEXPECTED_NEW_INTENT_PHASE");
        }catch(Exception error){throw new IllegalStateException(error);}}
        @Override protected void onResume(){super.onResume();if(phase==0&&ones==2&&!awaiting){awaiting=true;finishWhenReturned(0);}}
        private void finishWhenReturned(int attempt){
            if(!siblingDestroyed||!nestedDestroyed){if(attempt>200)throw new IllegalStateException("REAL_STACK_RETURN_TIMEOUT");new Handler(Looper.getMainLooper()).postDelayed(()->finishWhenReturned(attempt+1),20);return;}
            try{boolean outsideDenied=false;try{getCurrentCaller();}catch(IllegalStateException expected){outsideDenied=true;}report.put("outsideCurrentCallerRejected",outsideDenied).put("creates",creates).put("oneCallbacks",ones).put("twoCallbacks",twos).put("siblings",siblings).put("events",events).put("passed",true);
                setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();
            }catch(Exception error){throw new IllegalStateException(error);}
        }
        @Override protected void onDestroy(){if(phase==2)nestedDestroyed=true;events.put("destroy:"+phase);super.onDestroy();}
    }
    public static final class Root extends Base {}
    public static final class Double extends Base {
        @Override public void onNewIntent(Intent intent,ComponentCaller caller){twos++;if(!caller.getPackage().equals(getPackageName()))throw new IllegalStateException("DOUBLE_CALLER_MISMATCH");super.onNewIntent(intent,caller);}
    }
    public static final class Sibling extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);siblings++;events.put("sibling-create");startActivity(new Intent().setComponent(new ComponentName(getPackageName(),getIntent().getStringExtra("root"))).putExtra("phase",2));}
        @Override protected void onResume(){super.onResume();awaitNested(0);}
        private void awaitNested(int attempt){if(isFinishing())return;if(nestedDestroyed){finish();return;}if(attempt>300)throw new IllegalStateException("SIBLING_STACK_RETURN_TIMEOUT");new Handler(Looper.getMainLooper()).postDelayed(()->awaitNested(attempt+1),20);}
        @Override protected void onDestroy(){siblingDestroyed=true;events.put("sibling-destroy");super.onDestroy();}
    }
    public static final class CrashAfterWrite extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);try{
            java.io.File file=new java.io.File(getFilesDir(),"new-intent-effect.txt");
            if(file.exists()){setResult(RESULT_OK,new Intent().putExtra("result",new JSONObject().put("recovered",true).put("pid",android.os.Process.myPid()).toString()));finish();}
            else new Handler(Looper.getMainLooper()).postDelayed(()->startActivity(new Intent().setComponent(getComponentName())),200);
        }catch(Exception error){throw new IllegalStateException(error);}}
        @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);try{
            try(var out=openFileOutput("new-intent-effect.txt",MODE_APPEND)){out.write('x');out.getFD().sync();}
            android.os.Process.killProcess(android.os.Process.myPid());throw new IllegalStateException("PROCESS_KILL_FAILED");
        }catch(java.io.IOException error){throw new IllegalStateException(error);}}
    }
}
