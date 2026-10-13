package dev.tripoder.hooklab;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Resources;
import android.os.*;
import android.util.Log;
import android.view.ContextThemeWrapper;
import dalvik.system.DexClassLoader;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Baseline launcher. No Gadget, scripts, return-value changes, or privileged helper. */
final class GameRuntime {
    static final String PACKAGE="com.tencent.tmgp.sgame";
    static volatile GameRuntime current;
    final Context host;
    final String projectId;
    final ProjectStore store;
    final JSONObject report=new JSONObject();
    final JSONArray events=new JSONArray();
    ClassLoader loader;
    Resources resources;
    ApplicationInfo info;
    ActivityInfo activityInfo;
    Application application;
    File data;
    boolean prepared;
    private Thread.UncaughtExceptionHandler previousHandler;
    GameRuntime(Context context,String id) throws Exception {
        host=context.getApplicationContext();projectId=ProjectStore.validId(id);store=new ProjectStore(host);
        if(!PACKAGE.equals(store.get(id).getJSONObject("app").getString("packageName")))throw new IllegalArgumentException("此运行器仅接入王者荣耀");
        report.put("schema",1).put("projectId",id).put("packageName",PACKAGE).put("process",Application.getProcessName())
            .put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("hookEnabled",false)
            .put("hostTargetSdk",host.getApplicationInfo().targetSdkVersion)
            .put("rootRequired",false).put("adbRequired",false).put("debuggable",(host.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0)
            .put("startedAt",System.currentTimeMillis()).put("events",events).put("gameplayVerified",false);
        previousHandler=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{failure(error);if(previousHandler!=null)previousHandler.uncaughtException(thread,error);});
        event("created");
    }
    void prepare() throws Exception {
        event("package-inspection");
        PackageInfo pkg=host.getPackageManager().getPackageInfo(PACKAGE,PackageManager.GET_ACTIVITIES|PackageManager.GET_META_DATA|PackageManager.GET_SIGNING_CERTIFICATES);
        info=new ApplicationInfo(pkg.applicationInfo);
        report.put("versionName",pkg.versionName).put("versionCode",pkg.getLongVersionCode());
        report.put("hostApkSha256",GuestStore.sha256(new File(host.getApplicationInfo().sourceDir)));
        report.put("targetApkSha256",GuestStore.sha256(new File(info.sourceDir)));
        Intent launch=host.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if(launch==null||launch.getComponent()==null)throw new IllegalStateException("游戏入口不存在");
        for(ActivityInfo item:pkg.activities)if(item.name.equals(launch.getComponent().getClassName()))activityInfo=new ActivityInfo(item);
        if(activityInfo==null)throw new IllegalStateException("游戏 Activity 元数据不存在");
        if(activityInfo.targetActivity!=null)throw new IllegalStateException("此游戏入口别名尚未适配");
        data=new File(store.data(projectId),"game");if(!data.isDirectory()&&!data.mkdirs())throw new IOException("无法创建游戏运行目录");
        info.dataDir=data.getAbsolutePath();
        info.deviceProtectedDataDir=new File(data,"device_protected").getAbsolutePath();info.uid=android.os.Process.myUid();
        resources=host.getPackageManager().getResourcesForApplication(pkg.applicationInfo);
        List<String> paths=new ArrayList<>();paths.add(info.sourceDir);if(info.splitSourceDirs!=null)paths.addAll(Arrays.asList(info.splitSourceDirs));
        loader=new DexClassLoader(String.join(File.pathSeparator,paths),null,info.nativeLibraryDir,host.getClassLoader());
        report.put("activityClass",activityInfo.name).put("applicationClass",info.className).put("dataDir",info.dataDir)
            .put("nativeLibraryDir",info.nativeLibraryDir).put("nativeLibraryDirectoryReadable",new File(info.nativeLibraryDir).canRead());
        event("class-loader-ready");prepared=true;
    }
    Activity newActivity() throws Exception {
        if(!prepared)throw new IllegalStateException("游戏运行器尚未准备");
        if(application==null){
            Thread.currentThread().setContextClassLoader(loader);
            event("application-attach-start");
            application=Instrumentation.newApplication(loader.loadClass(info.className),new GameContext(host,this));
            application.registerActivityLifecycleCallbacks((TripoderApplication)host);
            // Unity's native runtime obtains assets through ActivityThread.getApplication().
            // Keep this process-local Application consistent with the Activity's Application.
            Class<?> threadClass=Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentThread=threadClass.getDeclaredMethod("currentActivityThread");
            currentThread.setAccessible(true);
            set(threadClass,currentThread.invoke(null),"mInitialApplication",application);
            Context base=((ContextWrapper)host).getBaseContext();
            Field packageField=base.getClass().getDeclaredField("mPackageInfo");packageField.setAccessible(true);
            Object loadedPackage=packageField.get(base);
            set(loadedPackage.getClass(),loadedPackage,"mClassLoader",loader);
            set(loadedPackage.getClass(),loadedPackage,"mApplication",application);
            event("process-application-attached");
            event("application-create-start");new Instrumentation().callApplicationOnCreate(application);event("application-created");
        }
        event("activity-constructor-start");
        Activity target=(Activity)loader.loadClass(activityInfo.name).getDeclaredConstructor().newInstance();
        event("activity-constructed");return target;
    }
    static boolean owns(Activity activity) { GameRuntime r=current;return r!=null&&r.loader!=null&&activity.getClass().getClassLoader()==r.loader; }
    void attach(Activity activity) throws Exception {
        if(Build.VERSION.SDK_INT<29)throw new IllegalStateException("游戏运行器需要 Android 10 或更高版本");
        event("activity-context-start");
        Context original=activity.getBaseContext();
        set(ContextWrapper.class,activity,"mBase",new GameContext(original,this));
        set(ContextThemeWrapper.class,activity,"mResources",resources);
        activity.setTheme(resources.newTheme());
        set(Activity.class,activity,"mApplication",application);
        ActivityInfo target=new ActivityInfo(activityInfo);target.applicationInfo=info;
        set(Activity.class,activity,"mActivityInfo",target);
        activity.setTheme(activityInfo.getThemeResource());
        event("activity-context-attached");
    }
    private static void set(Class<?> owner,Object object,String name,Object value) throws Exception { Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(object,value); }
    synchronized void snapshot() {
        try(BufferedReader reader=new BufferedReader(new FileReader("/proc/self/maps"))){
            Set<String> modules=new TreeSet<>();boolean gadget=false;String line;
            while((line=reader.readLine())!=null){if(line.contains("libgadget.so"))gadget=true;int start=line.indexOf('/');if(start>=0&&line.contains(info.nativeLibraryDir)&&line.endsWith(".so"))modules.add(new File(line.substring(start)).getName());}
            report.put("loadedTargetLibraries",new JSONArray(modules)).put("gadgetMapped",gadget).put("snapshotAt",System.currentTimeMillis());persist();
        }catch(Exception error){Log.w("TripoderGame","Runtime snapshot unavailable",error);}
    }
    synchronized void event(String stage) {
        try{report.put("stage",stage).put("updatedAt",System.currentTimeMillis());if(events.length()<100)events.put(new JSONObject().put("stage",stage).put("time",System.currentTimeMillis()));persist();Log.i("TripoderGame",stage);}catch(Exception e){Log.e("TripoderGame","Cannot save launch state",e);}
    }
    synchronized void failure(Throwable error) {
        try{report.put("error",Log.getStackTraceString(error));event("failed");}catch(Exception ignored){}Log.e("TripoderGame","Game launch failed",error);
    }
    private void persist() throws Exception {
        store.write(projectId,"compatibility/game-launch.json",report.toString(2));
        File external=host.getExternalFilesDir(null);if(external!=null)ProjectStore.atomic(new File(external,"game-launch-latest.json"),report.toString(2));
    }
}
