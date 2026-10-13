package dev.tripoder.hooklab;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import dalvik.system.DexClassLoader;
import org.json.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Loading preflight only: never constructs or initializes a target class. */
public final class CompatibilityService extends Service {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Messenger inbox=new Messenger(new Handler(Looper.getMainLooper(), message->{
        if(message.replyTo==null)return true;
        Messenger reply=message.replyTo;String target=message.getData().getString("packageName","");
        worker.execute(()->{JSONObject report;
            try{report=probe(target);}catch(Exception|LinkageError error){report=new JSONObject();try{report.put("error",error.getClass().getSimpleName()+": "+error.getMessage());}catch(JSONException ignored){}}
            Message response=Message.obtain();Bundle data=new Bundle();data.putString("report",report.toString());response.setData(data);
            try{reply.send(response);}catch(RemoteException ignored){}
        });return true;
    }));
    @Override public IBinder onBind(Intent intent){return inbox.getBinder();}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid());}
    private JSONObject probe(String packageName) throws Exception {
        long started=System.currentTimeMillis();
        JSONObject report=CompatibilityProbe.inspect(this,packageName);
        PackageInfo info=getPackageManager().getPackageInfo(packageName,PackageManager.GET_ACTIVITIES);
        ApplicationInfo application=info.applicationInfo;
        List<String> paths=new ArrayList<>();paths.add(application.sourceDir);
        if(application.splitSourceDirs!=null)paths.addAll(Arrays.asList(application.splitSourceDirs));
        // Only the boot class path is inherited; host classes cannot satisfy missing guest classes.
        DexClassLoader loader=new DexClassLoader(String.join(File.pathSeparator,paths),null,application.nativeLibraryDir,ClassLoader.getSystemClassLoader().getParent());
        JSONArray classes=new JSONArray();
        classes.put(resolve(loader,report.getString("applicationClass"),Application.class));
        String launcher=report.optString("launcherActivity","");
        if(info.activities!=null)for(ActivityInfo activity:info.activities)if(activity.name.equals(launcher)&&activity.targetActivity!=null){launcher=activity.targetActivity;break;}
        if(!launcher.isEmpty()&&!launcher.equals("null"))classes.put(resolve(loader,launcher,Activity.class));
        if(report.optBoolean("unityLibrariesObserved"))classes.put(resolve(loader,"com.unity3d.player.UnityPlayer",android.view.View.class));
        JSONObject resources=new JSONObject();
        try{
            android.content.res.Resources loaded=getPackageManager().getResourcesForApplication(application);
            resources.put("loaded",true).put("resourcePackage",application.packageName);
            if(application.labelRes!=0)resources.put("label",loaded.getString(application.labelRes));
            if(application.icon!=0){loaded.getDrawable(application.icon,null);resources.put("iconResolved",true);}
        }catch(Exception|LinkageError error){resources.put("loaded",false).put("error",error.getClass().getSimpleName()+": "+error.getMessage());}
        boolean passed=resources.optBoolean("loaded");
        for(int i=0;i<classes.length();i++)passed&=classes.getJSONObject(i).optBoolean("loaded")&&classes.getJSONObject(i).optBoolean("expectedBaseType");
        report.put("preflight",new JSONObject().put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid())
            .put("process",Application.getProcessName()).put("debuggable",(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)
            .put("startedAtMs",started).put("finishedAtMs",System.currentTimeMillis()).put("hostApkSha256",GuestStore.sha256(new File(getApplicationInfo().sourceDir)))
            .put("classes",classes).put("resources",resources).put("passed",passed)
            .put("targetClassInitializationRequested",false).put("nativeLibrariesLoaded",false).put("applicationLifecycleExecuted",false));
        if(!passed)report.getJSONArray("blockers").put("入口类或资源加载预检失败，详见 preflight 中的具体异常");
        return report;
    }
    private JSONObject resolve(ClassLoader loader,String name,Class<?> expected) throws JSONException {
        JSONObject result=new JSONObject().put("name",name);
        try{Class<?> type=Class.forName(name,false,loader);result.put("loaded",true).put("expectedBaseType",expected.isAssignableFrom(type)).put("declaredSuperclass",type.getSuperclass()==null?JSONObject.NULL:type.getSuperclass().getName());}
        catch(Exception|LinkageError error){result.put("loaded",false).put("error",error.getClass().getSimpleName()+": "+error.getMessage());}
        return result;
    }
}
