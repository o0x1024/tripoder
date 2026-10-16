package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.widget.TextView;
import org.json.*;
import java.util.*;

/** Owned APK, public platform APIs only; no runtime dependency or privileged test transport. */
public final class Providers {
    static final String LOCAL="dev.tripoder.p2.local",ALIAS="dev.tripoder.p2.alias",REMOTE="dev.tripoder.p2.remote";
    static final List<String> order=new ArrayList<>();
    static boolean beforeApplication=true,localDependency,localCycle,applicationQuery,appOpsDenied=true,crossCycle;
    static Uri uri(String authority){return Uri.parse("content://"+authority+"/items?literal=a%2Fb#part");}
    public static class Owned extends ContentProvider {
        final String instance=UUID.randomUUID().toString();
        @Override public boolean onCreate(){beforeApplication&=((App)getContext().getApplicationContext()).starts==0;beforeApplication&=getContext().getPackageName().equals("dev.tripoder.p2")&&getContext().getClassLoader().getParent()==getClass().getClassLoader();boolean denied=false;try{getContext().getSystemService(Context.APP_OPS_SERVICE);}catch(UnsupportedOperationException expected){denied=true;}appOpsDenied&=denied;return true;}
        @Override public String getType(Uri uri){return "vnd.android.cursor.item/tripoder";}
        @Override public Cursor query(Uri uri,String[] projection,String where,String[] args,String sort){MatrixCursor result=new MatrixCursor(new String[]{"pid","uid","callerPid","callerUid","package","data","instance","uri","beforeApplication","factoryProviders"});result.addRow(new Object[]{android.os.Process.myPid(),android.os.Process.myUid(),Binder.getCallingPid(),Binder.getCallingUid(),getCallingPackage(),getContext().getDataDir().toString(),instance,uri.toString(),beforeApplication?1:0,Factory.providers});return result;}
        @Override public Bundle call(String method,String arg,Bundle extras){
            Bundle result=new Bundle();result.putInt("pid",android.os.Process.myPid());result.putInt("callerPid",Binder.getCallingPid());result.putInt("callerUid",Binder.getCallingUid());result.putString("caller",getCallingPackage());result.putString("instance",instance);result.putString("data",getContext().getDataDir().toString());result.putString("method",method);result.putString("arg",arg);result.putString("challenge",extras==null?null:extras.getString("challenge"));result.putInt("starts",((App)getContext().getApplicationContext()).starts);
            result.putString("attribution",getCallingAttributionSource()==null?null:getCallingAttributionSource().getPackageName());
            result.putBoolean("cycle",crossCycle);
            CallingIdentity token=clearCallingIdentity();result.putBoolean("cleared",getCallingPackage()==null);restoreCallingIdentity(token);result.putBoolean("restored",Objects.equals(result.getString("caller"),getCallingPackage()));
            if(method.equals("die"))new Handler(Looper.getMainLooper()).postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),150);
            return result;
        }
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("FIXTURE_READ_ONLY");}
        @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException("FIXTURE_READ_ONLY");}
        @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException("FIXTURE_READ_ONLY");}
    }
    public static final class High extends Owned {@Override public boolean onCreate(){super.onCreate();order.add("high");return true;}}
    public static final class Low extends Owned {@Override public boolean onCreate(){super.onCreate();order.add("low");try(Cursor c=getContext().getContentResolver().query(uri(LOCAL),null,null,null,null)){localDependency=c!=null&&c.moveToFirst()&&c.getInt(0)==android.os.Process.myPid();}return true;}}
    public static final class Loop extends Owned {@Override public boolean onCreate(){super.onCreate();order.add("loop");try{getContext().getContentResolver().getType(uri("dev.tripoder.p2.loop"));}catch(RuntimeException error){localCycle=android.util.Log.getStackTraceString(error).contains("PROVIDER_INITIALIZATION_CYCLE");}if(!localCycle)throw new IllegalStateException("SELF_CYCLE_NOT_REJECTED");return true;}}
    public static final class Remote extends Owned {}
    public static final class Never extends Owned {}
    public static class Cycle extends Owned {@Override public boolean onCreate(){super.onCreate();String other=getClass()==CycleA.class?"dev.tripoder.p2.cycleb":"dev.tripoder.p2.cyclea";try{getContext().getContentResolver().getType(uri(other));}catch(RuntimeException error){crossCycle=android.util.Log.getStackTraceString(error).contains("PROVIDER_INITIALIZATION_CYCLE");if(!crossCycle)throw error;}return true;}}
    public static final class CycleA extends Cycle {}
    public static final class CycleB extends Cycle {}
    static void application(App app){if(!android.app.Application.getProcessName().equals("dev.tripoder.p2:providerclient"))return;order.add("application");try(Cursor c=app.getContentResolver().query(uri(LOCAL),null,null,null,null)){applicationQuery=c!=null&&c.moveToFirst()&&c.getInt(0)==android.os.Process.myPid();}}
    public static final class Main extends Activity {
        private final JSONArray checks=new JSONArray();private final JSONObject report=new JSONObject();
        @Override public void onCreate(Bundle state){super.onCreate(state);TextView view=new TextView(this);view.setText("Tripoder owned Provider acceptance");setContentView(view);new Thread(this::run,"provider-acceptance").start();}
        private void check(String name,boolean value,Object observed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value).put("observed",observed));if(!value)throw new IllegalStateException("ASSERTION_FAILED:"+name);}
        private void run(){boolean passed=false;try{
            ContentResolver resolver=getContentResolver();
            check("provider-init-order-before-application",order.equals(List.of("high","low","loop","application"))&&beforeApplication&&Factory.providers==3,order.toString());
            check("provider-attach-does-not-expose-appops-to-guest",appOpsDenied,appOpsDenied);
            check("provider-initializer-can-query-ready-dependency",localDependency,localDependency);
            check("provider-local-initialization-cycle-rejected",localCycle,localCycle);
            check("application-onCreate-can-query-own-provider",applicationQuery,applicationQuery);
            String localInstance;try(Cursor c=resolver.query(uri(LOCAL),null,null,null,null)){c.moveToFirst();localInstance=c.getString(c.getColumnIndexOrThrow("instance"));}
            try(Cursor c=resolver.query(uri(ALIAS),null,null,null,null)){c.moveToFirst();check("provider-authority-alias-one-instance",localInstance.equals(c.getString(c.getColumnIndexOrThrow("instance"))),localInstance);}
            Uri logical=uri(REMOTE);int remotePid;
            try(Cursor c=resolver.query(logical,null,null,null,null)){
                check("provider-query-real-cursor",c!=null&&c.moveToFirst(),String.valueOf(c));remotePid=c.getInt(c.getColumnIndexOrThrow("pid"));
                check("provider-cross-process-real-binder",remotePid!=android.os.Process.myPid()&&c.getInt(c.getColumnIndexOrThrow("callerPid"))==android.os.Process.myPid()&&c.getInt(c.getColumnIndexOrThrow("callerUid"))==android.os.Process.myUid(),remotePid);
                check("provider-logical-caller-and-uri",getPackageName().equals(c.getString(c.getColumnIndexOrThrow("package")))&&logical.toString().equals(c.getString(c.getColumnIndexOrThrow("uri"))),c.getString(c.getColumnIndexOrThrow("uri")));
                check("provider-factory-and-context",c.getInt(c.getColumnIndexOrThrow("beforeApplication"))==1&&c.getInt(c.getColumnIndexOrThrow("factoryProviders"))==2,c.getInt(c.getColumnIndexOrThrow("factoryProviders")));
            }
            check("provider-getType-routed","vnd.android.cursor.item/tripoder".equals(resolver.getType(logical)),resolver.getType(logical));
            Bundle extras=new Bundle();String challenge=UUID.randomUUID().toString();extras.putString("challenge",challenge);
            Bundle called=resolver.call(REMOTE,"snapshot","argument",extras);
            check("provider-call-preserves-payload",challenge.equals(called.getString("challenge"))&&"argument".equals(called.getString("arg"))&&remotePid==called.getInt("pid"),called.toString());
            check("provider-calling-identity-clear-restore",getPackageName().equals(called.getString("caller"))&&getPackageName().equals(called.getString("attribution"))&&called.getBoolean("cleared")&&called.getBoolean("restored"),called.toString());
            try(ContentProviderClient stable=resolver.acquireContentProviderClient(REMOTE)){
                check("provider-explicit-stable-client",remotePid==stable.call("snapshot",null,null).getInt("pid"),remotePid);
                boolean wrong=false;try{stable.query(uri(LOCAL),null,null,null,null);}catch(RuntimeException expected){wrong=true;}check("provider-client-wrong-authority-rejected",wrong,wrong);
            }
            String missingType=resolver.getType(uri("dev.tripoder.p2.missing"));check("provider-absent-authority-type-is-null",missingType==null,missingType);
            try(ContentProviderClient unstable=resolver.acquireUnstableContentProviderClient(REMOTE)){
                check("provider-explicit-unstable-client",remotePid==unstable.call("snapshot",null,null).getInt("pid"),remotePid);
                unstable.call("die",null,null);Thread.sleep(1200);boolean stale=false;try{unstable.call("snapshot",null,null);}catch(Exception expected){stale=true;}check("provider-dead-generation-client-rejected",stale,stale);
            }
            Bundle fresh=resolver.call(REMOTE,"snapshot",null,null);
            check("provider-new-acquire-new-process",fresh.getInt("pid")!=remotePid&&!fresh.getString("instance").equals(called.getString("instance"))&&fresh.getString("data").equals(called.getString("data")),fresh.toString());
            Bundle cycleA=resolver.call("dev.tripoder.p2.cyclea","snapshot",null,null),cycleB=resolver.call("dev.tripoder.p2.cycleb","snapshot",null,null);
            check("provider-cross-process-init-cycle-rejected",cycleA.getBoolean("cycle")||cycleB.getBoolean("cycle"),cycleA.toString()+" / "+cycleB);
            boolean observer=false;try{resolver.notifyChange(logical,null);}catch(UnsupportedOperationException expected){observer=true;}check("provider-unimplemented-observer-rejected",observer,observer);
            ProviderWrites.run(this,this::check);
            report.put("data",fresh.getString("data")).put("remotePid",fresh.getInt("pid")).put("pid",android.os.Process.myPid());passed=true;
        }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}finally{try{report.put("passed",passed).put("checks",checks);runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();});}catch(JSONException error){throw new IllegalStateException(error);}}}
    }
}
