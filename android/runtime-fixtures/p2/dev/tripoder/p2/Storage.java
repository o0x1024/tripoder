package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.database.sqlite.*;
import android.os.*;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Self-owned public-SDK storage workload. No runtime classes or physical host paths. */
public final class Storage {
    public static final class Main extends Activity {
        final JSONArray checks=new JSONArray();final JSONObject report=new JSONObject();final BlockingQueue<JSONObject> remote=new LinkedBlockingQueue<>();
        @Override public void onCreate(Bundle state){super.onCreate(state);TextView text=new TextView(this);text.setText("Tripoder owned storage acceptance");setContentView(text);new Thread(this::run,"storage-acceptance").start();}
        void check(String name,boolean pass,Object value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",pass).put("observed",value));if(!pass)throw new IllegalStateException("ASSERTION_FAILED:"+name);}
        void run(){boolean passed=false;try{
            int round=getIntent().getIntExtra("round",-1);String token=getIntent().getStringExtra("token");
            if(round>0)try(var in=openFileInput("storage-value")){check("file-retained-after-restart",token.equals(new String(in.readAllBytes(),StandardCharsets.UTF_8)),token);}
            try(var out=openFileOutput("storage-value",MODE_PRIVATE)){out.write(token.getBytes(StandardCharsets.UTF_8));}
            try(var out=openFileOutput("append-value",MODE_PRIVATE)){out.write(1);}try(var out=openFileOutput("append-value",MODE_APPEND)){out.write(2);}
            try(var in=openFileInput("append-value")){check("file-append-real-bytes",Arrays.equals(in.readAllBytes(),new byte[]{1,2}),getFileStreamPath("append-value"));}
            check("file-list-and-delete",Arrays.asList(fileList()).contains("append-value")&&deleteFile("append-value")&&!getFileStreamPath("append-value").exists(),Arrays.toString(fileList()));
            boolean mode=false;try{openFileOutput("bad-mode",MODE_WORLD_READABLE);}catch(IllegalArgumentException expected){mode=true;}check("file-invalid-mode-rejected",mode,mode);
            File target=new File(getCacheDir(),"symlink-target");Files.writeString(target.toPath(),"untouched");File link=new File(getFilesDir(),"link");NativeStorage.rawSymlink(target.toString(),link.toString());
            boolean symlink=false;try{openFileOutput("link",MODE_PRIVATE);}catch(SecurityException expected){symlink=true;}check("file-symlink-rejected-before-write",symlink&&Files.readString(target.toPath()).equals("untouched"),symlink);Files.delete(link.toPath());
            boolean traversal=false;try{getDatabasePath("../outside");}catch(IllegalArgumentException expected){traversal=true;}check("database-traversal-rejected",traversal,traversal);
            DatabasePaths.run(this,token,round);
            SharedPreferences p=getSharedPreferences("storage",MODE_PRIVATE);check("prefs-retained-and-space-specific",p.getInt("round",0)==round&&p.getInt("lifecycle",0)==round&&(round==0?!p.contains("token"):token.equals(p.getString("token",null))),p.getAll().toString());
            CountDownLatch notified=new CountDownLatch(1);AtomicBoolean onMain=new AtomicBoolean();SharedPreferences.OnSharedPreferenceChangeListener listener=(prefs,key)->{if("token".equals(key)){onMain.set(Looper.myLooper()==Looper.getMainLooper());notified.countDown();}};p.registerOnSharedPreferenceChangeListener(listener);
            Set<String> set=new LinkedHashSet<>(List.of("a","b"));var edit=p.edit().putString("token",token+round).putInt("round",round+1).putLong("long",12345678901L).putFloat("float",1.25f).putBoolean("boolean",true).putStringSet("set",set);set.add("not-owned");
            check("prefs-commit",edit.commit(),true);check("prefs-listener-main-thread",notified.await(5,TimeUnit.SECONDS)&&onMain.get(),onMain.get());p.unregisterOnSharedPreferenceChangeListener(listener);
            check("prefs-all-value-types",p.getInt("round",0)==round+1&&p.getLong("long",0)==12345678901L&&p.getFloat("float",0)==1.25f&&p.getBoolean("boolean",false)&&p.getStringSet("set",null).equals(Set.of("a","b")),p.getAll().toString());
            p.getStringSet("set",null).add("mutated-copy");check("prefs-set-no-aliasing",p.getStringSet("set",null).equals(Set.of("a","b")),p.getStringSet("set",null).toString());
            boolean type=false;try{p.getString("round",null);}catch(ClassCastException expected){type=true;}check("prefs-type-mismatch-rejected",type,type);
            for(int i=0;i<40;i++)p.edit().putInt("sequence",i).apply();check("prefs-apply-then-commit-ordered",p.edit().putString("token",token).putInt("sequence",40).commit()&&p.getInt("sequence",0)==40,p.getInt("sequence",0));
            SharedPreferences scratch=getSharedPreferences("scratch",MODE_PRIVATE);scratch.edit().putInt("remove",1).putInt("keep",2).commit();scratch.edit().remove("remove").commit();check("prefs-remove",!scratch.contains("remove")&&scratch.getInt("keep",0)==2,scratch.getAll().toString());scratch.edit().clear().putInt("afterClear",3).commit();check("prefs-clear-before-updates",scratch.getAll().size()==1&&scratch.getInt("afterClear",0)==3,scratch.getAll().toString());
            boolean modeRejected=false;try{getSharedPreferences("storage",MODE_WORLD_READABLE);}catch(IllegalArgumentException expected){modeRejected=true;}check("prefs-unsupported-mode-rejected",modeRejected,modeRejected);
            JSONObject concurrent=PreferenceProcesses.run(this,remote);check("prefs-real-process-concurrent-commit-and-reload",concurrent.getBoolean("passed"),concurrent);
            SharedPreferences erased=getSharedPreferences("erased",MODE_PRIVATE);erased.edit().putInt("value",4).apply();check("prefs-delete-orders-after-pending-apply",deleteSharedPreferences("erased")&&getSharedPreferences("erased",MODE_PRIVATE).getAll().isEmpty()&&!new File(getDataDir(),"shared_prefs/erased.xml").exists(),erased.getAll().toString());check("prefs-delete-absent-success",deleteSharedPreferences("erased"),true);
            for(int i=0;i<16;i++)getSharedPreferences("queue-"+i,MODE_PRIVATE).edit().putInt("value",i).apply();getSharedPreferences("queue-15",MODE_PRIVATE).edit().putBoolean("barrier",true).commit();long writers=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("guest-preferences")).count();check("prefs-one-writer-for-many-files",writers==1,writers);
            File broken=new File(getDataDir(),"shared_prefs/broken.xml");Files.writeString(broken.toPath(),"<map><int name='x' value='bad'/></map>");String bad=Files.readString(broken.toPath());boolean corrupt=false;try{getSharedPreferences("broken",MODE_PRIVATE);}catch(IllegalStateException expected){corrupt=expected.getMessage().startsWith("PREFERENCES_LOAD_FAILED");}check("prefs-corrupt-not-empty-or-replaced",corrupt&&Files.readString(broken.toPath()).equals(bad),corrupt);
            try(var db=openOrCreateDatabase("storage.db",MODE_ENABLE_WRITE_AHEAD_LOGGING,null)){
                db.execSQL("CREATE TABLE IF NOT EXISTS value (id INTEGER PRIMARY KEY, token TEXT NOT NULL)");try(var c=db.rawQuery("SELECT token FROM value WHERE id=1",null)){check("sqlite-retained-and-space-specific",round==0?!c.moveToFirst():c.moveToFirst()&&token.equals(c.getString(0)),round);}
                db.execSQL("INSERT OR REPLACE INTO value VALUES(1,?)",new Object[]{token});db.beginTransaction();try{db.execSQL("INSERT OR REPLACE INTO value VALUES(2,'must-rollback')");}finally{db.endTransaction();}
                try(var c=db.rawQuery("SELECT COUNT(*) FROM value WHERE id=2",null)){c.moveToFirst();check("sqlite-rollback",c.getInt(0)==0,c.getInt(0));}
                try(var c=db.rawQuery("PRAGMA journal_mode",null)){c.moveToFirst();check("sqlite-real-wal",c.getString(0).equals("wal"),c.getString(0));}
                runOnUiThread(()->startActivityForResult(new Intent(this,Remote.class).putExtra("token",token),63));JSONObject child=remote.poll(20,TimeUnit.SECONDS);check("storage-remote-completed",child!=null&&child.optBoolean("passed"),child);
                check("prefs-other-process-reads-committed-value",child.getBoolean("prefsRead"),child);check("sqlite-cross-process-shared-wal",child.getBoolean("databaseRead")&&child.getInt("pid")!=android.os.Process.myPid(),child);
                try(var c=db.rawQuery("SELECT token FROM value WHERE id=3",null)){check("sqlite-cross-process-write-visible",c.moveToFirst()&&token.equals(c.getString(0)),child);}
            }
            boolean dbMode=false;try{openOrCreateDatabase("bad.db",MODE_WORLD_WRITEABLE,null);}catch(IllegalArgumentException expected){dbMode=true;}check("sqlite-invalid-mode-rejected",dbMode,dbMode);
            File brokenDb=getDatabasePath("broken.db");Files.writeString(brokenDb.toPath(),"tripoder intentionally malformed database");byte[] original=Files.readAllBytes(brokenDb.toPath());boolean dbCorrupt=false;try(var db=openOrCreateDatabase("broken.db",MODE_PRIVATE,null)){db.rawQuery("SELECT * FROM sqlite_master",null).close();}catch(SQLiteException expected){dbCorrupt=true;}check("sqlite-corrupt-file-preserved",dbCorrupt&&Arrays.equals(original,Files.readAllBytes(brokenDb.toPath())),dbCorrupt);
            JSONArray nativeChecks=new JSONArray(NativeStorage.run(getPackageName(),getPackageCodePath(),token));for(int i=0;i<nativeChecks.length();i++){JSONObject item=nativeChecks.getJSONObject(i);check(item.getString("name"),item.getBoolean("passed"),item.opt("observed"));}
            check("java-and-jni-same-physical-bytes",Files.readString(new File(getFilesDir(),"native/bytes").toPath()).equals(token),token);
            // Last write is intentionally asynchronous: Activity completion and explicit stop must flush it.
            p.edit().putInt("lifecycle",round+1).apply();report.put("round",round).put("token",token).put("data",getDataDir().toString()).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid());passed=true;
        }catch(Throwable error){try{report.put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
        finally{try{report.put("passed",passed).put("checks",checks);runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();});}catch(JSONException error){throw new IllegalStateException(error);}}}
        @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{remote.offer(request==63&&code==RESULT_OK&&data!=null?new JSONObject(data.getStringExtra("result")):new JSONObject().put("passed",false));}catch(JSONException error){throw new IllegalStateException(error);}}
    }
    public static final class Remote extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);if(getIntent().getBooleanExtra("preferencesOnly",false)){PreferenceProcesses.remote(this);return;}JSONObject result=new JSONObject();try{
            String token=getIntent().getStringExtra("token");boolean read;
            boolean prefsRead=token.equals(getSharedPreferences("storage",MODE_PRIVATE).getString("token",null));
            try(var db=openOrCreateDatabase("storage.db",MODE_ENABLE_WRITE_AHEAD_LOGGING,null);var c=db.rawQuery("SELECT token FROM value WHERE id=1",null)){read=c.moveToFirst()&&token.equals(c.getString(0));db.execSQL("INSERT OR REPLACE INTO value VALUES(3,?)",new Object[]{token});}
            result.put("passed",true).put("prefsRead",prefsRead).put("databaseRead",read).put("pid",android.os.Process.myPid());
        }catch(Throwable error){try{result.put("passed",false).put("error",android.util.Log.getStackTraceString(error));}catch(JSONException ignored){}}
        setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));finish();}
    }
}
