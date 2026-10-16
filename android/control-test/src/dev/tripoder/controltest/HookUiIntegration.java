package dev.tripoder.controltest;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual native workbench controls, a distinct-UID worker and an unmodified owned APK. */
public final class HookUiIntegration extends ControlTestBase {
    private IBinder session;private String project,process,binding,install;private Object app,projects;
    @Override void test()throws Exception{
        report.put("scope","native-workbench-hook-ui-owned-counter");startThroughWorkbench(false);app=getTargetContext().getApplicationContext();session=(IBinder)field(field(app,"engine"),"session");projects=field(app,"projects");
        String space=run+"-ui";execute(session,"createSpace","operation",id(),"space",space);
        JSONObject fixtures;try(var in=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        File apk=new File(getTargetContext().getCacheDir(),run+"-counter.apk");
        try(var in=getContext().getAssets().open("preview-guest.apk");var out=new FileOutputStream(apk)){in.transferTo(out);out.getFD().sync();}
        try(var fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle request=ControlWire.request("install","space",space,"operation",id());request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getString("previewSha256"))));request.putParcelable("fd0",fd);
            install=ControlWire.call(session,ControlWire.EXECUTE,request).getString("generation");
        }finally{if(!apk.delete())throw new IOException("TEST_APK_COPY_NOT_REMOVED");}
        project=(String)invoke(projects,"create",new Class<?>[]{String.class},"Hook 真机验收 "+run.substring(8));binding=id();
        execute(session,"bindProject","operation",binding,"project",project,"space",space,"package","dev.tripoder.previewguest","generation",install);
        JSONArray selectors=new JSONArray().put(new JSONObject().put("id","counter").put("kind","java").put("className","dev.tripoder.previewguest.CounterActivity").put("methodName","formatCount").put("argumentTypes",new JSONArray().put("int")));
        invoke(projects,"selectors",new Class<?>[]{String.class,String.class},project,selectors.toString());
        final String script="import { definePlan } from '@abox/hook';\nexport default definePlan({\n  id: 'counter-ui', target, params: {},\n  setup(ctx) {\n    ctx.java.method('dev.tripoder.previewguest.CounterActivity', 'formatCount', ['int'])\n      .replace(({args}) => 'Hook ' + args[0]);\n  }\n});\n";
        java.util.concurrent.atomic.AtomicReference<Activity> visible=new java.util.concurrent.atomic.AtomicReference<>();
        Application application=(Application)app;Application.ActivityLifecycleCallbacks watcher=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().equals("dev.tripoder.hooklab.WorkbenchProjectActivity"))visible.set(a);}
            public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };application.registerActivityLifecycleCallbacks(watcher);
        try{
            getTargetContext().startActivity(new Intent().setClassName("dev.tripoder.workbench","dev.tripoder.hooklab.WorkbenchProjectActivity").putExtra("project",project).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AccessibilityNodeInfo editor=waitNode("项目脚本编辑器",true);Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,script);
            check("native-editor-accepts-script",editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text),"native accessibility edit");scrollTo("保存脚本 · 有未保存修改");click("保存脚本 · 有未保存修改");waitNode("脚本已保存",false);
            check("native-save-persists-exact-source",script.equals(invoke(projects,"script",new Class<?>[]{String.class},project)),"exact saved TypeScript");
            String launchOperation=id();Bundle prepared=execute(session,"prepareLaunch","operation",launchOperation,"project",project,"bindingOperation",binding,"generation",install,"component","dev.tripoder.previewguest.CounterActivity");process=prepared.getString("generation");report.put("workerPid",prepared.getInt("pid")).put("workerUid",prepared.getInt("uid")).put("project",project).put("space",space).put("installGeneration",install).put("processGeneration",process);
            scrollTo("选择运行进程");click("选择运行进程");click("dev.tripoder.previewguest · PID "+prepared.getInt("pid"));awaitOperations();
            scrollTo("1 · 观察目标");click("1 · 观察目标");awaitOperations();check("native-panel-observes-real-method",panelState().equals("OBSERVED"),panelState());
            scrollTo("2 · 检查脚本");click("2 · 检查脚本");awaitOperations();check("native-panel-prepares-without-activation",panelState().equals("PREPARED"),panelState());
            scrollTo("3 · 确认激活");click("3 · 确认激活");click("确认激活");awaitOperations();check("native-panel-receives-active-ack",panelState().equals("ACTIVE"),panelState());capture("workbench-active");
            Activity page=visible.get();if(page==null)throw new IllegalStateException("PROJECT_PAGE_NOT_OBSERVED");
            runOnMainSync(()->page.startActivity(new Intent().setClassName("dev.tripoder.engine","dev.tripoder.runtime.RuntimeGuestGateway").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("operation",launchOperation).putExtra("capability",prepared.getString("capability")),ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()));
            waitNode("Hook 0",false);check("actual-counter-renders-hooked-value",true,"Hook 0");click("＋  计数一次");waitNode("Hook 1",false);capture("counter-hook-active");
            JSONObject active=status();check("actual-ui-calls-hit-worker-hook",active.getString("state").equals("ACTIVE")&&active.getInt("hits")==2&&active.getInt("errors")==0,active);
            click("完成并返回 Tripoder");waitNode("脚本部署",false);scrollTo("停用脚本");click("停用脚本");awaitOperations();check("native-panel-confirms-disable",panelState().equals("DISABLED"),panelState());capture("workbench-disabled");
            scrollTop();click("启动空间应用");click("dev.tripoder.previewguest.CounterActivity");waitNode("1",false);capture("counter-original-restored");
            JSONObject restored=status();check("explicit-next-launch-keeps-original-behavior",restored.getString("state").equals("IDLE"),restored);click("完成并返回 Tripoder");
            check("guest-and-workbench-use-distinct-uids",prepared.getInt("uid")!=android.os.Process.myUid(),new JSONObject().put("guest",prepared.getInt("uid")).put("workbench",android.os.Process.myUid()));
            report.put("ownedApkSha256",fixtures.getString("previewSha256")).put("apkRewritten",false).put("businessScenarioVerified",false);
        }finally{application.unregisterActivityLifecycleCallbacks(watcher);}
    }
    private String panelState()throws Exception{Object sessions=field(app,"hooks");Object state=invoke(sessions,"project",new Class<?>[]{String.class},project);JSONObject status=(JSONObject)field(state,"status");return status==null?"UNCONFIRMED":status.optString("state");}
    private void awaitOperations()throws Exception{Object operations=field(app,"operations");long end=SystemClock.elapsedRealtime()+18000;do{SystemClock.sleep(40);if(!(Boolean)invoke(operations,"pending",new Class<?>[0]))return;}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("UI_OPERATION_UNCONFIRMED:"+field(operations,"message"));}
    private JSONObject status()throws Exception{JSONObject raw=new JSONObject().put("command","status").put("processGeneration",process);Bundle result=execute(session,"hook","operation",id(),"project",project,"bindingOperation",binding,"generation",install,"processGeneration",process,"request",raw.toString());return new JSONObject(result.getString("result")).getJSONObject("result");}
    private AccessibilityNodeInfo waitNode(String text,boolean description)throws Exception{long end=SystemClock.elapsedRealtime()+10000;do{AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow(),match=find(root,text,description);if(match!=null)return match;SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("UI_TARGET_NOT_FOUND:"+text);}
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value,boolean description){if(node==null)return null;CharSequence text=description?node.getContentDescription():node.getText();if(text!=null&&value.contentEquals(text))return node;for(int i=0;i<node.getChildCount();i++){var found=find(node.getChild(i),value,description);if(found!=null)return found;}return null;}
    private void click(String text)throws Exception{AccessibilityNodeInfo node=waitNode(text,false);for(int i=0;i<4&&node!=null;i++,node=node.getParent())if(node.isClickable()){if(!node.isEnabled()||!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("UI_CLICK_REJECTED:"+text);return;}throw new IllegalStateException("UI_TARGET_NOT_CLICKABLE:"+text);}
    private void scrollTo(String text)throws Exception{for(int i=0;i<12;i++){var node=find(getUiAutomation().getRootInActiveWindow(),text,false);if(node!=null&&node.isVisibleToUser())return;scroll(true);SystemClock.sleep(180);}throw new IllegalStateException("UI_SCROLL_TARGET_NOT_FOUND:"+text);}
    private void scrollTop(){for(int i=0;i<12;i++){if(!scroll(false))break;SystemClock.sleep(50);}}
    private boolean scroll(boolean forward){var root=getUiAutomation().getRootInActiveWindow();return scrollNode(root,forward);}
    private boolean scrollNode(AccessibilityNodeInfo node,boolean forward){if(node==null)return false;if(node.isScrollable()&&node.performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;for(int i=0;i<node.getChildCount();i++)if(scrollNode(node.getChild(i),forward))return true;return false;}
    private void capture(String label)throws Exception{getUiAutomation().waitForIdle(300,3000);SystemClock.sleep(650);var root=getUiAutomation().getRootInActiveWindow();String pkg=root==null?"":String.valueOf(root.getPackageName());if(!pkg.equals("dev.tripoder.workbench")&&!pkg.equals("dev.tripoder.engine"))throw new IOException("UI_SCREENSHOT_NOT_OWNED:"+pkg);Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IOException("UI_SCREENSHOT_UNAVAILABLE");String name=run+"-"+label+".png";try(var out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("UI_SCREENSHOT_ENCODE_FAILED");out.getFD().sync();}bitmap.recycle();JSONArray captures=report.optJSONArray("captures");if(captures==null){captures=new JSONArray();report.put("captures",captures);}captures.put(name);}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...args)throws Exception{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,args);}
    private static String id(){return UUID.randomUUID().toString();}
}
