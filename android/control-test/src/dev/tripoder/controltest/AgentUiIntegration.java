package dev.tripoder.controltest;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Desktop MCP performs all Hook operations with no key, pairing or approval. */
public final class AgentUiIntegration extends ControlTestBase {
    private IBinder controller;private Object app,agents;private String project,process,binding,install;
    public static final String SOURCE="import { definePlan } from '@abox/hook';\nexport default definePlan({\n  id: 'counter-mcp', target, params: {},\n  setup(ctx) {\n    ctx.java.method('dev.tripoder.previewguest.CounterActivity', 'formatCount', ['int'])\n      .replace(({args}) => 'Hook ' + args[0]);\n  }\n});\n";
    @Override void test()throws Exception{
        report.put("scope","open-mcp-owned-counter-native-ui").put("phoneApprovalPerformed",false).put("pairingPerformed",false);
        WorkbenchUiTestBase.isolateOperationJournal(this);startThroughWorkbench(false);
        app=getTargetContext().getApplicationContext();controller=(IBinder)field(field(app,"engine"),"session");agents=field(app,"agents");Object projects=field(app,"projects");
        cleanupOwnedFixtures(projects);
        String space=run+"-agent";execute(controller,"createSpace","operation",id(),"space",space);
        JSONObject fixtures;try(var in=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        File apk=new File(getTargetContext().getCacheDir(),run+"-counter.apk");try(var in=getContext().getAssets().open("preview-guest.apk");var out=new FileOutputStream(apk)){in.transferTo(out);out.getFD().sync();}
        try(var fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle request=ControlWire.request("install","space",space,"operation",id());request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getString("previewSha256"))));request.putParcelable("fd0",fd);install=ControlWire.call(controller,ControlWire.EXECUTE,request).getString("generation");
        }finally{if(!apk.delete())throw new IOException("TEST_APK_COPY_NOT_REMOVED");}
        Bundle applicationTarget=applicationProject(controller,projects,space,"dev.tripoder.previewguest",install);project=applicationTarget.getString("project");binding=applicationTarget.getString("bindingOperation");
        String originalScript=(String)invoke(projects,"script",new Class<?>[]{String.class},project);
        String launchOperation=id();Bundle prepared=execute(controller,"prepareLaunch","operation",launchOperation,"project",project,"bindingOperation",binding,"generation",install,"component","dev.tripoder.previewguest.CounterActivity");process=prepared.getString("generation");Bundle worker=awaitWorker(controller,project,prepared);
        report.put("workerPid",worker.getInt("pid")).put("workerUid",worker.getInt("uid")).put("project",project).put("space",space).put("processGeneration",process).put("installGeneration",install).put("ownedApkSha256",fixtures.getString("previewSha256")).put("apkRewritten",false);

        awaitEndpoint();check("endpoint-starts-without-phone-pairing",true,"automatic loopback endpoint");
        signal("ready",new JSONObject().put("project",project).put("processGeneration",process).put("pid",worker.getInt("pid")).put("source",SOURCE));
        awaitSignal("active");runOnMainSync(()->getTargetContext().startActivity(prepared.getParcelable("intent",Intent.class)));
        waitText("Hook 0");check("desktop-activates-without-phone-review",true,"actual guest rendered Hook 0");signal("visible",new JSONObject());
        awaitSignal("hooked");waitText("Hook 1");check("duplicate-operation-clicks-once",true,"counter remains Hook 1");signal("hooked-ui",new JSONObject());
        awaitSignal("disabled");waitText("2");check("explicit-disable-restores-real-behavior",true,"same Activity renders 2");signal("restored",new JSONObject());
        awaitSignal("finished");JSONArray live=new JSONArray(execute(controller,"hookProcesses","project",project).getString("processes"));boolean alive=false;
        for(int i=0;i<live.length();i++)if(process.equals(live.getJSONObject(i).getString("processGeneration"))&&worker.getInt("pid")==live.getJSONObject(i).getInt("pid"))alive=true;
        check("desktop-close-keeps-exact-worker-running",alive,worker.getInt("pid"));
        check("desktop-draft-does-not-overwrite-project-source",originalScript.equals(invoke(projects,"script",new Class<?>[]{String.class},project)),true);
        report.put("authenticationRequired",false).put("runtimeMode","thin");
        execute(controller,"deleteSpace","operation",id(),"space",space);
    }
    private void cleanupOwnedFixtures(Object projects)throws Exception{
        File[] files=getTargetContext().getExternalFilesDir(null).listFiles();if(files==null)return;
        JSONArray spaces=new JSONArray(execute(controller,"catalog").getString("spaces"));Set<String> existing=new HashSet<>();for(int i=0;i<spaces.length();i++)existing.add(spaces.getString(i));
        for(File file:files){
            if(!file.getName().matches("control-[a-f0-9]{16}-open-ready\\.json"))continue;
            JSONObject fixture=new JSONObject(java.nio.file.Files.readString(file.toPath()));if(!fixture.has("source"))continue;
            String oldRun=file.getName().substring(0,24),oldProject=fixture.getString("project");
            File metadata=new File(getTargetContext().getFilesDir(),"projects/"+oldProject+"/project.json");if(!metadata.isFile())continue;
            JSONObject value=(JSONObject)invoke(projects,"get",new Class<?>[]{String.class},oldProject);
            if(!value.getString("name").equals("MCP 真机验收 "+oldRun.substring(8)))throw new IllegalStateException("OWNED_FIXTURE_NAME_CHANGED");
            if(existing.contains(oldRun+"-agent"))execute(controller,"deleteSpace","operation",id(),"space",oldRun+"-agent");

        }
    }
    private void awaitEndpoint()throws Exception{long end=SystemClock.elapsedRealtime()+10000;while(!(Boolean)field(agents,"listening")&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);if(!(Boolean)field(agents,"listening"))throw new IllegalStateException("OPEN_ENDPOINT_NOT_STARTED");}
    private void awaitSignal(String step)throws Exception{File file=new File(getTargetContext().getExternalFilesDir(null),run+"-open-"+step+".json");long end=SystemClock.elapsedRealtime()+90000;while(!file.isFile()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);if(!file.isFile())throw new IllegalStateException("DESKTOP_SIGNAL_MISSING:"+step);}
    private void signal(String step,JSONObject value)throws Exception{android.util.AtomicFile file=new android.util.AtomicFile(new File(getTargetContext().getExternalFilesDir(null),run+"-open-"+step+".json"));FileOutputStream out=file.startWrite();try{out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){file.failWrite(out);throw e;}}
    private void waitText(String value)throws Exception{long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){if(find(getUiAutomation().getRootInActiveWindow(),value))return;SystemClock.sleep(50);}throw new IllegalStateException("GUEST_TEXT_MISSING:"+value);}
    private static boolean find(AccessibilityNodeInfo node,String value){if(node==null)return false;if(node.getText()!=null&&value.contentEquals(node.getText()))return true;for(int i=0;i<node.getChildCount();i++)if(find(node.getChild(i),value))return true;return false;}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...args)throws Exception{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,args);}
    private static String id(){return UUID.randomUUID().toString();}
}
