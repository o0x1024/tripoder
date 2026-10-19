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
        report.put("scope","native-application-tap-long-press-and-deletion");isolateOperationJournal();startThroughWorkbench(false);
        app=getTargetContext().getApplicationContext();session=(IBinder)field(field(app,"engine"),"session");projects=field(app,"projects");operations=field(app,"operations");
        // Earlier runs' spaces go entirely, with whatever they left installed or bound.
        JSONArray prior=new JSONArray(execute(session,"catalog").getString("spaces"));
        for(int i=0;i<prior.length();i++)if(prior.getString(i).matches("tap-[ab]-[a-f0-9]{16}")){JSONArray dropped=new JSONArray(execute(session,"deleteSpace","operation",id(),"space",prior.getString(i)).getString("deletedProjects"));for(int j=0;j<dropped.length();j++)invoke(projects,"delete",new Class<?>[]{String.class},dropped.getString(j));}
        spaceA="tap-a-"+run.substring(8);spaceB="tap-b-"+run.substring(8);
        execute(session,"createSpace","operation",id(),"space",spaceA);execute(session,"createSpace","operation",id(),"space",spaceB);
        importApk("launcher-guest.apk",spaceA);generationA=install(spaceA,PACKAGE).getString("generation");
        int projectCount=((List<?>)invoke(projects,"all",new Class<?>[0])).size();String projectRefs=execute(session,"catalog").getString("projects");
        home();appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);waitNode("查看项目",false);waitNode("删除应用",false);capture("long-press-menu");
        check("long-press-does-not-launch",getUiAutomation().getRootInActiveWindow().getPackageName().toString().equals("dev.tripoder.engine")&&!(Boolean)invoke(operations,"pending",new Class<?>[0]),"management menu without a launch operation");
        click("查看项目");waitNode("＋ 新建项目",false);boolean bound=false;JSONArray initialBindings=new JSONArray(projectRefs);for(int i=0;i<initialBindings.length();i++)if(generationA.equals(initialBindings.getJSONObject(i).getString("generation")))bound=true;
        check("new-installation-has-no-project-binding",!bound,"older retained projects do not bind this new installation");capture("project-space");clickBack();
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(300);if(find(getUiAutomation().getRootInActiveWindow(),"在哪个空间运行？",false)!=null)click(spaceA+" · 版本 1");waitGuest();check("tap-launches-without-a-project",true,"standalone launcher in engine task");capture("direct-launch");
        click("＋  计数一次");waitNode("已保存到当前空间",false);returnFromGuest();
        check("direct-launch-never-creates-or-binds-project",projectCount==((List<?>)invoke(projects,"all",new Class<?>[0])).size()&&projectRefs.equals(execute(session,"catalog").getString("projects")),"project records unchanged");
        check("first-launch-has-durable-success",lastSucceeded(),"gateway completion queried on return");
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("repeat-tap-keeps-space-data",find(getUiAutomation().getRootInActiveWindow(),"1",false)!=null,"count = 1");returnFromGuest();
        // The space is chosen first; only projects bound in that space take over the tap.
        importApk("launcher-guest.apk",spaceB);generationB=install(spaceB,PACKAGE).getString("generation");home();
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("选择默认空间");click(spaceB+" · 版本 1");
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("explicit-space-selection-uses-independent-data",find(getUiAutomation().getRootInActiveWindow(),"0",false)!=null,"space B count = 0");returnFromGuest();
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("selected-space-is-remembered",find(getUiAutomation().getRootInActiveWindow(),"0",false)!=null,"second tap opens space B directly");returnFromGuest();
        // Deleting an application removes every installation, its data and the projects bound to it, from one long-press choice.
        String project=(String)invoke(projects,"create",new Class<?>[]{String.class},"随应用删除 "+run.substring(8,14));
        invoke(projects,"script",new Class<?>[]{String.class,String.class},project,"// removed together with the app\n");
        execute(session,"bindProject","operation",id(),"project",project,"space",spaceA,"package",PACKAGE,"generation",generationA);home();
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("project-in-another-space-does-not-take-over-tap",find(getUiAutomation().getRootInActiveWindow(),"0",false)!=null,"default space B stays in effect (count = 0)");returnFromGuest();
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("选择默认空间");click(spaceA+" · 版本 1");
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("single-bound-project-launches-in-chosen-space",find(getUiAutomation().getRootInActiveWindow(),"1",false)!=null,"bound project opens space A (count = 1)");returnFromGuest();
        String stale=id();check("stale-delete-rejected",rejected(()->execute(session,"uninstallApplication","operation",stale,"space",spaceB,"package",PACKAGE,"generation",generationA),IllegalStateException.class,"INSTALL_GENERATION_CONFLICT"),"exact generation required");
        check("rejected-delete-has-terminal-receipt","FAILED".equals(execute(session,"query","operation",stale).getString("state")),"no unqueryable pending operation");
        taskChecks(PACKAGE,spaceA,generationA,"standard");
        importApk("launcher-singletask.apk",spaceB);String singleTask=install(spaceB,SINGLE_TASK).getString("generation");
        taskChecks(SINGLE_TASK,spaceB,singleTask,"singleTask");
        execute(session,"deleteApplication","operation",id(),"package",SINGLE_TASK);
        check("single-task-fixture-removed",install(spaceB,SINGLE_TASK)==null,"leaves the remaining assertions' application counts unchanged");
        // Leave the application running in the background so deletion has to stop it.
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();home();awaitOperations();
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("删除应用");SystemClock.sleep(400);
        // One confirmation that states the consequence; no per-space choice. Nothing is sent before it is accepted.
        boolean confirmation=findPrefix(getUiAutomation().getRootInActiveWindow(),"删除「")!=null&&findPrefix(getUiAutomation().getRootInActiveWindow(),"将从 2 个空间中删除")!=null;
        check("delete-confirms-once-with-consequence",confirmation&&find(getUiAutomation().getRootInActiveWindow(),"选择要删除的运行空间",false)==null&&!(Boolean)invoke(operations,"pending",new Class<?>[0]),"confirmation names both spaces and the bound project; no operation before acceptance");
        capture("delete-confirmation");click("删除");
        awaitOperations();home();
        check("delete-stops-background-app-and-removes-every-install",install(spaceA,PACKAGE)==null&&install(spaceB,PACKAGE)==null,new JSONObject().put("message",field(operations,"message")).put("resources",json(execute(session,"resources"))));
        boolean sourceGone;try{invoke(projects,"script",new Class<?>[]{String.class},project);sourceGone=false;}catch(Throwable absent){sourceGone=true;}
        check("delete-removes-project-source",sourceGone,"project directory removed");
        check("delete-removes-project-binding",rejected(()->execute(session,"resolveProject","project",project),IllegalStateException.class,"PROJECT_NOT_BOUND"),"binding row removed, not left stale");
        check("grid-has-no-tile-for-deleted-app",findPackage(getUiAutomation().getRootInActiveWindow(),PACKAGE)==null,"no installation remains");
        clickDescription("项目标签");SystemClock.sleep(300);capture("project-index-after-delete");
        check("deleted-app-project-is-not-in-project-index",find(getUiAutomation().getRootInActiveWindow(),"随应用删除 "+run.substring(8,14),false)==null,"project removed with its application");
        // An exported arbitrary Activity is not a substitute for a real application launcher.
        importApk("preview-guest.apk",spaceA);String noLauncher=id(),previewGeneration=install(spaceA,"dev.tripoder.previewguest").getString("generation");
        check("no-launcher-does-not-start-arbitrary-activity",rejected(()->execute(session,"prepareApplicationLaunch","operation",noLauncher,"space",spaceA,"package","dev.tripoder.previewguest","generation",previewGeneration),IllegalStateException.class,"APPLICATION_HAS_NO_LAUNCHER"),"no fallback to arbitrary or host Activity");
        check("rejected-launch-has-terminal-receipt","FAILED".equals(execute(session,"query","operation",noLauncher).getString("state")),"launch rejected before worker startup");
        // A project's application cannot be swapped under it; another space or version of the same one can.
        String moved=(String)invoke(projects,"create",new Class<?>[]{String.class},"换应用 "+run.substring(8,14));
        String movedBinding=id();execute(session,"bindProject","operation",movedBinding,"project",moved,"space",spaceA,"package","dev.tripoder.previewguest","generation",previewGeneration);
        importApk("launcher-guest.apk",spaceA);String relaunched=install(spaceA,PACKAGE).getString("generation");
        check("project-cannot-move-to-another-application",rejected(()->execute(session,"bindProject","operation",id(),"project",moved,"space",spaceA,"package",PACKAGE,"generation",relaunched,"expectedBinding",movedBinding),IllegalStateException.class,"PROJECT_PACKAGE_CHANGE_UNSUPPORTED"),"binding unchanged");
        // An application removed from every space leaves bound projects behind; the project index offers their deletion, which goes through deleteApplication.
        // The launcher fixture is used because the deletion above proved it is installed nowhere else; other suites keep the preview fixture in their own spaces.
        String orphan=(String)invoke(projects,"create",new Class<?>[]{String.class},"应用已移除 "+run.substring(8,14));
        execute(session,"bindProject","operation",id(),"project",orphan,"space",spaceA,"package",PACKAGE,"generation",relaunched);
        execute(session,"uninstallApplication","operation",id(),"space",spaceA,"package",PACKAGE,"generation",relaunched);
        check("removed-application-leaves-stale-binding","STALE".equals(binding(orphan).getString("state")),binding(orphan));
        home();clickDescription("项目标签");String orphanTitle=PACKAGE+" 已从所有空间移除";waitNode(orphanTitle,false);capture("orphaned-application-projects");
        clickDescription("删除 "+PACKAGE+" 的项目");SystemClock.sleep(300);click("删除");awaitOperations();
        boolean orphanGone;try{invoke(projects,"script",new Class<?>[]{String.class},orphan);orphanGone=false;}catch(Throwable absent){orphanGone=true;}
        check("orphaned-projects-deleted-without-installation",orphanGone&&rejected(()->execute(session,"resolveProject","project",orphan),IllegalStateException.class,"PROJECT_NOT_BOUND"),"source and binding removed although no installation remained");
        home();clickDescription("项目标签");SystemClock.sleep(400);check("orphan-notice-gone",find(getUiAutomation().getRootInActiveWindow(),orphanTitle,false)==null,"no remaining binding");
        // Deleting a space removes its applications, their data and the projects bound to it; the space goes last.
        home();clickDescription("设置标签");scrollTo(spaceA);click(spaceA);click("删除空间");SystemClock.sleep(400);
        check("space-delete-confirms-consequence",findPrefix(getUiAutomation().getRootInActiveWindow(),"将删除这个空间中的 1 个应用及其全部数据，并删除绑定到这个空间的 1 个项目")!=null&&!(Boolean)invoke(operations,"pending",new Class<?>[0]),"one confirmation, nothing sent before it");
        capture("space-delete-confirmation");click("删除");awaitOperations();
        boolean movedGone;try{invoke(projects,"script",new Class<?>[]{String.class},moved);movedGone=false;}catch(Throwable absent){movedGone=true;}
        check("space-delete-removes-space-installs-and-projects",!new JSONArray(execute(session,"catalog").getString("spaces")).toString().contains("\""+spaceA+"\"")&&install(spaceA,"dev.tripoder.previewguest")==null&&movedGone&&rejected(()->execute(session,"resolveProject","project",moved),IllegalStateException.class,"PROJECT_NOT_BOUND"),new JSONObject().put("message",field(operations,"message")));
        String emptied=id();Bundle gone=execute(session,"deleteSpace","operation",emptied,"space",spaceB);
        check("empty-space-deleted","SUCCEEDED".equals(execute(session,"query","operation",emptied).getString("state"))&&gone.getInt("installs")==0,json(gone));
        String missing=id();check("missing-space-delete-rejected",rejected(()->execute(session,"deleteSpace","operation",missing,"space",spaceB),IllegalStateException.class,"SPACE_NOT_FOUND")&&"FAILED".equals(execute(session,"query","operation",missing).getString("state")),"terminal receipt");
        report.put("project",project).put("spaceA",spaceA).put("spaceB",spaceB).put("workerUid",execute(session,"status").getInt("engineUid")).put("guestLaunched",true).put("hostAppLaunched",false);
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
        shell("am start -W -n dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity");
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
        if(processes!=null)for(var process:processes)if(process.processName.startsWith("dev.tripoder.engine:slot"))pids.add(process.pid);return pids;
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
    private void home()throws Exception{startActivitySync(new Intent().setClassName("dev.tripoder.engine","dev.tripoder.hooklab.WorkbenchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();getUiAutomation().waitForIdle(300,3000);clickDescription("更多");click("刷新");waitNode("添加应用",true);SystemClock.sleep(550);}
    private AccessibilityNodeInfo appTile()throws Exception{return tile(PACKAGE);}
    private void waitGuest()throws Exception{try{waitNode("＋  计数一次",false);}catch(Exception error){report.put("launchUiStatus",field(operations,"message"));report.put("testJournal",new JSONObject(getTargetContext().getSharedPreferences("ui-acceptance-"+run,0).getAll()));report.put("launchResources",json(execute(session,"resources")));throw error;}String pkg=getUiAutomation().getRootInActiveWindow().getPackageName().toString();if(!pkg.equals("dev.tripoder.engine"))throw new IllegalStateException("GUEST_WINDOW_NOT_ENGINE:"+pkg);}
    private void returnFromGuest()throws Exception{scrollTo("完成并返回 Tripoder");click("完成并返回 Tripoder");waitNode("添加应用",true);awaitOperations();}
    private boolean lastSucceeded()throws Exception{return String.valueOf(field(operations,"message")).contains("已完成");}
    private void awaitOperations()throws Exception{awaitOperations(25000);}
    private void awaitOperations(long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;do{runOnMainSync(()->{try{invoke(operations,"query",new Class<?>[0]);}catch(Exception error){throw new IllegalStateException(error);}});SystemClock.sleep(70);if(!(Boolean)invoke(operations,"pending",new Class<?>[0]))return;}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("OPERATION_UNCONFIRMED:"+field(operations,"message"));}
}
