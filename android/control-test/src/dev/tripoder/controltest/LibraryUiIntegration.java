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

/** Native one-application-to-many-project acceptance using the owned counter APK. */
public final class LibraryUiIntegration extends WorkbenchUiTestBase {
    private Object app,projects;private IBinder session;private String space,install;
    @Override void test()throws Exception{
        report.put("scope","native-application-library-and-one-to-many-projects");isolateOperationJournal();startThroughWorkbench(false);
        app=getTargetContext().getApplicationContext();session=(IBinder)field(field(app,"engine"),"session");projects=field(app,"projects");
        // Earlier runs' spaces go entirely; left behind, they crowd the space choice this test reads.
        JSONArray prior=new JSONArray(execute(session,"catalog").getString("spaces"));
        for(int i=0;i<prior.length();i++)if(prior.getString(i).matches("library-[a-f0-9]{16}")){JSONArray dropped=new JSONArray(execute(session,"deleteSpace","operation",id(),"space",prior.getString(i)).getString("deletedProjects"));for(int j=0;j<dropped.length();j++)invoke(projects,"delete",new Class<?>[]{String.class},dropped.getString(j));}
        space="library-"+run.substring(8);execute(session,"createSpace","operation",id(),"space",space);
        JSONObject fixtures;try(var in=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        File apk=new File(getTargetContext().getCacheDir(),run+"-counter.apk");
        try(var in=getContext().getAssets().open("preview-guest.apk");var out=new FileOutputStream(apk)){in.transferTo(out);out.getFD().sync();}
        int before=((List<?>)invoke(projects,"all",new Class<?>[0])).size();Object imports=field(app,"imports");
        runOnMainSync(()->{try{invoke(imports,"importUri",new Class<?>[]{android.net.Uri.class,String.class},android.net.Uri.fromFile(apk),space);}catch(Exception error){throw new IllegalStateException(error);}});
        long importDeadline=SystemClock.elapsedRealtime()+20000;
        while((Boolean)field(imports,"preparing")||(Boolean)invoke(field(app,"operations"),"pending",new Class<?>[0])){if(SystemClock.elapsedRealtime()>importDeadline)throw new IllegalStateException("IMPORT_NOT_CONFIRMED");SystemClock.sleep(50);}
        install=new JSONObject(findInstall()).getString("generation");if(!apk.delete())throw new IOException("FIXTURE_COPY_NOT_REMOVED");
        check("apk-import-does-not-create-project",before==((List<?>)invoke(projects,"all",new Class<?>[0])).size(),"application import independent of project creation");
        check("import-copies-cleaned-after-receipt",((File)field(imports,"root")).list().length==0,"temporary APK descriptors and files released");
        Bundle presentation=execute(session,"appPresentation","space",space,"package","dev.tripoder.previewguest","generation",install);
        check("presentation-reads-original-apk-label",!presentation.getString("label").equals("dev.tripoder.previewguest"),presentation.getString("label"));
        check("presentation-rejects-stale-install",rejected(()->execute(session,"appPresentation","space",space,"package","dev.tripoder.previewguest","generation",id()),IllegalStateException.class,"STALE_INSTALL"),"generation checked");
        getTargetContext().startActivity(new Intent().setComponent(engineComponent("dev.tripoder.hooklab.WorkbenchActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        clickDescription("更多");click("刷新");waitNode("添加应用",true);SystemClock.sleep(700);
        AccessibilityNodeInfo tile=findPackage(getUiAutomation().getRootInActiveWindow(),"dev.tripoder.previewguest");if(tile==null)throw new IllegalStateException("APP_TILE_MISSING");
        check("home-separates-apps-from-projects",find(getUiAutomation().getRootInActiveWindow(),"＋ 新建项目",false)==null,"home is app grid");
        check("one-tile-per-package-across-spaces",countPackage(getUiAutomation().getRootInActiveWindow(),"dev.tripoder.previewguest")==1,"one application tile");
        capture("application-grid");tile.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);click("查看项目");waitNode("＋ 新建项目",false);
        String firstName="UI 项目 A "+run.substring(8,14),secondName="UI 项目 B "+run.substring(8,14);
        create(firstName);String first=projectId(firstName);
        AccessibilityNodeInfo editor=waitNode("项目脚本编辑器",true);Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"// only project A\n");editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text);scrollTo("保存脚本 · 有未保存修改");click("保存脚本 · 有未保存修改");awaitOperations();clickBack();
        create(secondName);String second=projectId(secondName);awaitOperations();
        Bundle a=execute(session,"resolveProject","project",first),b=execute(session,"resolveProject","project",second);
        check("two-projects-bind-one-application",!first.equals(second)&&a.getString("package").equals(b.getString("package"))&&install.equals(a.getString("generation"))&&install.equals(b.getString("generation")),new JSONObject().put("first",first).put("second",second).put("package",a.getString("package")));
        check("scripts-remain-independent",invoke(projects,"script",new Class<?>[]{String.class},first).equals("// only project A\n")&&!invoke(projects,"script",new Class<?>[]{String.class},second).equals("// only project A\n"),"editing project A leaves project B unchanged");
        clickBack();scrollTo(secondName);check("application-page-lists-both-projects",find(getUiAutomation().getRootInActiveWindow(),firstName,false)!=null&&find(getUiAutomation().getRootInActiveWindow(),secondName,false)!=null,"both project rows");capture("application-projects");clickBack();
        clickDescription("项目标签");scrollTo(secondName);check("global-project-index-shows-new-projects",find(getUiAutomation().getRootInActiveWindow(),secondName,false)!=null,"global index");capture("project-index");clickDescription("应用标签");clickDescription("添加应用");
        waitNode("手机应用",false);waitNode("APK 文件",false);check("picker-install-disabled-without-selection",!waitNode("安装所选应用",true).isEnabled(),"disabled action");
        long deadline=SystemClock.elapsedRealtime()+10000;AccessibilityNodeInfo choice=null;while(choice==null&&SystemClock.elapsedRealtime()<deadline){choice=findAnyPackage(getUiAutomation().getRootInActiveWindow());SystemClock.sleep(60);}
        if(choice==null)throw new IllegalStateException("PHONE_APP_LIST_EMPTY");choice.performAction(AccessibilityNodeInfo.ACTION_CLICK);SystemClock.sleep(300);check("picker-selection-enables-install",waitNode("安装所选应用",true).isEnabled()&&findPrefix(getUiAutomation().getRootInActiveWindow(),"安装「")!=null,"one selection names the app");capture("add-app-selection");
        click("APK 文件");waitNode("选择 APK 文件",false);check("apk-source-has-explicit-entry",true,"file source tab");clickBack();
        report.put("projectA",first).put("projectB",second).put("space",space).put("workerUid",execute(session,"status").getInt("engineUid")).put("guestLaunched",false).put("hostAppLaunched",false);
    }
    private void create(String name)throws Exception{
        scrollTop();click("＋ 新建项目");SystemClock.sleep(200);var target=find(getUiAutomation().getRootInActiveWindow(),"新项目名称",true);if(target==null){String suffix="\n空间："+space+" · 版本 "+new JSONObject(findInstall()).getLong("version");var choice=findSuffix(getUiAutomation().getRootInActiveWindow(),suffix);if(choice==null)throw new IllegalStateException("INSTALL_CHOICE_MISSING:"+space);click(choice.getText().toString());}
        var input=waitNode("新项目名称",true);Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name);input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value);click("创建项目");waitNode("项目脚本编辑器",true);awaitOperations();
    }
    private String findInstall()throws Exception{JSONArray installs=new JSONArray(execute(session,"catalog").getString("installs"));for(int i=0;i<installs.length();i++)if(space.equals(installs.getJSONObject(i).getString("space")))return installs.getJSONObject(i).toString();throw new IllegalStateException("INSTALL_MISSING");}
    private String projectId(String name)throws Exception{for(Object item:(List<?>)invoke(projects,"all",new Class<?>[0])){JSONObject value=(JSONObject)item;if(name.equals(value.getString("name")))return value.getString("id");}throw new IllegalStateException("PROJECT_MISSING");}
    private void awaitOperations()throws Exception{Object operations=field(app,"operations");long end=SystemClock.elapsedRealtime()+18000;do{SystemClock.sleep(50);if(!(Boolean)invoke(operations,"pending",new Class<?>[0]))return;}while(SystemClock.elapsedRealtime()<end);throw new IllegalStateException("OPERATION_UNCONFIRMED");}
}
