package dev.tripoder.controltest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Agent uses only public MCP primitives; phone checks actual Application/UI. */
public final class CodeProbeIntegration extends ControlTestBase {
    private IBinder controller;private Object projects;private String project,space,install,binding;
    @Override void test()throws Exception{
        WorkbenchUiTestBase.isolateOperationJournal(this);startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();controller=(IBinder)field(field(app,"engine"),"session");projects=field(app,"projects");
        cleanupOwnedFixtures();
        space=run+"-code";execute(controller,"createSpace","operation",id(),"space",space);
        JSONObject fixtures;try(var in=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        File apk=new File(getTargetContext().getCacheDir(),run+"-code.apk");try(var in=getContext().getAssets().open("code-probe-guest.apk");var out=new FileOutputStream(apk)){in.transferTo(out);out.getFD().sync();}
        try(var fd=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle request=ControlWire.request("install","space",space,"operation",id());request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getString("probeSha256"))));request.putParcelable("fd0",fd);install=ControlWire.call(controller,ControlWire.EXECUTE,request).getString("generation");
        }finally{apk.delete();}
        project=(String)invoke(projects,"create",new Class<?>[]{String.class},"Code probe fixture "+run);binding=id();execute(controller,"bindProject","operation",binding,"project",project,"space",space,"package","dev.tripoder.probefixture","generation",install);
        long before=SystemClock.elapsedRealtime();Bundle baseline=execute(controller,"prepareLaunch","operation",id(),"project",project,"bindingOperation",binding,"generation",install,"component","dev.tripoder.probefixture.ProbeActivity");Bundle worker=awaitWorker(controller,project,baseline);
        report.put("baselineLaunchMs",SystemClock.elapsedRealtime()-before);runOnMainSync(()->getTargetContext().startActivity(baseline.getParcelable("intent",Intent.class)));waitText("startup=original memory=dynamic-v1 file=dynamic-v1 error=");check("ordinary-thin-application-and-dynamic-loaders-work",true,"actual owned UI");
        signal("ready",new JSONObject().put("project",project).put("processGeneration",baseline.getString("generation")).put("pid",worker.getInt("pid")).put("payloadSha256",fixtures.getString("payloadSha256")));
        awaitSignal("baseline-done");stop();signal("code-ready",new JSONObject().put("project",project));
        awaitSignal("started");waitText("startup=hooked-before-application memory=dynamic-v1 file=dynamic-v1 error=");check("startup-script-active-before-application-onCreate",true,"startup method returned replaced value");signal("visible",new JSONObject());
        awaitSignal("hooked");waitText("payload=hooked");check("explicit-dynamic-loader-hook-runs-on-real-call",true,"real in-memory class invocation");signal("hooked-ui",new JSONObject());
        awaitSignal("stop-for-artifact");stop();signal("stopped",new JSONObject());
        awaitSignal("restarted");waitText("startup=original memory=dynamic-v1 file=dynamic-v1 error=");check("disabled-probe-plan-restores-ordinary-startup",true,"new generation, original method");signal("restored",new JSONObject());
        awaitSignal("finished");stop();execute(controller,"deleteSpace","operation",id(),"space",space);invoke(projects,"delete",new Class<?>[]{String.class},project);
        report.put("scope","generic-code-probes-owned-application").put("pairingPerformed",false).put("phoneApprovalPerformed",false).put("runtimeMode","thin");
    }
    private void cleanupOwnedFixtures()throws Exception{
        JSONObject catalog=json(execute(controller,"catalog"));JSONArray spaceRows=new JSONArray(catalog.getString("spaces"));Set<String> spaces=new HashSet<>();for(int i=0;i<spaceRows.length();i++)spaces.add(spaceRows.getString(i));
        @SuppressWarnings("unchecked") List<JSONObject> existing=(List<JSONObject>)invoke(projects,"all",new Class<?>[]{});
        for(JSONObject value:existing){String name=value.getString("name");if(!name.matches("Code probe fixture control-[a-f0-9]{16}"))continue;String ownedRun=name.substring("Code probe fixture ".length());
            File proof=new File(getTargetContext().getExternalFilesDir(null),"progress-"+ownedRun+".json");if(!proof.isFile())continue;
            JSONObject progress=new JSONObject(java.nio.file.Files.readString(proof.toPath()));if(!ownedRun.equals(progress.getString("runId")))throw new IllegalStateException("OWNED_FIXTURE_RUN_MISMATCH");
            if(spaces.contains(ownedRun+"-code"))execute(controller,"deleteSpace","operation",id(),"space",ownedRun+"-code");invoke(projects,"delete",new Class<?>[]{String.class},value.getString("id"));
        }
    }
    private void stop()throws Exception{execute(controller,"stopApplication","operation",id(),"space",space,"package","dev.tripoder.probefixture","generation",install);runOnMainSync(()->getTargetContext().startActivity(new Intent().setClassName(getTargetContext(),"dev.tripoder.hooklab.WorkbenchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));}
    private void signal(String step,JSONObject value)throws Exception{android.util.AtomicFile file=new android.util.AtomicFile(new File(getTargetContext().getExternalFilesDir(null),run+"-open-"+step+".json"));FileOutputStream out=file.startWrite();try{out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){file.failWrite(out);throw e;}}
    private void awaitSignal(String step){File file=new File(getTargetContext().getExternalFilesDir(null),run+"-open-"+step+".json");long end=SystemClock.elapsedRealtime()+120000;while(!file.isFile()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);if(!file.isFile())throw new IllegalStateException("DESKTOP_SIGNAL_MISSING:"+step);}
    private void waitText(String text){long end=SystemClock.elapsedRealtime()+12000;String observed="";while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(find(root,text))return;String actual=fixtureText(root);if(!actual.isEmpty())observed=actual;SystemClock.sleep(50);}throw new IllegalStateException("FIXTURE_TEXT_MISSING:"+text+";actual="+observed);}
    private static String fixtureText(AccessibilityNodeInfo node){if(node==null)return "";if(node.getText()!=null&&node.getText().toString().startsWith("startup="))return node.getText().toString();for(int i=0;i<node.getChildCount();i++){String text=fixtureText(node.getChild(i));if(!text.isEmpty())return text;}return "";}
    private static boolean find(AccessibilityNodeInfo node,String text){if(node==null)return false;if(node.getText()!=null&&text.contentEquals(node.getText()))return true;for(int i=0;i<node.getChildCount();i++)if(find(node.getChild(i),text))return true;return false;}
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...args)throws Exception{Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);}
    private static String id(){return UUID.randomUUID().toString();}
}
