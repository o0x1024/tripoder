package dev.tripoder.controltest;

import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.tripoder.control.ControlWire;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real native UI observes the public device control handler; stdio and Hook behavior have a separate acceptance run. */
public final class GlobalControlUiIntegration extends WorkbenchUiTestBase {
    private Object app,agents;private IBinder controller;
    @Override void test()throws Exception{
        report.put("scope","global-control-invalidates-native-ui-and-preserves-dirty-editor");isolateOperationJournal();startThroughWorkbench(false);
        app=getTargetContext().getApplicationContext();agents=field(app,"agents");controller=(IBinder)field(field(app,"engine"),"session");
        long deadline=SystemClock.elapsedRealtime()+10000;while(!(Boolean)field(agents,"listening")){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("AGENT_ENDPOINT_NOT_READY");SystemClock.sleep(30);}
        waitNode("应用标签",true);check("navigation-has-no-project-or-workspace-tab",find(getUiAutomation().getRootInActiveWindow(),"项目标签",true)==null&&find(getUiAutomation().getRootInActiveWindow(),"工作区",false)==null,true);
        String space="ui-global-"+run.substring(8),pkg="dev.tripoder.launcherfixture";
        JSONArray current=wire("applications.list",new JSONObject()).getJSONArray("applications");for(int i=0;i<current.length();i++)if(pkg.equals(current.getJSONObject(i).getString("package")))throw new IllegalStateException("OWNED_FIXTURE_MUST_BE_ABSENT");
        wire("space.create",new JSONObject().put("space",space).put("name","UI Global"));
        JSONObject fixtures;try(var input=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        File apk=new File(getTargetContext().getCacheDir(),run+"-launcher.apk");try(var input=getContext().getAssets().open("launcher-guest.apk");var output=new FileOutputStream(apk)){input.transferTo(output);output.getFD().sync();}
        Bundle installed;
        try(var descriptor=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle request=ControlWire.request("install","operation",id(),"space",space);request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getString("launcherSha256"))));request.putParcelable("fd0",descriptor);installed=ControlWire.call(controller,ControlWire.EXECUTE,request);
        }finally{apk.delete();}
        JSONObject original=wire("project.get",new JSONObject().put("package",pkg));
        clickDescription("更多");click("刷新");waitTile(pkg,true);findPackage(getUiAutomation().getRootInActiveWindow(),pkg).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);waitNode("应用分析",false);click("应用分析");waitNode("项目脚本编辑器",true);
        check("application-opens-its-single-analysis-page",find(getUiAutomation().getRootInActiveWindow(),"＋ 新建项目",false)==null,true);
        JSONObject first=wire("project.scriptPut",new JSONObject().put("package",pkg).put("expectedSha256",original.getString("sourceSha256")).put("source","// changed by desktop\n"));
        waitEditor("// changed by desktop\n");check("external-save-refreshes-clean-editor",true,first.getString("sourceSha256"));
        AccessibilityNodeInfo editor=waitNode("项目脚本编辑器",true);Bundle local=new Bundle();local.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"// local unsaved\n");if(!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,local))throw new IllegalStateException("EDITOR_CHANGE_REJECTED");
        JSONObject second=wire("project.scriptPut",new JSONObject().put("package",pkg).put("expectedSha256",first.getString("sourceSha256")).put("source","// newer remote\n"));
        SystemClock.sleep(500);waitEditor("// local unsaved\n");check("external-save-preserves-dirty-editor",true,second.getString("sourceSha256"));
        click("保存脚本 · 有未保存修改");waitNode("脚本没有保存",false);check("dirty-editor-detects-external-source-conflict",wire("project.get",new JSONObject().put("package",pkg)).getString("source").equals("// newer remote\n"),true);click("知道了");
        clickBack();click("放弃修改");waitTile(pkg,true);capture("global-ui-before-delete");
        JSONObject target=new JSONObject().put("space",space).put("package",pkg).put("installGeneration",installed.getString("generation"));
        wire("application.delete",new JSONObject().put("package",pkg).put("installations",new JSONArray().put(target)));
        waitTile(pkg,false);check("external-delete-refreshes-app-grid-without-manual-refresh",true,pkg);capture("global-ui-after-delete");
        wire("space.delete",new JSONObject().put("space",space));clickDescription("设置标签");waitNode("外部 Agent",false);check("mcp-entry-is-global-in-settings",find(getUiAutomation().getRootInActiveWindow(),"外部 Agent 连接状态",true)!=null,true);capture("global-control-settings");
        report.put("sourceProject",original.getString("project")).put("space",space).put("workerUid",android.os.Process.myUid()).put("guestLaunched",false);
    }
    private JSONObject wire(String method,JSONObject params)throws Exception{
        JSONObject request=new JSONObject().put("protocol",2).put("requestId",id()).put("method",method).put("params",params),reply=(JSONObject)invoke(agents,"wire",new Class<?>[]{JSONObject.class},request);
        if(!reply.getBoolean("ok"))throw new IllegalStateException(reply.toString());return reply.getJSONObject("result");
    }
    private void waitEditor(String expected)throws Exception{long deadline=SystemClock.elapsedRealtime()+10000;do{var node=find(getUiAutomation().getRootInActiveWindow(),"项目脚本编辑器",true);if(node!=null&&expected.contentEquals(node.getText()==null?"":node.getText()))return;SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("EDITOR_SOURCE_NOT_UPDATED");}
    private void waitTile(String pkg,boolean present)throws Exception{long deadline=SystemClock.elapsedRealtime()+10000;do{if((findPackage(getUiAutomation().getRootInActiveWindow(),pkg)!=null)==present)return;SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("APPLICATION_TILE_STATE_MISMATCH:"+present);}
}
