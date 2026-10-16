package dev.tripoder.controltest;

import android.app.*;
import android.content.*;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Starts the pinned original target APK through the production project ingress. No account actions. */
public final class TargetLaunchIntegration extends ControlTestBase {
    private static final String SPACE="p6-20261014";
    @Override void test()throws Exception{
        String target=args.getString("target");boolean wechat="wechat".equals(target);
        if(!wechat&&!"honor".equals(target))throw new IllegalArgumentException("TARGET_REQUIRED");
        String pkg=wechat?"com.tencent.mm":"com.tencent.tmgp.sgame";
        String expected=wechat?"f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d":"6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384";
        report.put("scope","original-target-first-launch-only").put("target",pkg).put("apkSha256",expected).put("apkRewritten",false).put("loginAttempted",false).put("businessScenarioVerified",false);
        startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();IBinder session=(IBinder)field(field(app,"engine"),"session");Object projects=field(app,"projects");
        File source=new File(getTargetContext().getExternalFilesDir(null),"acceptance/"+target+".apk");check("original-apk-digest-matches",expected.equals(digest(source)),expected);
        Bundle catalog=execute(session,"catalog");JSONArray spaces=new JSONArray(catalog.getString("spaces"));boolean exists=false;for(int i=0;i<spaces.length();i++)if(SPACE.equals(spaces.getString(i)))exists=true;
        if(!exists)execute(session,"createSpace","space",SPACE,"operation",id());
        File recordFile=new File(getTargetContext().getFilesDir(),"acceptance-"+target+".json");JSONObject record;
        if(recordFile.exists()){
            record=new JSONObject(java.nio.file.Files.readString(recordFile.toPath()));
            if(!expected.equals(record.getString("sha256")))throw new IllegalStateException("TARGET_RECEIPT_DIGEST_CHANGED");
            Bundle resolved=execute(session,"resolveProject","project",record.getString("project"));
            if(!SPACE.equals(resolved.getString("space"))||!pkg.equals(resolved.getString("package"))||!record.getString("generation").equals(resolved.getString("generation"))||!record.getString("binding").equals(resolved.getString("bindingOperation")))throw new IllegalStateException("TARGET_BINDING_CHANGED");
        }else{
            JSONArray installed=new JSONArray(catalog.getString("installs"));for(int i=0;i<installed.length();i++){JSONObject item=installed.getJSONObject(i);if(SPACE.equals(item.getString("space"))&&pkg.equals(item.getString("package")))throw new IllegalStateException("TARGET_INSTALL_WITHOUT_ACCEPTANCE_RECEIPT");}
            String operation=id();Bundle installedResult;
            try(var fd=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)){
                Bundle request=ControlWire.request("install","space",SPACE,"operation",operation);request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(expected)));request.putParcelable("fd0",fd);
                installedResult=ControlWire.call(session,ControlWire.EXECUTE,request);
            }
            check("target-install-committed","SUCCEEDED".equals(execute(session,"query","operation",operation).getString("state")),json(installedResult));
            String project=(String)invoke(projects,"create",new Class<?>[]{String.class},wechat?"微信 · 真机验收":"王者荣耀 · 真机验收"),binding=id(),generation=installedResult.getString("generation");
            execute(session,"bindProject","operation",binding,"project",project,"space",SPACE,"package",pkg,"generation",generation);
            record=new JSONObject().put("project",project).put("binding",binding).put("generation",generation).put("sha256",expected);
            android.util.AtomicFile atomic=new android.util.AtomicFile(recordFile);FileOutputStream out=atomic.startWrite();try{out.write(record.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception error){atomic.failWrite(out);throw error;}
        }
        report.put("targetReceipt",record).put("space",SPACE);
        if(args.containsKey("closeActivityTicket"))for(String ticket:args.getString("closeActivityTicket").split(",",-1)){
            UUID.fromString(ticket);JSONArray entries=new JSONArray(execute(session,"launchEntries","project",record.getString("project")).getString("entries"));String selected=null;
            for(int i=0;i<entries.length();i++){JSONObject item=entries.getJSONObject(i);if(ticket.equals(item.getString("ticket"))){if(selected!=null||!"ACTIVITY_ROUTE".equals(item.optString("kind")))throw new IllegalStateException("EXACT_ACTIVITY_ROUTE_REQUIRED");selected=item.getString("operation");}}
            if(selected==null)throw new IllegalStateException("EXACT_ACTIVITY_ROUTE_NOT_ELIGIBLE");
            boolean privateQuery=false;try{execute(session,"query","operation",selected);}catch(RuntimeException error){privateQuery=String.valueOf(error.getMessage()).contains("OPERATION_OWNER_MISMATCH");}check("internal-operation-generic-query-remains-owner-bound",privateQuery,ticket);
            boolean rejected=false;try{execute(session,"closeLaunchEntry","operation",id(),"project",record.getString("project"),"bindingOperation",id(),"generation",record.getString("generation"),"launchOperation",selected);}catch(RuntimeException error){rejected=String.valueOf(error.getMessage()).contains("LAUNCH_ENTRY_BINDING_MISMATCH");}
            check("internal-entry-wrong-binding-rejected",rejected,ticket);closePriorEntry(session,record,selected);
        }
        if(args.containsKey("closeLaunchEntry"))closePriorEntry(session,record,args.getString("closeLaunchEntry"));
        JSONArray targets=new JSONArray(execute(session,"launchTargets","project",record.getString("project")).getString("targets"));JSONObject launcher=null;
        for(int i=0;i<targets.length();i++)if(targets.getJSONObject(i).getBoolean("launcher")){if(launcher!=null)throw new IllegalStateException("MULTIPLE_LAUNCHERS_REQUIRE_SELECTION");launcher=targets.getJSONObject(i);}
        if(launcher==null)throw new IllegalStateException("TARGET_LAUNCHER_MISSING");report.put("launcher",launcher);
        check("declared-launcher-policy-supported",launcher.getBoolean("supported"),launcher);
        String launch=id();report.put("launchOperation",launch);Bundle prepared;
        try{prepared=execute(session,"prepareLaunch","operation",launch,"project",record.getString("project"),"bindingOperation",record.getString("binding"),"generation",record.getString("generation"),"component",launcher.getString("component"));}
        catch(Exception error){report.put("launchReceipt",json(execute(session,"query","operation",launch)));throw error;}
        report.put("workerPid",prepared.getInt("pid")).put("workerUid",prepared.getInt("uid")).put("processGeneration",prepared.getString("generation"));check("target-application-initialized",prepared.getInt("pid")>0&&prepared.getInt("uid")!=android.os.Process.myUid(),json(preparedWithoutCapability(prepared)));
        java.util.concurrent.atomic.AtomicReference<Activity> page=new java.util.concurrent.atomic.AtomicReference<>();Application application=(Application)app;
        Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().equals("dev.tripoder.hooklab.WorkbenchProjectActivity"))page.set(a);}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        application.registerActivityLifecycleCallbacks(observer);
        try{
            getTargetContext().startActivity(new Intent().setClassName("dev.tripoder.workbench","dev.tripoder.hooklab.WorkbenchProjectActivity").putExtra("project",record.getString("project")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline=SystemClock.elapsedRealtime()+8000;while(page.get()==null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(25);if(page.get()==null)throw new IllegalStateException("PROJECT_PAGE_NOT_CREATED");waitForIdleSync();
            runOnMainSync(()->page.get().startActivity(new Intent().setClassName("dev.tripoder.engine","dev.tripoder.runtime.RuntimeGuestGateway").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK).putExtra("operation",launch).putExtra("capability",prepared.getString("capability")),ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()));
            Bundle receipt;deadline=SystemClock.elapsedRealtime()+15000;do{receipt=execute(session,"query","operation",launch);if(!"RUNNING".equals(receipt.getString("state")))break;SystemClock.sleep(100);}while(SystemClock.elapsedRealtime()<deadline);
            report.put("launchReceipt",json(receipt));check("target-first-activity-acknowledged","SUCCEEDED".equals(receipt.getString("state")),json(receipt));
        }finally{application.unregisterActivityLifecycleCallbacks(observer);}
        JSONArray samples=new JSONArray();report.put("stabilitySamples",samples);long stableUntil=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<stableUntil){
            JSONArray live=new JSONArray(execute(session,"hookProcesses","project",record.getString("project")).getString("processes"));boolean originalAlive=false;
            for(int i=0;i<live.length();i++){JSONObject process=live.getJSONObject(i);if(prepared.getString("generation").equals(process.getString("processGeneration"))&&prepared.getInt("pid")==process.getInt("pid"))originalAlive=true;}
            samples.put(new JSONObject().put("time",System.currentTimeMillis()).put("originalAlive",originalAlive).put("processes",live));if(!originalAlive){check("target-original-worker-remains-running-for-30-seconds",false,live);break;}SystemClock.sleep(1000);
        }
        check("target-original-worker-remains-running-for-30-seconds",true,samples.length());
        android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot==null)throw new IllegalStateException("TARGET_SCREENSHOT_UNAVAILABLE");String capture=run+"-target-stable.png";try(var output=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),capture))){if(!screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))throw new IOException("TARGET_SCREENSHOT_FAILED");}finally{screenshot.recycle();}report.put("captures",new JSONArray().put(capture));
        check("original-apk-unchanged",expected.equals(digest(source)),expected);
    }
    private void closePriorEntry(IBinder session,JSONObject record,String prior)throws Exception{
        UUID.fromString(prior);
        JSONArray eligible=new JSONArray(execute(session,"launchEntries","project",record.getString("project")).getString("entries"));JSONObject selected=null;
        for(int i=0;i<eligible.length();i++)if(prior.equals(eligible.getJSONObject(i).getString("operation")))selected=eligible.getJSONObject(i);
        if(selected==null)throw new IllegalStateException("EXPLICIT_ENTRY_NOT_ELIGIBLE");
        Bundle original=new Bundle();original.putString("state",selected.getString("outcome"));original.putString("detail",selected.optString("detail",null));check("explicit-old-launch-is-unknown","UNKNOWN".equals(original.getString("state")),json(original));
        String closure=id();Bundle closed=execute(session,"closeLaunchEntry","operation",closure,"project",record.getString("project"),"bindingOperation",record.getString("binding"),"generation",record.getString("generation"),"launchOperation",prior);
        Bundle repeated=execute(session,"closeLaunchEntry","operation",closure,"project",record.getString("project"),"bindingOperation",record.getString("binding"),"generation",record.getString("generation"),"launchOperation",prior);
        check("entry-closure-idempotent-resources-retained",closed.getBoolean("entryClosed")&&!closed.getBoolean("resourcesRetired")&&selected.getString("ticket").equals(repeated.getString("ticket")),json(closed));
        Bundle old=execute(session,"entryClosureReceipt","operation",closure),receipt=execute(session,"query","operation",closure);
        check("entry-closure-keeps-original-unknown","UNKNOWN".equals(old.getString("state"))&&Objects.equals(original.getString("detail"),old.getString("detail"))&&"SUCCEEDED".equals(receipt.getString("state")),json(old));
        if(!report.has("entryClosures"))report.put("entryClosures",new JSONArray());report.getJSONArray("entryClosures").put(new JSONObject().put("prior",json(original)).put("eligible",selected).put("closed",json(closed)).put("receipt",json(receipt)).put("resources",json(execute(session,"resources"))));
    }
    private static Bundle preparedWithoutCapability(Bundle value){Bundle copy=new Bundle(value);copy.remove("capability");return copy;}
    private static String digest(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var in=new FileInputStream(file)){byte[] buffer=new byte[1024*1024];int count;while((count=in.read(buffer))>=0)digest.update(buffer,0,count);}return HexFormat.of().formatHex(digest.digest());}
    private static Object field(Object value,String name)throws Exception{Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static Object invoke(Object value,String name,Class<?>[] types,Object...args)throws Exception{Method method=value.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(value,args);}
    private static String id(){return UUID.randomUUID().toString();}
}
