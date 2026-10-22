package dev.tripoder.controltest;

import android.content.Intent;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.util.*;
import org.json.*;

/** Real tap, long-press, launch, space selection and deletion of an owned standalone APK. */
public final class AppActionsUiIntegration extends WorkbenchUiTestBase {
    private static final String PACKAGE="dev.tripoder.launcherfixture",SINGLE_TASK="dev.tripoder.launcherfixture.singletask";
    private Object app,projects,operations;private IBinder session;private String spaceA,spaceB,generationA,generationB;
    @Override void test()throws Exception{
        report.put("scope","single-application-project-native-ui");isolateOperationJournal();startThroughWorkbench(false);
        app=getTargetContext().getApplicationContext();session=(IBinder)field(field(app,"engine"),"session");projects=field(app,"projects");operations=field(app,"operations");
        spaceA="tap-a-"+run.substring(8);spaceB="tap-b-"+run.substring(8);execute(session,"createSpace","operation",id(),"space",spaceA);execute(session,"createSpace","operation",id(),"space",spaceB);
        importApk("launcher-guest.apk",spaceA);generationA=install(spaceA,PACKAGE).getString("generation");Bundle first=applicationProject(session,projects,spaceA,PACKAGE,generationA);String project=first.getString("project");
        invoke(projects,"script",new Class<?>[]{String.class,String.class},project,"// retained single application script\n");
        importApk("launcher-guest.apk",spaceB);generationB=install(spaceB,PACKAGE).getString("generation");Bundle second=applicationProject(session,projects,spaceB,PACKAGE,generationB);
        check("two-spaces-share-one-project",project.equals(second.getString("project")),project);
        home();check("project-navigation-removed",find(getUiAutomation().getRootInActiveWindow(),"项目标签",true)==null,"application and settings only");appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);waitNode("应用分析",false);click("应用分析");waitNode("项目脚本编辑器",true);
        check("analysis-opens-directly-without-project-picker",find(getUiAutomation().getRootInActiveWindow(),"＋ 新建项目",false)==null,"one source editor");capture("single-application-analysis");clickBack();home();
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("选择默认空间");click(spaceA+" · 版本 1");appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();click("＋  计数一次");returnFromGuest();
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("选择默认空间");click(spaceB+" · 版本 1");appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("space-data-remains-independent",find(getUiAutomation().getRootInActiveWindow(),"0",false)!=null,"space B starts at zero");returnFromGuest();
        String stale=id();check("stale-installation-is-rejected",rejected(()->execute(session,"uninstallApplication","operation",stale,"space",spaceB,"package",PACKAGE,"generation",generationA),IllegalStateException.class,"INSTALL_GENERATION_CONFLICT"),true);
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("选择默认空间");click(spaceA+" · 版本 1");taskChecks(PACKAGE,spaceA,generationA,"standard");
        execute(session,"deleteSpace","operation",id(),"space",spaceA);check("space-delete-retains-application-source",invoke(projects,"script",new Class<?>[]{String.class},project).equals("// retained single application script\n"),true);
        execute(session,"uninstallApplication","operation",id(),"space",spaceB,"package",PACKAGE,"generation",generationB);home();check("uninstalled-application-still-has-analysis",tile(PACKAGE)!=null,true);
        check("uninstalled-project-id-is-retained",new JSONArray(execute(session,"catalog").getString("applications")).toString().contains(project),project);
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("删除应用");click("删除");awaitOperations();home();
        check("explicit-application-delete-removes-source",!new File(getTargetContext().getFilesDir(),"projects/"+project).exists(),true);
        execute(session,"deleteSpace","operation",id(),"space",spaceB);report.put("project",project).put("spaceA",spaceA).put("spaceB",spaceB);
    }
    /** Leaving during a launch, the entry's own task, wake of a running application and Back from its root, all through real taps. */
    private void taskChecks(String pkg,String space,String generation,String mode)throws Exception{
        execute(session,"stopApplication","operation",id(),"space",space,"package",pkg,"generation",generation);
        // The user leaves before the guest is ready: the entry is handed back instead of holding the application open.
        home();tile(pkg).performAction(AccessibilityNodeInfo.ACTION_CLICK);
        // Leave only once the launch is crossing Binder, so the tap is known to have been taken.
        long submitted=SystemClock.elapsedRealtime()+8000;java.util.concurrent.atomic.AtomicBoolean crossing=new java.util.concurrent.atomic.AtomicBoolean();
        while(true){runOnMainSync(()->{try{crossing.set(!((Set<?>)field(operations,"submitting")).isEmpty());}catch(Exception error){throw new IllegalStateException(error);}});if(crossing.get())break;if(SystemClock.elapsedRealtime()>submitted)throw new IllegalStateException("LAUNCH_NOT_SUBMITTED");SystemClock.sleep(5);}
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
        // Handed back (not visible), expired (the platform dropped the start) or entered before Home took effect: never left pending.
        awaitOperations(45000);String left=String.valueOf(field(operations,"message"));JSONArray behind=guestTasks(pkg);
        check("leaving-during-launch-never-stays-pending-"+mode,left.contains("未完成")&&behind.length()==0||left.contains("已完成")&&behind.length()==1,new JSONObject().put("message",left).put("tasks",behind));
        // The user comes back from the launcher; an app in the background may not open its own pages (MIUI blocks that start).
        shell("am start -W -n "+enginePackage()+"/dev.tripoder.hooklab.WorkbenchActivity");
        home();tile(pkg).performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();awaitOperations();
        JSONArray opened=guestTasks(pkg);int task=opened.length()==1?opened.getJSONObject(0).getInt("taskId"):-1;Set<Integer> workers=slotPids();
        check("next-tap-opens-one-own-task-"+mode,task>=0&&task!=workbenchTask()&&opened.getJSONObject(0).getInt("numActivities")==1,new JSONObject().put("guest",opened).put("workbenchTask",workbenchTask()));
        // Tapping a running application returns its task; no second root and no new process.
        home();tile(pkg).performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();awaitOperations();
        JSONArray woken=guestTasks(pkg);
        check("tap-on-running-app-wakes-same-task-and-process-"+mode,woken.length()==1&&woken.getJSONObject(0).getInt("taskId")==task&&workers.equals(slotPids()),new JSONObject().put("guest",woken).put("before",workers.toString()).put("after",slotPids().toString()));
        // Back from the guest's root returns to the workbench.
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);waitNode("添加应用",true);
        check("back-from-guest-root-returns-to-workbench-"+mode,true,"workbench visible");
        tile(pkg).performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();awaitOperations();
        JSONArray again=guestTasks(pkg);
        // The platform either moved the root task back (woken again) or finished it (a fresh root in the same process); both are valid, never two roots.
        check("tap-after-back-opens-one-task-in-same-process-"+mode,again.length()==1&&workers.equals(slotPids()),new JSONObject().put("guest",again).put("sameTask",again.length()==1&&again.getJSONObject(0).getInt("taskId")==task));
        capture("task-"+mode.toLowerCase(java.util.Locale.ROOT));returnFromGuest();
    }
    private JSONArray guestTasks(String pkg)throws Exception{
        JSONArray all=new JSONArray(execute(session,"resources").getString("tasks")),out=new JSONArray();
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(pkg.equals(row.optString("package"))&&row.getInt("numActivities")>0)out.put(row);}return out;
    }
    private int workbenchTask()throws Exception{
        JSONArray all=new JSONArray(execute(session,"resources").getString("tasks"));
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(row.optString("baseComponent").endsWith("hooklab.WorkbenchActivity"))return row.getInt("taskId");}return -1;
    }
    private Set<Integer> slotPids(){
        Set<Integer> pids=new TreeSet<>();var processes=getTargetContext().getSystemService(android.app.ActivityManager.class).getRunningAppProcesses();
        if(processes!=null)for(var process:processes)if(process.processName.startsWith(enginePackage()+":slot"))pids.add(process.pid);return pids;
    }
    private AccessibilityNodeInfo tile(String pkg)throws Exception{getUiAutomation().waitForIdle(300,3000);SystemClock.sleep(350);long end=SystemClock.elapsedRealtime()+8000;do{var node=findPackage(getUiAutomation().getRootInActiveWindow(),pkg);if(node!=null)return node;SystemClock.sleep(60);}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("APPLICATION_TILE_MISSING:"+pkg);}
    private void importApk(String asset,String space)throws Exception{
        File apk=new File(getTargetContext().getCacheDir(),run+"-"+asset);
        try(var in=getContext().getAssets().open(asset);var out=new FileOutputStream(apk)){in.transferTo(out);out.getFD().sync();}
        Object imports=field(app,"imports");runOnMainSync(()->{try{invoke(imports,"importUri",new Class<?>[]{android.net.Uri.class,String.class},android.net.Uri.fromFile(apk),space);}catch(Exception error){throw new IllegalStateException(error);}});
        long end=SystemClock.elapsedRealtime()+20000;while((Boolean)field(imports,"preparing")){if(SystemClock.elapsedRealtime()>end)throw new IllegalStateException("IMPORT_PREPARATION_TIMEOUT");SystemClock.sleep(50);}awaitOperations();if(!apk.delete())throw new IOException("FIXTURE_COPY_NOT_REMOVED");
    }
    private JSONObject install(String space,String pkg)throws Exception{JSONArray all=new JSONArray(execute(session,"catalog").getString("installs"));for(int i=0;i<all.length();i++){JSONObject value=all.getJSONObject(i);if(space.equals(value.getString("space"))&&pkg.equals(value.getString("package")))return value;}return null;}
    private JSONObject binding(String project)throws Exception{JSONArray all=new JSONArray(execute(session,"catalog").getString("projects"));for(int i=0;i<all.length();i++)if(project.equals(all.getJSONObject(i).getString("project")))return all.getJSONObject(i);throw new IllegalStateException("BINDING_MISSING");}
    private void home()throws Exception{startActivitySync(new Intent().setComponent(engineComponent("dev.tripoder.hooklab.WorkbenchActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();getUiAutomation().waitForIdle(300,3000);clickDescription("更多");click("刷新");waitNode("添加应用",true);SystemClock.sleep(550);}
    private AccessibilityNodeInfo appTile()throws Exception{return tile(PACKAGE);}
    private void waitGuest()throws Exception{try{waitNode("＋  计数一次",false);}catch(Exception error){report.put("launchUiStatus",field(operations,"message"));report.put("testJournal",new JSONObject(getTargetContext().getSharedPreferences("ui-acceptance-"+run,0).getAll()));report.put("launchResources",json(execute(session,"resources")));throw error;}String pkg=getUiAutomation().getRootInActiveWindow().getPackageName().toString();if(!pkg.equals(enginePackage()))throw new IllegalStateException("GUEST_WINDOW_NOT_ENGINE:"+pkg);}
    private void returnFromGuest()throws Exception{scrollTo("完成并返回 Tripoder");click("完成并返回 Tripoder");waitNode("添加应用",true);awaitOperations();}
    private boolean lastSucceeded()throws Exception{return String.valueOf(field(operations,"message")).contains("已完成");}
    private void awaitOperations()throws Exception{awaitOperations(25000);}
    private void awaitOperations(long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;do{runOnMainSync(()->{try{invoke(operations,"query",new Class<?>[0]);}catch(Exception error){throw new IllegalStateException(error);}});SystemClock.sleep(70);if(!(Boolean)invoke(operations,"pending",new Class<?>[0]))return;}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("OPERATION_UNCONFIRMED:"+field(operations,"message"));}
}
