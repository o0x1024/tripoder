package dev.tripoder.controltest;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Real picker selection, phone APK copies, multiple document results, partial failure and receipt recovery. */
public final class MultiAddUiIntegration extends WorkbenchUiTestBase {
    private Object app,imports,batch;private android.os.IBinder controller;private Activity page;
    @Override void test()throws Exception{
        report.put("scope","native-multiple-phone-apps-and-apk-files");isolateOperationJournal();startThroughWorkbench(false);
        app=getTargetContext().getApplicationContext();imports=field(app,"imports");batch=field(imports,"batch");controller=(android.os.IBinder)field(field(app,"engine"),"session");
        String space="multi-"+run.substring(8);execute(controller,"createSpace","operation",id(),"space",space);
        List<String> packages=List.of(available("com.miui.calculator","com.android.calculator2","com.google.android.calculator"),available("com.android.deskclock","com.google.android.deskclock"));
        Set<String> before=new HashSet<>();JSONArray owners=new JSONArray(execute(controller,"catalog").getString("applications"));for(int i=0;i<owners.length();i++)before.add(owners.getJSONObject(i).getString("package"));
        page=startActivitySync(new Intent().setComponent(engineComponent("dev.tripoder.hooklab.WorkbenchAddActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitReady();
        runOnMainSync(()->{try{set(page,"space",space);invoke(page,"render",new Class<?>[0]);}catch(Exception error){throw new IllegalStateException(error);}});
        search(packages.get(0));tapPackage(packages.get(0));search(packages.get(1));tapPackage(packages.get(1));
        check("phone-picker-retains-two-selected-apps-across-search",selection().containsAll(packages)&&selection().size()==2,true);
        tapPackage(packages.get(1));check("one-tile-can-be-deselected-without-clearing-others",selection().equals(Set.of(packages.get(0))),true);tapPackage(packages.get(1));
        click("APK 文件");click("手机应用");check("selection-survives-source-tab-switch",selection().containsAll(packages),true);
        click("取消全选");check("filtered-deselect-keeps-hidden-selection",selection().equals(Set.of(packages.get(0))),true);click("全选");click("清空");check("clear-removes-hidden-and-visible-selection",selection().isEmpty()&&!waitNode("安装所选应用",true).isEnabled(),true);
        tapPackage(packages.get(1));search(packages.get(0));tapPackage(packages.get(0));
        AtomicReference<Activity> replacement=new AtomicReference<>();Application application=(Application)app;Application.ActivityLifecycleCallbacks watcher=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity activity,Bundle state){if(activity.getClass().getName().equals("dev.tripoder.hooklab.WorkbenchAddActivity"))replacement.set(activity);}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        application.registerActivityLifecycleCallbacks(watcher);runOnMainSync(page::recreate);long deadline=SystemClock.elapsedRealtime()+10000;while(replacement.get()==null){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("RECREATION_TIMEOUT");SystemClock.sleep(30);}page=replacement.get();application.unregisterActivityLifecycleCallbacks(watcher);waitReady();
        check("multiple-selection-survives-activity-recreation",selection().containsAll(packages)&&selection().size()==2,true);waitNode("添加所选 2 个应用",false);capture("multiple-phone-selection");clickDescription("安装所选应用");waitBatch();
        JSONArray phoneItems=snapshotItems(batch);check("both-selected-phone-apps-have-successful-receipts",phoneItems.length()==2&&all(phoneItems,"SUCCEEDED"),phoneItems);
        JSONArray installed=new JSONArray(execute(controller,"catalog").getString("installs"));for(String pkg:packages)check("phone-app-imported-"+pkg,contains(installed,space,pkg),true);
        check("completed-phone-selection-is-cleared",selection().isEmpty(),true);search(packages.get(0));var existing=waitPackage(packages.get(0));check("already-installed-phone-app-is-not-selectable",!existing.isEnabled(),true);
        File first=copy("launcher-guest.apk"),second=copy("launcher-singletask.apk"),invalid=new File(getTargetContext().getCacheDir(),run+"-invalid.apk");Files.writeString(invalid.toPath(),"not an APK");
        Intent documentResult=new Intent().setData(Uri.fromFile(first));ClipData clip=ClipData.newUri(getTargetContext().getContentResolver(),"owned APKs",Uri.fromFile(first));clip.addItem(new ClipData.Item(Uri.fromFile(invalid)));clip.addItem(new ClipData.Item(Uri.fromFile(second)));documentResult.setClipData(clip);
        java.util.concurrent.atomic.AtomicBoolean multiple=new java.util.concurrent.atomic.AtomicBoolean();ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())){multiple.set(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));return new ActivityResult(Activity.RESULT_OK,documentResult);}return null;}};
        String previousBatch=(String)invoke(batch,"id",new Class<?>[0]);addMonitor(monitor);click("APK 文件");click("选择 APK 文件");deadline=SystemClock.elapsedRealtime()+10000;while(previousBatch.equals(invoke(batch,"id",new Class<?>[0]))){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("DOCUMENT_BATCH_NOT_CREATED");SystemClock.sleep(30);}waitBatch();removeMonitor(monitor);check("document-picker-enables-multiple-selection",multiple.get(),true);
        JSONArray fileItems=snapshotItems(batch);check("document-data-and-clipdata-are-deduplicated",fileItems.length()==3,fileItems);
        check("invalid-file-does-not-block-later-apks",fileItems.getJSONObject(0).getString("state").equals("SUCCEEDED")&&fileItems.getJSONObject(1).getString("state").equals("FAILED")&&fileItems.getJSONObject(2).getString("state").equals("SUCCEEDED"),fileItems);
        check("batch-summary-retains-partial-failure",invoke(batch,"summary",new Class<?>[0]).toString().contains("成功 2 个")&&invoke(batch,"summary",new Class<?>[0]).toString().contains("失败 1 个"),true);capture("multiple-apk-results");
        installed=new JSONArray(execute(controller,"catalog").getString("installs"));check("two-independent-apks-become-two-applications",contains(installed,space,"dev.tripoder.launcherfixture")&&contains(installed,space,"dev.tripoder.launcherfixture.singletask"),true);
        JSONObject persisted=new JSONObject(field(batch,"batch").toString());persisted.getJSONArray("items").getJSONObject(0).put("state","SUBMITTED");getTargetContext().getSharedPreferences("import-batch",0).edit().putString("current",persisted.toString()).commit();
        AtomicReference<Object> restored=new AtomicReference<>();runOnMainSync(()->{try{Class<?> type=batch.getClass();var constructor=type.getDeclaredConstructor(Context.class,imports.getClass(),field(app,"operations").getClass(),field(app,"engine").getClass());constructor.setAccessible(true);restored.set(constructor.newInstance(getTargetContext(),imports,field(app,"operations"),field(app,"engine")));}catch(Exception error){throw new IllegalStateException(error);}});
        Object recovered=restored.get();check("recovered-batch-waits-for-explicit-resume",(Boolean)invoke(recovered,"active",new Class<?>[0])&&!(Boolean)invoke(recovered,"running",new Class<?>[0]),true);String generations=installed.toString();runOnMainSync(()->{try{invoke(recovered,"resume",new Class<?>[0]);}catch(Exception error){throw new IllegalStateException(error);}});waitBatch(recovered);
        check("recovery-queries-original-operation-without-reinstalling",generations.equals(new JSONArray(execute(controller,"catalog").getString("installs")).toString())&&snapshotItems(recovered).getJSONObject(0).getString("operation").equals(fileItems.getJSONObject(0).getString("operation")),true);
        first.delete();second.delete();invalid.delete();execute(controller,"deleteSpace","operation",id(),"space",space);
        for(String pkg:List.of(packages.get(0),packages.get(1),"dev.tripoder.launcherfixture","dev.tripoder.launcherfixture.singletask"))if(!before.contains(pkg))try{Bundle deleted=execute(controller,"deleteApplication","operation",id(),"package",pkg,"expectedInstalls","[]");JSONArray ids=new JSONArray(deleted.getString("deletedProjects"));for(int i=0;i<ids.length();i++)invoke(field(app,"projects"),"delete",new Class<?>[]{String.class},ids.getString(i));}catch(IllegalStateException concurrent){report.put("retainedConcurrentApplication",pkg);}
        report.put("phonePackages",new JSONArray(packages)).put("phoneItems",phoneItems).put("fileItems",fileItems).put("workerUid",android.os.Process.myUid());
    }
    private String available(String...packages)throws Exception{for(String pkg:packages)try{getTargetContext().getPackageManager().getApplicationInfo(pkg,0);return pkg;}catch(android.content.pm.PackageManager.NameNotFoundException absent){}throw new IllegalStateException("OWNED_PHONE_FIXTURE_UNAVAILABLE");}
    private void waitReady()throws Exception{long deadline=SystemClock.elapsedRealtime()+10000;while((Boolean)field(page,"loading")||field(page,"catalog")==null){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("PICKER_NOT_READY");SystemClock.sleep(30);}}
    private Set<?> selection()throws Exception{return new HashSet<>((Set<?>)field(page,"selected"));}
    private void search(String value)throws Exception{var input=waitNode("搜索应用",true);Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);if(!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text))throw new IllegalStateException("SEARCH_CHANGE_REJECTED");SystemClock.sleep(100);}
    private AccessibilityNodeInfo waitPackage(String pkg)throws Exception{long deadline=SystemClock.elapsedRealtime()+10000;do{var node=findPackage(getUiAutomation().getRootInActiveWindow(),pkg);if(node!=null)return node;SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("PHONE_TILE_MISSING:"+pkg);}
    private void tapPackage(String pkg)throws Exception{if(!waitPackage(pkg).performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("PHONE_SELECTION_REJECTED");SystemClock.sleep(100);}
    private void waitBatch()throws Exception{waitBatch(batch);}
    private void waitBatch(Object target)throws Exception{long deadline=SystemClock.elapsedRealtime()+90000;do{runOnMainSync(()->{try{invoke(field(app,"operations"),"query",new Class<?>[0]);}catch(Exception error){throw new IllegalStateException(error);}});if((Boolean)invoke(target,"finished",new Class<?>[0])&&!(Boolean)invoke(target,"running",new Class<?>[0]))return;SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("BATCH_TIMEOUT:"+invoke(target,"summary",new Class<?>[0]));}
    private JSONArray snapshotItems(Object value)throws Exception{return new JSONArray(((JSONObject)field(value,"batch")).getJSONArray("items").toString());}
    private static boolean all(JSONArray items,String state)throws Exception{for(int i=0;i<items.length();i++)if(!state.equals(items.getJSONObject(i).getString("state")))return false;return true;}
    private static boolean contains(JSONArray installs,String space,String pkg)throws Exception{for(int i=0;i<installs.length();i++)if(space.equals(installs.getJSONObject(i).getString("space"))&&pkg.equals(installs.getJSONObject(i).getString("package")))return true;return false;}
    private File copy(String asset)throws Exception{File file=new File(getTargetContext().getCacheDir(),run+"-"+asset);try(var input=getContext().getAssets().open(asset);var output=new FileOutputStream(file)){input.transferTo(output);output.getFD().sync();}return file;}
    private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
}
