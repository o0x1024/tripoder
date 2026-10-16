package dev.tripoder.controltest;

import android.content.Intent;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.util.*;
import org.json.*;

/** Real tap, long-press, launch, space selection and deletion of an owned standalone APK. */
public final class AppActionsUiIntegration extends WorkbenchUiTestBase {
    private static final String PACKAGE="dev.tripoder.launcherfixture";
    private Object app,projects,operations;private IBinder session;private String spaceA,spaceB,generationA,generationB;
    @Override void test()throws Exception{
        report.put("scope","native-application-tap-long-press-and-deletion");isolateOperationJournal();startThroughWorkbench(false);
        app=getTargetContext().getApplicationContext();session=(IBinder)field(field(app,"engine"),"session");projects=field(app,"projects");operations=field(app,"operations");
        JSONArray prior=new JSONArray(execute(session,"catalog").getString("installs"));
        for(int i=0;i<prior.length();i++){JSONObject value=prior.getJSONObject(i);if(PACKAGE.equals(value.getString("package"))&&value.getString("space").matches("tap-[ab]-[a-f0-9]{16}"))execute(session,"uninstallApplication","operation",id(),"space",value.getString("space"),"package",PACKAGE,"generation",value.getString("generation"));}
        spaceA="tap-a-"+run.substring(8);spaceB="tap-b-"+run.substring(8);
        execute(session,"createSpace","operation",id(),"space",spaceA);execute(session,"createSpace","operation",id(),"space",spaceB);
        importApk("launcher-guest.apk",spaceA);generationA=install(spaceA,PACKAGE).getString("generation");
        int projectCount=((List<?>)invoke(projects,"all",new Class<?>[0])).size();String projectRefs=execute(session,"catalog").getString("projects");
        home();appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);waitNode("进入项目空间",false);waitNode("删除应用",false);capture("long-press-menu");
        check("long-press-does-not-launch",getUiAutomation().getRootInActiveWindow().getPackageName().toString().equals("dev.tripoder.workbench")&&!(Boolean)invoke(operations,"pending",new Class<?>[0]),"management menu without a launch operation");
        click("进入项目空间");waitNode("＋ 新建项目",false);boolean bound=false;JSONArray initialBindings=new JSONArray(projectRefs);for(int i=0;i<initialBindings.length();i++)if(generationA.equals(initialBindings.getJSONObject(i).getString("generation")))bound=true;
        check("new-installation-has-no-project-binding",!bound,"older retained projects do not bind this new installation");capture("project-space");clickBack();
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(300);if(find(getUiAutomation().getRootInActiveWindow(),"选择运行空间",false)!=null)click(spaceA+" · 版本 1");waitGuest();check("tap-launches-without-a-project",true,"standalone launcher in engine task");capture("direct-launch");
        click("＋  计数一次");waitNode("已保存到当前空间",false);returnFromGuest();
        check("direct-launch-never-creates-or-binds-project",projectCount==((List<?>)invoke(projects,"all",new Class<?>[0])).size()&&projectRefs.equals(execute(session,"catalog").getString("projects")),"project records unchanged");
        check("first-launch-has-durable-success",lastSucceeded(),"gateway completion queried on return");
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("repeat-tap-keeps-space-data",find(getUiAutomation().getRootInActiveWindow(),"1",false)!=null,"count = 1");returnFromGuest();
        // A retained script proves uninstall invalidates binding without deleting projects.
        String project=(String)invoke(projects,"create",new Class<?>[]{String.class},"保留脚本 "+run.substring(8,14));
        invoke(projects,"script",new Class<?>[]{String.class,String.class},project,"// retained after app removal\n");
        execute(session,"bindProject","operation",id(),"project",project,"space",spaceA,"package",PACKAGE,"generation",generationA);
        importApk("launcher-guest.apk",spaceB);generationB=install(spaceB,PACKAGE).getString("generation");home();
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("选择运行空间");click(spaceB+" · 版本 1");
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("explicit-space-selection-uses-independent-data",find(getUiAutomation().getRootInActiveWindow(),"0",false)!=null,"space B count = 0");returnFromGuest();
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();check("selected-space-is-remembered",find(getUiAutomation().getRootInActiveWindow(),"0",false)!=null,"second tap opens space B directly");returnFromGuest();
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("删除应用");click(spaceA+" · 版本 1");waitNode("删除应用？",false);capture("delete-confirmation");click("取消");
        check("cancel-delete-preserves-install",install(spaceA,PACKAGE)!=null,"no delete submitted");
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("删除应用");click(spaceA+" · 版本 1");click("删除应用");awaitOperations();
        check("delete-removes-only-selected-install",install(spaceA,PACKAGE)==null&&generationB.equals(install(spaceB,PACKAGE).getString("generation")),"space A removed, space B unchanged");
        check("delete-preserves-script-and-marks-binding-stale",invoke(projects,"script",new Class<?>[]{String.class},project).equals("// retained after app removal\n")&&"STALE".equals(binding(project).getString("state")),"script retained; binding invalidated");
        home();check("remaining-space-keeps-one-app-tile",countApp(getUiAutomation().getRootInActiveWindow(),"应用："+PACKAGE+"，")==1,"one package tile remains");
        String stale=id();check("stale-delete-rejected",rejected(()->execute(session,"uninstallApplication","operation",stale,"space",spaceB,"package",PACKAGE,"generation",generationA),IllegalStateException.class,"INSTALL_GENERATION_CONFLICT"),"exact generation required");
        check("rejected-delete-has-terminal-receipt","FAILED".equals(execute(session,"query","operation",stale).getString("state")),"no unqueryable pending operation");
        appTile().performAction(AccessibilityNodeInfo.ACTION_CLICK);waitGuest();home();awaitOperations();
        appTile().performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("删除应用");SystemClock.sleep(150);if(find(getUiAutomation().getRootInActiveWindow(),"选择要删除的运行空间",false)!=null)click(spaceB+" · 版本 1");waitNode("删除应用？",false);click("删除应用");awaitOperations();home();
        check("delete-stops-background-app-and-removes-install",install(spaceB,PACKAGE)==null,new JSONObject().put("message",field(operations,"message")).put("resources",json(execute(session,"resources"))));
        boolean any=false;JSONArray remaining=new JSONArray(execute(session,"catalog").getString("installs"));for(int i=0;i<remaining.length();i++)if(PACKAGE.equals(remaining.getJSONObject(i).getString("package")))any=true;
        check("grid-reflects-remaining-installs-only",(findApp(getUiAutomation().getRootInActiveWindow(),"应用："+PACKAGE+"，")!=null)==any,"binding alone does not create an application tile");
        clickDescription("项目标签");waitNode("保留脚本 "+run.substring(8,14),false);capture("retained-project");
        check("deleted-app-project-remains-in-project-index",true,"project is independently accessible");
        // An exported arbitrary Activity is not a substitute for a real application launcher.
        importApk("preview-guest.apk",spaceA);String noLauncher=id(),previewGeneration=install(spaceA,"dev.tripoder.previewguest").getString("generation");
        check("no-launcher-does-not-start-arbitrary-activity",rejected(()->execute(session,"prepareApplicationLaunch","operation",noLauncher,"space",spaceA,"package","dev.tripoder.previewguest","generation",previewGeneration),IllegalStateException.class,"APPLICATION_HAS_NO_LAUNCHER"),"no fallback to arbitrary or host Activity");
        check("rejected-launch-has-terminal-receipt","FAILED".equals(execute(session,"query","operation",noLauncher).getString("state")),"launch rejected before worker startup");
        report.put("project",project).put("spaceA",spaceA).put("spaceB",spaceB).put("workerUid",execute(session,"status").getInt("engineUid")).put("guestLaunched",true).put("hostAppLaunched",false);
    }
    private void importApk(String asset,String space)throws Exception{
        File apk=new File(getTargetContext().getCacheDir(),run+"-"+asset);
        try(var in=getContext().getAssets().open(asset);var out=new FileOutputStream(apk)){in.transferTo(out);out.getFD().sync();}
        Object imports=field(app,"imports");runOnMainSync(()->{try{invoke(imports,"importUri",new Class<?>[]{android.net.Uri.class,String.class},android.net.Uri.fromFile(apk),space);}catch(Exception error){throw new IllegalStateException(error);}});
        long end=SystemClock.elapsedRealtime()+20000;while((Boolean)field(imports,"preparing")){if(SystemClock.elapsedRealtime()>end)throw new IllegalStateException("IMPORT_PREPARATION_TIMEOUT");SystemClock.sleep(50);}awaitOperations();if(!apk.delete())throw new IOException("FIXTURE_COPY_NOT_REMOVED");
    }
    private JSONObject install(String space,String pkg)throws Exception{JSONArray all=new JSONArray(execute(session,"catalog").getString("installs"));for(int i=0;i<all.length();i++){JSONObject value=all.getJSONObject(i);if(space.equals(value.getString("space"))&&pkg.equals(value.getString("package")))return value;}return null;}
    private JSONObject binding(String project)throws Exception{JSONArray all=new JSONArray(execute(session,"catalog").getString("projects"));for(int i=0;i<all.length();i++)if(project.equals(all.getJSONObject(i).getString("project")))return all.getJSONObject(i);throw new IllegalStateException("BINDING_MISSING");}
    private void home()throws Exception{startActivitySync(new Intent().setClassName("dev.tripoder.workbench","dev.tripoder.hooklab.WorkbenchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();getUiAutomation().waitForIdle(300,3000);click("⋮");click("刷新");waitNode("添加应用",true);SystemClock.sleep(550);}
    private AccessibilityNodeInfo appTile()throws Exception{getUiAutomation().waitForIdle(300,3000);SystemClock.sleep(350);long end=SystemClock.elapsedRealtime()+8000;do{var node=findApp(getUiAutomation().getRootInActiveWindow(),"应用："+PACKAGE+"，");if(node!=null)return node;SystemClock.sleep(60);}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("APPLICATION_TILE_MISSING");}
    private void waitGuest()throws Exception{try{waitNode("＋  计数一次",false);}catch(Exception error){report.put("launchUiStatus",field(operations,"message"));report.put("testJournal",new JSONObject(getTargetContext().getSharedPreferences("ui-acceptance-"+run,0).getAll()));report.put("launchResources",json(execute(session,"resources")));throw error;}String pkg=getUiAutomation().getRootInActiveWindow().getPackageName().toString();if(!pkg.equals("dev.tripoder.engine"))throw new IllegalStateException("GUEST_WINDOW_NOT_ENGINE:"+pkg);}
    private void returnFromGuest()throws Exception{scrollTo("完成并返回 Tripoder");click("完成并返回 Tripoder");waitNode("添加应用",true);awaitOperations();}
    private boolean lastSucceeded()throws Exception{return String.valueOf(field(operations,"message")).contains("已完成");}
    private void awaitOperations()throws Exception{long end=SystemClock.elapsedRealtime()+25000;do{runOnMainSync(()->{try{invoke(operations,"query",new Class<?>[0]);}catch(Exception error){throw new IllegalStateException(error);}});SystemClock.sleep(70);if(!(Boolean)invoke(operations,"pending",new Class<?>[0]))return;}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("OPERATION_UNCONFIRMED:"+field(operations,"message"));}
}
