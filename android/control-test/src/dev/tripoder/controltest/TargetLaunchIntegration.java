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
        try(var diagnostic=new ControlThreadWatchdog(getTargetContext(),run)){runTarget();}
    }
    private void runTarget()throws Exception{
        String target=args.getString("target");boolean wechat="wechat".equals(target);
        String pkg,expected,title;
        if(args.containsKey("customPackage")){
            pkg=args.getString("customPackage");expected=args.getString("customSha256");title=pkg+" · 兼容性验收";
        }else switch(target==null?"":target){
            case "wechat":pkg="com.tencent.mm";expected="f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d";title="微信 · 真机验收";break;
            case "honor":pkg="com.tencent.tmgp.sgame";expected="6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384";title="王者荣耀 · 真机验收";break;
            case "wps":pkg="com.kingsoft.xiezuo";expected="71df658765efeaa59e81733046b66ca7e94430eece14b9f996afa7c1b15e3ff7";title="WPS协作 · 真机验收";break;
            default:throw new IllegalArgumentException("TARGET_REQUIRED");
        }
        // Hook targets exist only for the two applications whose native libraries were analysed.
        if(!("wechat".equals(target)||"honor".equals(target))&&("true".equals(args.getString("nativeHook"))||"true".equals(args.getString("managedJniHook"))))throw new IllegalArgumentException("HOOK_TARGET_NOT_DEFINED");
        report.put("scope","true".equals(args.getString("nativeHook"))?"original-target-native-original-forwarding":"original-target-first-launch-only").put("target",pkg).put("apkSha256",expected).put("apkRewritten",false).put("loginAttempted",false).put("businessScenarioVerified",false);
        startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();IBinder session=(IBinder)field(field(app,"engine"),"session");Object projects=field(app,"projects");
        File source=args.containsKey("customSource")?new File(args.getString("customSource")):new File(getTargetContext().getExternalFilesDir(null),"acceptance/"+target+".apk");check("original-apk-digest-matches",expected.equals(digest(source)),expected);
        Bundle catalog=execute(session,"catalog");JSONArray spaces=new JSONArray(catalog.getString("spaces"));boolean exists=false;for(int i=0;i<spaces.length();i++)if(SPACE.equals(spaces.getString(i)))exists=true;
        if(args.containsKey("reverifySpace")){
            String repairSpace=args.getString("reverifySpace"),repairPackage=args.getString("reverifyPackage"),repairGeneration=args.getString("reverifyGeneration");String failure=null;
            try{execute(session,"reverifyInstall","space",repairSpace,"package",repairPackage,"generation",repairGeneration);}
            catch(Throwable error){StringBuilder text=new StringBuilder();for(Throwable c=error;c!=null;c=c.getCause())text.append(c.getClass().getSimpleName()).append(':').append(c.getMessage()).append(" | ");failure=text.toString();}
            report.put("reverifyError",failure==null?JSONObject.NULL:failure).put("workerUid",android.os.Process.myUid());check("reverify-attempted",true,String.valueOf(failure));return;
        }
        if("true".equals(args.getString("deleteApplication"))){
            // The same engine command the user's "删除应用" issues: every installation, its data and the bound projects go; the source kept for those projects goes with them.
            Bundle gone=execute(session,"deleteApplication","operation",id(),"package",pkg);
            JSONArray removed=new JSONArray(gone.getString("deletedProjects"));
            for(int i=0;i<removed.length();i++)invoke(projects,"delete",new Class<?>[]{String.class},removed.getString(i));
            report.put("deletedProjects",removed).put("deletedInstalls",gone.getInt("installs")).put("workerUid",android.os.Process.myUid());
            check("application-deleted-with-its-projects",true,pkg);return;
        }
        if("true".equals(args.getString("listCatalog"))){report.put("installs",new JSONArray(catalog.getString("installs"))).put("projects",new JSONArray(catalog.getString("projects"))).put("spaces",new JSONArray(catalog.getString("spaces"))).put("workerUid",android.os.Process.myUid());check("catalog-listed-without-changes",true,"read only");return;}
        if(!exists)execute(session,"createSpace","space",SPACE,"operation",id());
        File recordFile=new File(getTargetContext().getFilesDir(),"acceptance-"+(target!=null?target:pkg)+".json");JSONObject record;
        // A receipt describes an installation. When the application was deleted (which also deletes its projects) there is nothing left for it to describe.
        if(recordFile.exists()&&!installedInSpace(catalog,pkg)&&!recordFile.delete())throw new IllegalStateException("TARGET_RECEIPT_NOT_REMOVABLE");
        if(recordFile.exists()){
            record=new JSONObject(java.nio.file.Files.readString(recordFile.toPath()));
            if(!expected.equals(record.getString("sha256")))throw new IllegalStateException("TARGET_RECEIPT_DIGEST_CHANGED");

            Bundle resolved=execute(session,"resolveProject","project",record.getString("project"));
            if(!SPACE.equals(resolved.getString("space"))||!pkg.equals(resolved.getString("package"))||!record.getString("generation").equals(resolved.getString("generation"))||!record.getString("binding").equals(resolved.getString("bindingOperation")))throw new IllegalStateException("TARGET_BINDING_CHANGED");
        }else if((record=claimExistingInstall(session,catalog,pkg,expected,projects,title))!=null){
            atomicWrite(recordFile,record);

        }else{
            String operation=id();Bundle installedResult;
            try(var fd=ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY)){
                Bundle request=ControlWire.request("install","space",SPACE,"operation",operation);request.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));request.putStringArrayList("sha256",new ArrayList<>(List.of(expected)));request.putParcelable("fd0",fd);
                installedResult=ControlWire.call(session,ControlWire.EXECUTE,request);
            }
            check("target-install-committed","SUCCEEDED".equals(execute(session,"query","operation",operation).getString("state")),json(installedResult));
            String generation=installedResult.getString("generation");Bundle canonical=applicationProject(session,projects,SPACE,pkg,generation);String project=canonical.getString("project"),binding=canonical.getString("bindingOperation");

            record=new JSONObject().put("project",project).put("binding",binding).put("generation",generation).put("sha256",expected);
            atomicWrite(recordFile,record);
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
        if("true".equals(args.getString("diagnoseUninstall"))){
            report.put("workerUid",android.os.Process.myUid());String failure=null;
            try{execute(session,"uninstallApplication","operation",id(),"space",SPACE,"package",pkg,"generation",record.getString("generation"));}
            catch(Throwable error){StringBuilder text=new StringBuilder();for(Throwable c=error;c!=null;c=c.getCause())text.append(c.getClass().getSimpleName()).append(':').append(c.getMessage()).append(" | ");failure=text.toString();}
            try{report.put("unresolvedActivities",new JSONArray(execute(session,"resources").getString("unresolvedActivities")));}catch(Throwable error){report.put("unresolvedActivities","unavailable:"+error);}
            report.put("uninstallError",failure==null?JSONObject.NULL:failure);check("uninstall-diagnosed-without-launch",true,String.valueOf(failure));return;
        }
        if("true".equals(args.getString("listEntries"))){report.put("installs",new JSONArray(catalog.getString("installs"))).put("launchEntries",new JSONArray(execute(session,"launchEntries","project",record.getString("project")).getString("entries"))).put("workerUid",android.os.Process.myUid());check("launch-entries-listed-without-launch",true,record.getString("project"));return;}
        if(args.containsKey("closeLaunchEntry"))closePriorEntry(session,record,args.getString("closeLaunchEntry"));
        else if(args.containsKey("customPackage")){
            try{execute(session,"stopApplication","operation",id(),"space",SPACE,"package",pkg,"generation",record.getString("generation"));}catch(Exception ignored){}
            JSONArray priorEntries=new JSONArray(execute(session,"launchEntries","project",record.getString("project")).getString("entries"));
            for(int i=0;i<priorEntries.length();i++){
                JSONObject item=priorEntries.getJSONObject(i);
                if("UNKNOWN".equals(item.optString("outcome")))execute(session,"closeLaunchEntry","operation",id(),"project",record.getString("project"),"bindingOperation",record.getString("binding"),"generation",record.getString("generation"),"launchOperation",item.getString("operation"));
            }
        }
        if("true".equals(args.getString("closeOnly"))){report.put("workerUid",android.os.Process.myUid());check("launch-entry-closed-without-launch",args.containsKey("closeLaunchEntry")||args.containsKey("closeActivityTicket"),"no launch");return;}
        JSONArray targets=new JSONArray(execute(session,"launchTargets","project",record.getString("project")).getString("targets"));JSONObject launcher=null;
        for(int i=0;i<targets.length();i++)if(targets.getJSONObject(i).getBoolean("launcher")){if(launcher!=null)throw new IllegalStateException("MULTIPLE_LAUNCHERS_REQUIRE_SELECTION");launcher=targets.getJSONObject(i);}
        if(launcher==null)throw new IllegalStateException("TARGET_LAUNCHER_MISSING");report.put("launcher",launcher);
        check("declared-launcher-policy-supported",launcher.getBoolean("supported"),launcher);
        String launch=id();report.put("launchOperation",launch);Bundle prepared;
        long prepareStartMs=SystemClock.elapsedRealtime();
        try{prepared=execute(session,"prepareLaunch","operation",launch,"project",record.getString("project"),"bindingOperation",record.getString("binding"),"generation",record.getString("generation"),"component",launcher.getString("component"));}
        catch(Exception error){
            try{
                report.put("launchReceipt",json(execute(session,"query","operation",launch)));
                report.put("retainedLaunchEntries",new JSONArray(execute(session,"launchEntries","project",record.getString("project")).getString("entries")));
                report.put("retainedResources",json(execute(session,"resources")));
            }catch(Exception observation){error.addSuppressed(observation);}
            throw error;
        }
        long prepareLaunchMs=SystemClock.elapsedRealtime()-prepareStartMs;
        Bundle worker=awaitWorker(session,record.getString("project"),prepared);
        long workerReadyMs=SystemClock.elapsedRealtime()-prepareStartMs;
        report.put("prepareLaunchMs",prepareLaunchMs).put("workerReadyMs",workerReadyMs).put("workerPid",worker.getInt("pid")).put("workerUid",worker.getInt("uid")).put("processGeneration",prepared.getString("generation"));check("target-application-initialized",worker.getInt("pid")>0&&worker.getInt("uid")==android.os.Process.myUid()&&worker.getInt("pid")!=android.os.Process.myPid(),json(preparedWithoutEntry(prepared)));
        java.util.concurrent.atomic.AtomicReference<Activity> page=new java.util.concurrent.atomic.AtomicReference<>();Application application=(Application)app;
        Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){if(a.getClass().getName().equals("dev.tripoder.hooklab.WorkbenchProjectActivity"))page.set(a);}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}};
        application.registerActivityLifecycleCallbacks(observer);
        try{
            getTargetContext().startActivity(new Intent().setComponent(engineComponent("dev.tripoder.hooklab.WorkbenchProjectActivity")).putExtra("project",record.getString("project")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline=SystemClock.elapsedRealtime()+8000;while(page.get()==null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(25);if(page.get()==null)throw new IllegalStateException("PROJECT_PAGE_NOT_CREATED");waitForIdleSync();
            long activityStartMs=SystemClock.elapsedRealtime();
            runOnMainSync(()->{Intent start=prepared.getParcelable("intent",Intent.class);android.util.Log.i("TripoderLaunch","controller start component="+start.getComponent().flattenToShortString()+" ticket="+start.getStringExtra("tripoder.ticket"));page.get().startActivity(start);});
            Bundle receipt;deadline=SystemClock.elapsedRealtime()+15000;do{receipt=execute(session,"query","operation",launch);if(!"RUNNING".equals(receipt.getString("state")))break;SystemClock.sleep(25);}while(SystemClock.elapsedRealtime()<deadline);
            long firstActivityAckMs=SystemClock.elapsedRealtime()-activityStartMs;
            report.put("firstActivityAckMs",firstActivityAckMs).put("totalLaunchMs",prepareLaunchMs+firstActivityAckMs).put("launchReceipt",json(receipt));check("target-first-activity-acknowledged","SUCCEEDED".equals(receipt.getString("state")),json(receipt));
        }finally{application.unregisterActivityLifecycleCallbacks(observer);}
        long stabilityMs=args.containsKey("stabilitySeconds")?Long.parseLong(args.getString("stabilitySeconds"))*1000L:30000L;
        JSONArray samples=new JSONArray();report.put("stabilitySamples",samples);long started=SystemClock.elapsedRealtime(),stableUntil=started+stabilityMs,lastSample=started,maxGap=0;int emptyStreak=0;
        while(SystemClock.elapsedRealtime()<stableUntil){
            android.util.Log.i("TripoderAcceptance","stability-query-start:"+samples.length());
            JSONArray live=new JSONArray(execute(session,"hookProcesses","project",record.getString("project")).getString("processes"));boolean generationAlive=false,originalPidAlive=false;JSONArray livePids=new JSONArray();
            android.util.Log.i("TripoderAcceptance","stability-query-end:"+samples.length());
            long sampled=SystemClock.elapsedRealtime();maxGap=Math.max(maxGap,sampled-lastSample);lastSample=sampled;
            for(int i=0;i<live.length();i++){JSONObject process=live.getJSONObject(i);if(prepared.getString("generation").equals(process.getString("processGeneration"))){generationAlive=true;livePids.put(process.getInt("pid"));if(prepared.getInt("pid")==process.getInt("pid"))originalPidAlive=true;}}
            // A multiprocess or hot-fix (Tinker) guest recycles its initial pid during startup, so pin liveness to the launch
            // generation's whole process set, not one pid: the guest is running while ANY process of this generation is alive,
            // and it has died only when the generation stays absent across several samples (tolerating the brief restart gap).
            if(generationAlive)emptyStreak=0;else emptyStreak++;
            samples.put(new JSONObject().put("time",System.currentTimeMillis()).put("originalAlive",generationAlive).put("generationAlive",generationAlive).put("originalPidAlive",originalPidAlive).put("livePids",livePids).put("processes",live));
            if(emptyStreak>=3){check("target-original-worker-remains-running-for-30-seconds",false,live);break;}SystemClock.sleep(1000);
        }
        maxGap=Math.max(maxGap,SystemClock.elapsedRealtime()-lastSample);
        check("target-stability-observation-is-continuous",samples.length()>=2&&maxGap<=5000,new JSONObject().put("samples",samples.length()).put("maxGapMs",maxGap).put("elapsedMs",SystemClock.elapsedRealtime()-started));
        check("target-original-worker-remains-running-for-30-seconds",true,samples.length());
        if("true".equals(args.getString("wake")))wakeRunningApplication(session,pkg,record,launcher,prepared);
        if("true".equals(args.getString("nativeHook")))new TargetNativeHookChecks(this,session,record,prepared.getString("generation"),wechat).run();
        if("true".equals(args.getString("managedJniHook")))new TargetNativeHookChecks(this,session,record,prepared.getString("generation"),wechat).managedJni();
        android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
        if(screenshot==null){
            // No screenshot is acceptable only when the application itself forbids it; anything else is still a failure.
            if(!secureWindowOf(pkg))throw new IllegalStateException("TARGET_SCREENSHOT_UNAVAILABLE");
            check("screenshot-unavailable-because-target-window-is-secure",true,pkg);report.put("screenshotSkipped","TARGET_DECLARES_FLAG_SECURE");
        }else{
            String capture=run+"-target-stable.png";try(var output=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),capture))){if(!screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))throw new IOException("TARGET_SCREENSHOT_FAILED");}finally{screenshot.recycle();}report.put("captures",new JSONArray().put(capture));
        }
        if("true".equals(args.getString("stopAfterTest"))){
            String stop=id();execute(session,"stopApplication","operation",stop,"space",SPACE,"package",pkg,"generation",record.getString("generation"));
            check("target-stopped-after-test","SUCCEEDED".equals(execute(session,"query","operation",stop).getString("state")),stop);
        }
        check("original-apk-unchanged",expected.equals(digest(source)),expected);
    }
    /** Tapping an application that is already running must bring its own task back, in the same process, with no second root. */
    private void wakeRunningApplication(IBinder session,String pkg,JSONObject record,JSONObject launcher,Bundle first)throws Exception{
        shell("input keyevent KEYCODE_HOME");SystemClock.sleep(1500);
        check("wake-guest-is-backgrounded",!focusedWindow().contains(enginePackage()+"/")&&!focusedWindow().contains(pkg+"/"),focusedWindow());
        // The user taps the icon inside the workbench, so the app is in the foreground when the wake is requested.
        shell("am start -n "+enginePackage()+"/dev.tripoder.hooklab.WorkbenchActivity");SystemClock.sleep(2000);
        check("wake-requested-from-foreground-workbench",focusedWindow().contains("WorkbenchActivity"),focusedWindow());
        String wake=id();
        Bundle woken=execute(session,"prepareLaunch","operation",wake,"project",record.getString("project"),"bindingOperation",record.getString("binding"),"generation",record.getString("generation"),"component",launcher.getString("component"));
        check("wake-returns-woken-without-entry",woken.getBoolean("woken",false)&&!woken.containsKey("intent"),json(woken));
        Bundle receipt=execute(session,"query","operation",wake);
        check("wake-operation-succeeded","SUCCEEDED".equals(receipt.getString("state")),json(receipt));
        SystemClock.sleep(1500);
        report.put("wakeFocus",focusedWindow()).put("wakeTasks",shell("dumpsys activity activities").lines().filter(l->l.contains(enginePackage())&&(l.contains("topResumedActivity")||l.contains("ResumedActivity")||l.contains("Task{"))).limit(20).collect(java.util.stream.Collectors.joining("\n")));
        check("wake-guest-is-foreground-again",(focusedWindow().contains(enginePackage()+"/")||focusedWindow().contains(pkg+"/"))&&!focusedWindow().contains("WorkbenchActivity"),focusedWindow());
        JSONArray live=new JSONArray(execute(session,"hookProcesses","project",record.getString("project")).getString("processes"));boolean same=false;
        for(int i=0;i<live.length();i++){JSONObject process=live.getJSONObject(i);if(first.getString("generation").equals(process.getString("processGeneration"))&&first.getInt("pid")==process.getInt("pid"))same=true;}
        check("wake-keeps-the-same-guest-process",same,live);
    }
    private String focusedWindow()throws Exception{
        StringBuilder focus=new StringBuilder();
        for(String line:shell("dumpsys window").split("\n"))if(line.contains("mCurrentFocus=")&&line.contains("Window{"))focus.append(line.trim()).append(' ');
        return focus.toString();
    }
    private void atomicWrite(File file,JSONObject record)throws Exception{
        android.util.AtomicFile atomic=new android.util.AtomicFile(file);FileOutputStream out=atomic.startWrite();try{out.write(record.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception error){atomic.failWrite(out);throw error;}
    }
    /** Migration: adopt a COMMITTED install already in SPACE whose receipt file was lost (e.g. the workbench/engine merge). Returns null when no install exists. */
    private JSONObject claimExistingInstall(IBinder session,Bundle catalog,String pkg,String expected,Object projects,String title)throws Exception{
        JSONObject install=null;JSONArray installed=new JSONArray(catalog.getString("installs"));
        for(int i=0;i<installed.length();i++){JSONObject item=installed.getJSONObject(i);if(!SPACE.equals(item.getString("space"))||!pkg.equals(item.getString("package")))continue;
            if(!"COMMITTED".equals(item.getString("state"))||install!=null)throw new IllegalStateException("TARGET_INSTALL_NOT_CLAIMABLE");install=item;}
        if(install==null)return null;
        String generation=install.getString("generation");JSONObject bound=null;JSONArray refs=new JSONArray(catalog.getString("projects"));
        for(int i=0;i<refs.length();i++){JSONObject item=refs.getJSONObject(i);
            if(SPACE.equals(item.getString("space"))&&pkg.equals(item.getString("package"))&&generation.equals(item.getString("generation"))&&"COMMITTED".equals(item.getString("state"))){
                if(bound!=null)throw new IllegalStateException("TARGET_CLAIM_AMBIGUOUS_PROJECT");bound=item;}}
        String project,binding;
        if(bound!=null){project=bound.getString("project");binding=bound.getString("bindingOperation");}
        else{
            Bundle canonical=applicationProject(session,projects,SPACE,pkg,generation);project=canonical.getString("project");binding=canonical.getString("bindingOperation");
        }
        execute(session,"selectProjectTarget","operation",id(),"project",project,"space",SPACE,"package",pkg,"generation",generation);

        Bundle resolved=execute(session,"resolveProject","project",project);
        if(!SPACE.equals(resolved.getString("space"))||!pkg.equals(resolved.getString("package"))||!generation.equals(resolved.getString("generation"))||!binding.equals(resolved.getString("bindingOperation")))throw new IllegalStateException("TARGET_CLAIM_BINDING_MISMATCH");
        JSONObject record=new JSONObject().put("project",project).put("binding",binding).put("generation",generation).put("sha256",expected);
        report.put("claimedExistingInstall",new JSONObject().put("generation",generation).put("project",project).put("reusedBinding",bound!=null));
        return record;
    }
    private boolean installedInSpace(Bundle catalog,String pkg)throws Exception{
        JSONArray installed=new JSONArray(catalog.getString("installs"));
        for(int i=0;i<installed.length();i++){JSONObject item=installed.getJSONObject(i);if(SPACE.equals(item.getString("space"))&&pkg.equals(item.getString("package")))return true;}
        return false;
    }
    /** True only when the foreground window of this package really declares FLAG_SECURE, which the platform answers with no screenshot at all. */
    private boolean secureWindowOf(String pkg)throws Exception{
        try(java.io.InputStream in=new java.io.FileInputStream(getUiAutomation().executeShellCommand("dumpsys window windows").getFileDescriptor())){
            String dump=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            for(String block:dump.split("\n  Window #")){
                if(block.contains(" u0 "+pkg+"/")&&block.contains(" fl=")&&java.util.regex.Pattern.compile("\\n\\s+fl=[^\\n]*\\bSECURE\\b").matcher(block).find())return true;
            }
        }
        return false;
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
    private static Bundle preparedWithoutEntry(Bundle value){Bundle copy=new Bundle(value);copy.remove("intent");return copy;}
    private static String digest(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var in=new FileInputStream(file)){byte[] buffer=new byte[1024*1024];int count;while((count=in.read(buffer))>=0)digest.update(buffer,0,count);}return HexFormat.of().formatHex(digest.digest());}
    private static Object field(Object value,String name)throws Exception{Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static Object invoke(Object value,String name,Class<?>[] types,Object...args)throws Exception{Method method=value.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(value,args);}
    private static String id(){return UUID.randomUUID().toString();}
}
