package dev.tripoder.p2;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.UUID;
import org.json.*;

/** Own APK adversarial protocol checks plus real Android recreation/result lifecycle. */
public final class ActivitySources {
    private static JSONObject report;private static JSONArray checks,attempts;
    private static Bundle oldInstance;private static boolean noResultDone;
    private static void check(String name,boolean passed,Object observed)throws Exception{
        checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",observed));
        if(!passed)throw new IllegalStateException("ASSERTION_FAILED:"+name);
    }
    private static Object engine()throws Exception{return Class.forName("dev.tripoder.runtime.RuntimeEngine").getField("current").get(null);}
    private static Bundle auth(Activity source)throws Exception{Object engine=engine();Method method=engine.getClass().getDeclaredMethod("activityAuth",Activity.class);method.setAccessible(true);return new Bundle((Bundle)method.invoke(engine,source));}
    private static void rejected(Bundle identity,String name,String expected)throws Exception{
        Bundle request=new Bundle(identity);String operation=UUID.randomUUID().toString();attempts.put(operation);
        request.putString("package","dev.tripoder.p2");request.putString("component",NoResult.class.getName());request.putString("operation",operation);request.putString("requestDigest","0".repeat(64));request.putBoolean("wantsResult",false);
        Object engine=engine();Field field=engine.getClass().getDeclaredField("broker");field.setAccessible(true);IBinder broker=(IBinder)field.get(engine);
        Class<?> wire=Class.forName("dev.tripoder.runtime.RuntimeWire");String failure="not rejected";
        try{wire.getMethod("call",IBinder.class,int.class,Bundle.class).invoke(null,broker,wire.getField("ROUTE").getInt(null),request);}
        catch(InvocationTargetException error){failure=String.valueOf(error.getCause());}
        check(name,failure.contains(expected),failure);
    }
    public static final class Main extends Activity {
        private boolean launchedResult;
        @Override public void onCreate(Bundle state){super.onCreate(state);try{
            if(state==null){
                report=new JSONObject();checks=new JSONArray();attempts=new JSONArray();noResultDone=false;
                oldInstance=auth(this);Bundle missing=new Bundle(oldInstance);missing.remove("ticket");rejected(missing,"source-ticket-required","INVALID_ticket");
                Bundle wrong=new Bundle(oldInstance);wrong.putString("instance",UUID.randomUUID().toString());rejected(wrong,"source-instance-required","ACTIVITY_INSTANCE_STALE");
                Bundle absent=new Bundle(oldInstance);absent.putString("ticket",UUID.randomUUID().toString());rejected(absent,"source-ticket-must-exist","ACTIVITY_TICKET_MISSING");
                new Handler(Looper.getMainLooper()).postDelayed(this::recreate,100);
            }else{
                Bundle current=auth(this);check("recreation-retains-ticket-changes-instance",current.getString("ticket").equals(oldInstance.getString("ticket"))&&!current.getString("instance").equals(oldInstance.getString("instance")),current.getString("instance"));
                rejected(oldInstance,"destroyed-source-instance-rejected","ACTIVITY_INSTANCE_STALE");
                startActivity(new Intent(this,NoResult.class));
            }
        }catch(Exception error){throw new IllegalStateException("ACTIVITY_SOURCE_FIXTURE",error);}}
        @Override protected void onResume(){super.onResume();if(!launchedResult&&noResultDone){launchedResult=true;startActivityForResult(new Intent(this,Remote.class),17);}}
        @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{
            check("source-result-request",request==17&&code==RESULT_OK&&data!=null,request);
            check("source-result-caller-logical",getCurrentCaller().getPackage().equals(getPackageName()),getCurrentCaller().getPackage());
            JSONObject remote=new JSONObject(data.getStringExtra("result"));
            check("source-alias-visible-to-result-recipient",remote.getString("caller").equals("dev.tripoder.p2/dev.tripoder.p2.SourceAlias"),remote);
            check("target-recreation-retains-caller",remote.getBoolean("recreated")&&remote.getBoolean("stable"),remote);
            report.put("checks",checks).put("rejectedOperations",attempts).put("remote",remote).put("passed",true);
            setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();
        }catch(Exception error){throw new IllegalStateException("ACTIVITY_SOURCE_RESULT",error);}}
    }
    public static final class NoResult extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);try{
            check("no-result-calling-package-null",getCallingPackage()==null,getCallingPackage()==null?JSONObject.NULL:getCallingPackage());
            check("no-result-calling-activity-null",getCallingActivity()==null,getCallingActivity()==null?JSONObject.NULL:getCallingActivity().toString());
            noResultDone=true;finish();
        }catch(Exception error){throw new IllegalStateException(error);}}
    }
    public static final class Remote extends Activity {
        private String original;
        @Override public void onCreate(Bundle state){super.onCreate(state);try{
            String caller=getCallingActivity().flattenToString();original=state==null?caller:state.getString("original");
            if(state==null)new Handler(Looper.getMainLooper()).postDelayed(this::recreate,100);
            else{JSONObject result=new JSONObject().put("caller",caller).put("recreated",true).put("stable",caller.equals(original)&&getCallingPackage().equals("dev.tripoder.p2")).put("pid",android.os.Process.myPid());setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));finish();}
        }catch(Exception error){throw new IllegalStateException(error);}}
        @Override protected void onSaveInstanceState(Bundle state){state.putString("original",original);super.onSaveInstanceState(state);}
    }
}
