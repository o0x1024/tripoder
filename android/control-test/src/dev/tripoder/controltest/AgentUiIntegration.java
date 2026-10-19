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

/** Drives only the owned counter UI and approval controls; desktop MCP executes every Hook command. */
public final class AgentUiIntegration extends ControlTestBase {
    private IBinder controller;private Object app,agents;private String project,process,binding,install;
    public static final String SOURCE="import { definePlan } from '@abox/hook';\nexport default definePlan({\n  id: 'counter-mcp', target, params: {},\n  setup(ctx) {\n    ctx.java.method('dev.tripoder.previewguest.CounterActivity', 'formatCount', ['int'])\n      .replace(({args}) => 'Hook ' + args[0]);\n  }\n});\n";
    @Override void test()throws Exception{
        report.put("scope","external-mcp-owned-counter-native-ui").put("approvalByOwnedTestInstrumentation",true);WorkbenchUiTestBase.isolateOperationJournal(this);startThroughWorkbench(false);
        app=getTargetContext().getApplicationContext();controller=(IBinder)field(field(app,"engine"),"session");agents=field(app,"agents");Object projects=field(app,"projects");
        String space=run+"-agent";execute(controller,"createSpace","operation",id(),"space",space);
        JSONObject fixtures;try(var in=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        File apk=new File(getTargetContext().getCacheDir(),run+"-counter.apk");try(var in=getContext().getAssets().open("preview-guest.apk");var out=new FileOutputStream(apk)){in.transferTo(out);out.getFD().sync();}
        try(var fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle request=ControlWire.request("install","space",space,"operation",id());request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getString("previewSha256"))));request.putParcelable("fd0",fd);install=ControlWire.call(controller,ControlWire.EXECUTE,request).getString("generation");
        }finally{if(!apk.delete())throw new IOException("TEST_APK_COPY_NOT_REMOVED");}
        project=(String)invoke(projects,"create",new Class<?>[]{String.class},"MCP 真机验收 "+run.substring(8));binding=id();execute(controller,"bindProject","operation",binding,"project",project,"space",space,"package","dev.tripoder.previewguest","generation",install);
        String originalScript=(String)invoke(projects,"script",new Class<?>[]{String.class},project);
        String launchOperation=id();Bundle prepared=execute(controller,"prepareLaunch","operation",launchOperation,"project",project,"bindingOperation",binding,"generation",install,"component","dev.tripoder.previewguest.CounterActivity");process=prepared.getString("generation");Bundle worker=awaitWorker(controller,project,prepared);
        report.put("workerPid",worker.getInt("pid")).put("workerUid",worker.getInt("uid")).put("project",project).put("space",space).put("processGeneration",process).put("installGeneration",install).put("ownedApkSha256",fixtures.getString("previewSha256")).put("apkRewritten",false);
        java.util.concurrent.atomic.AtomicReference<Activity> visible=new java.util.concurrent.atomic.AtomicReference<>();Application application=(Application)app;
        Application.ActivityLifecycleCallbacks watcher=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().equals("dev.tripoder.hooklab.WorkbenchProjectActivity"))visible.set(a);}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};application.registerActivityLifecycleCallbacks(watcher);
        try{
            getTargetContext().startActivity(new Intent().setClassName("dev.tripoder.engine","dev.tripoder.hooklab.WorkbenchProjectActivity").putExtra("project",project).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitNode("项目脚本编辑器",true,10000);
            click("外部 Agent");scrollTo("开启外部控制入口");click("开启外部控制入口");await(()->(Boolean)field(agents,"listening"),10000,"ENDPOINT_START");
            // Exercise rapid close/reopen with an actual TCP connection before any grant exists.
            try(java.net.Socket connection=new java.net.Socket("127.0.0.1",dev.tripoder.control.AgentCrypto.DEVICE_PORT)){connection.getOutputStream().write(new byte[]{0,0,0,0});connection.getOutputStream().flush();while(connection.getInputStream().read()!=-1){}}
            getTargetContext().startService(new Intent().setClassName("dev.tripoder.engine","dev.tripoder.hooklab.AgentControlService").setAction("stop"));await(()->!(Boolean)field(agents,"listening"),10000,"ENDPOINT_FIRST_STOP");
            SystemClock.sleep(2200);click("开启外部控制入口");await(()->(Boolean)field(agents,"listening"),10000,"ENDPOINT_REOPEN");check("endpoint-reopens-after-closed-tcp-connection",true,"same fixed loopback port; no authorization existed");
            Object key=invoke(agents,"deviceKey",new Class<?>[0]);signal("ready",new JSONObject().put("publicKey",field(key,"publicKey")).put("fingerprint",field(key,"fingerprint")).put("project",project).put("pid",worker.getInt("pid")).put("source",SOURCE));
            await(()->invoke(agents,"pending",new Class<?>[0])!=null,120000,"PAIR_REQUEST");SystemClock.sleep(2200);
            scrollTo("批准配对请求");click("批准配对请求");click("dev.tripoder.previewguest · PID "+worker.getInt("pid"));click("授权观察与准备");
            await(()->!((String)field(agents,"session")).isEmpty(),10000,"PAIR_APPROVAL");check("phone-ui-pairs-real-process",inspect().getString("processGeneration").equals(process),inspect());
            if("sentinel".equals(args.getString("phase"))){SystemClock.sleep(55000);JSONObject pending=inspect();check("first-client-wait-survives-45-second-disconnect-window",pending.getString("state").equals("OPEN")&&!pending.getBoolean("clientConnected"),pending);signal("waiting-client",new JSONObject());}
            await(()->{JSONObject state=inspect();if(!state.getString("state").equals("OPEN"))throw new IllegalStateException("AGENT_CLOSED_BEFORE_PREPARE:"+state.getString("reason"));return state.has("preparedSourceSha256");},"sentinel".equals(args.getString("phase"))?420000:180000,"EXTERNAL_PREPARE");JSONObject review=inspect();check("only-owned-counter-source-approved",SOURCE.equals(review.getString("draftSource")),review.getString("preparedSourceSha256"));
            SystemClock.sleep(2200);scrollTo("审阅外部脚本");click("审阅外部脚本");click("批准此版本部署");signal("reviewed",new JSONObject().put("sourceSha256",review.getString("preparedSourceSha256")));
            await(()->inspect().getString("hookState").equals("ACTIVE"),180000,"EXTERNAL_ACTIVATE");check("external-activation-confirmed-by-engine",true,inspect());capture("agent-active");
            // Pairing and human/model review may outlive the issued entry's validity; the query expires it,
            // and a new foreground launch is then issued explicitly.
            Bundle launchQuery=execute(controller,"query","operation",launchOperation);final Bundle activeLaunch;
            if("RUNNING".equals(launchQuery.getString("state")))activeLaunch=prepared;
            else if("FAILED".equals(launchQuery.getString("state"))&&"LAUNCH_ENTRY_NOT_ENTERED".equals(launchQuery.getString("detail")))activeLaunch=execute(controller,"prepareLaunch","operation",id(),"project",project,"bindingOperation",binding,"generation",install,"component","dev.tripoder.previewguest.CounterActivity");
            else throw new IllegalStateException("INITIAL_LAUNCH_OUTCOME_REQUIRES_RECONCILIATION:"+launchQuery.getString("state"));
            check("post-review-launch-keeps-exact-process",process.equals(activeLaunch.getString("generation")),activeLaunch.getString("generation"));
            Activity page=visible.get();if(page==null)throw new IllegalStateException("PROJECT_PAGE_NOT_OBSERVED");runOnMainSync(()->page.startActivity(activeLaunch.getParcelable("intent",Intent.class)));
            waitNode("Hook 0",false,10000);click("＋  计数一次");waitNode("Hook 1",false,10000);check("actual-counter-calls-hook-twice",true,"Hook 0 -> Hook 1");capture("counter-hooked");signal("hits",new JSONObject().put("expectedHits",2));
            if("disconnect".equals(args.getString("phase"))){
                await(()->inspect().getString("state").equals("CLOSED"),70000,"CONTACT_EXPIRY");JSONObject closed=inspect();check("disconnect-revokes-without-client-help",closed.getString("reason").equals("CONTACT_EXPIRED"),closed);
                JSONArray processes=new JSONArray(execute(controller,"hookProcesses","project",project).getString("processes"));boolean found=false;for(int i=0;i<processes.length();i++)if(process.equals(processes.getJSONObject(i).getString("processGeneration")))found=true;check("disconnect-terminates-exact-hooked-process",!found,processes);signal("contact-closed",closed);
                await(()->new File(getTargetContext().getExternalFilesDir(null),"agent-"+run+"-contact-confirmed.json").isFile(),20000,"DESKTOP_CLOSE_RECONCILIATION");
                getTargetContext().startService(new Intent().setClassName("dev.tripoder.engine","dev.tripoder.hooklab.AgentControlService").setAction("stop"));await(()->!(Boolean)field(agents,"listening"),10000,"ENDPOINT_STOP");return;
            }
            await(()->inspect().getString("hookState").equals("DISABLED"),180000,"EXTERNAL_DISABLE");click("＋  计数一次");waitNode("2",false,10000);capture("counter-restored");check("actual-counter-original-restored-after-mcp-disable",true,"Hook 1 -> 2 in the same Activity and process");click("完成并返回 Tripoder");waitNode("撤销会话并停止入口",false,10000);
            check("external-draft-does-not-overwrite-editor",originalScript.equals(invoke(projects,"script",new Class<?>[]{String.class},project)),"unchanged");check("guest-runs-in-separate-process-of-same-uid",worker.getInt("uid")==android.os.Process.myUid()&&worker.getInt("pid")!=android.os.Process.myPid(),worker.getInt("uid"));signal("restored",new JSONObject());
            if("sentinel".equals(args.getString("phase")))execute(controller,"agentRevoke","session",(String)field(agents,"session"));
            await(()->inspect().getString("state").equals("CLOSED"),90000,"EXTERNAL_CLOSE");check("revocation-stop-confirmed",true,inspect());
            if(!"sentinel".equals(args.getString("phase"))){
                File confirmation=new File(getTargetContext().getExternalFilesDir(null),"agent-"+run+"-close-confirmed.json");
                await(()->confirmation.isFile(),20000,"DESKTOP_FINAL_CLOSE_RECEIPT");
                if(confirmation.length()>4096)throw new SecurityException("DESKTOP_CONFIRMATION_TOO_LARGE");
                JSONObject received=new JSONObject(java.nio.file.Files.readString(confirmation.toPath()));
                check("desktop-received-final-closed-receipt",run.equals(received.getString("runId"))&&"close-confirmed".equals(received.getString("phase"))&&field(agents,"session").equals(received.getString("session"))&&"CLOSED".equals(received.getString("state")),received);
                UUID.fromString(received.getString("operationId"));
            }
            scrollTo("撤销会话并停止入口");click("撤销会话并停止入口");click("撤销并停止");await(()->!(Boolean)field(agents,"listening"),10000,"ENDPOINT_STOP");check("endpoint-stopped",true,"closed");
        }finally{application.unregisterActivityLifecycleCallbacks(watcher);}
    }
    private JSONObject inspect()throws Exception{return new JSONObject(execute(controller,"agentInspect","session",(String)field(agents,"session")).getString("result"));}
    private interface Condition{boolean get()throws Exception;}
    private void await(Condition condition,long timeout,String label)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.get())return;SystemClock.sleep(200);}throw new IllegalStateException("AGENT_UI_WAIT_TIMEOUT:"+label+":"+(agents==null?"":field(agents,"message")));}
    private void signal(String phase,JSONObject value)throws Exception{value.put("runId",run).put("phase",phase);android.util.AtomicFile file=new android.util.AtomicFile(new File(getTargetContext().getExternalFilesDir(null),"agent-"+run+"-"+phase+".json"));FileOutputStream out=file.startWrite();try{out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception error){file.failWrite(out);throw error;}}
    private AccessibilityNodeInfo waitNode(String text,boolean description,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;do{var node=find(getUiAutomation().getRootInActiveWindow(),text,description);if(node!=null)return node;SystemClock.sleep(80);}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("UI_TARGET_NOT_FOUND:"+text);}
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value,boolean description){if(node==null)return null;CharSequence text=description?node.getContentDescription():node.getText();if(text!=null&&value.contentEquals(text))return node;for(int i=0;i<node.getChildCount();i++){var found=find(node.getChild(i),value,description);if(found!=null)return found;}return null;}
    private void click(String text)throws Exception{var node=waitNode(text,false,10000);for(int i=0;i<4&&node!=null;i++,node=node.getParent())if(node.isClickable()){if(!node.isEnabled()||!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("UI_CLICK_REJECTED:"+text);return;}throw new IllegalStateException("UI_TARGET_NOT_CLICKABLE:"+text);}
    private void scrollTo(String text)throws Exception{for(int i=0;i<14;i++){var node=find(getUiAutomation().getRootInActiveWindow(),text,false);if(node!=null&&node.isVisibleToUser())return;scroll(getUiAutomation().getRootInActiveWindow(),true);SystemClock.sleep(160);}throw new IllegalStateException("UI_SCROLL_TARGET_NOT_FOUND:"+text);}
    private void scrollTop(){for(int i=0;i<14;i++){if(!scroll(getUiAutomation().getRootInActiveWindow(),false))break;SystemClock.sleep(60);}}
    private boolean scroll(AccessibilityNodeInfo node,boolean forward){if(node==null)return false;if(node.isScrollable()&&node.performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;for(int i=0;i<node.getChildCount();i++)if(scroll(node.getChild(i),forward))return true;return false;}
    private void capture(String label)throws Exception{getUiAutomation().waitForIdle(300,3000);SystemClock.sleep(650);var root=getUiAutomation().getRootInActiveWindow();String pkg=root==null?"":String.valueOf(root.getPackageName());if(!pkg.equals("dev.tripoder.engine")&&!pkg.equals("dev.tripoder.engine"))throw new IOException("UI_SCREENSHOT_NOT_OWNED");Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IOException("UI_SCREENSHOT_UNAVAILABLE");String name=run+"-"+label+".png";try(var out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.getFD().sync();}bitmap.recycle();JSONArray captures=report.optJSONArray("captures");if(captures==null){captures=new JSONArray();report.put("captures",captures);}captures.put(name);}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...args)throws Exception{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,args);}
    private static String id(){return UUID.randomUUID().toString();}
}
