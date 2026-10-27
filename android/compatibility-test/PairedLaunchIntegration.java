package dev.tripoder.hooklab;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

/** Real installation/start/stop through the production workbench authority. No deletion or replay. */
public final class PairedLaunchIntegration extends Instrumentation {
    private Bundle args;private JSONObject report;private File output;
    @Override public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
    private static String id(){return UUID.randomUUID().toString();}
    private void save(String phase)throws Exception{report.put("phase",phase);Files.writeString(output.toPath(),report.toString());android.util.Log.i("TripoderPair",phase+":"+report.optString("package"));}
    /** The observation deadline stays fixed. The driver acknowledges its one
     * bounded capture before this harness stops the owned worker. */
    private boolean awaitCapture(String run)throws Exception{
        if(!"true".equals(args.getString("captureAck")))return true;
        File ack=new File(getTargetContext().getExternalFilesDir(null),"paired-"+run+".capture-ack");
        save("capture-ready");long until=SystemClock.elapsedRealtime()+90000;
        while(SystemClock.elapsedRealtime()<until){
            if(ack.isFile()){
                if(ack.length()!=run.length()+1||!Files.readString(ack.toPath()).equals(run+"\n")){
                    report.put("captureAckFailure","PAIR_CAPTURE_ACK_INVALID");return false;
                }
                return true;
            }
            SystemClock.sleep(100);
        }
        report.put("captureAckFailure","PAIR_CAPTURE_ACK_TIMEOUT");return false;
    }
    /** Test-only observation barrier, after the controller's timed RPC has returned. */
    private void awaitOwnedStopObservation(String run)throws Exception{
        if(!"true".equals(args.getString("stopStatusAck")))return;
        File ack=new File(getTargetContext().getExternalFilesDir(null),"paired-"+run+".stop-status-ack");
        save("stop-returned");long until=SystemClock.elapsedRealtime()+8000;
        while(SystemClock.elapsedRealtime()<until){
            if(ack.isFile()&&ack.length()==run.length()+1&&Files.readString(ack.toPath()).equals(run+"\n")){report.put("stopKernelObservationAcknowledged",true);return;}
            SystemClock.sleep(50);
        }
        report.put("stopKernelObservationAcknowledged",false);
    }
    private Bundle execute(WorkbenchApplication app,String command,String...pairs)throws Exception{return app.engine.executeBlocking(ControlWire.request(command,pairs));}
    private Bundle installOwnedFixture(WorkbenchApplication app,String space,String expected,String local,String expectedPackage)throws Exception{
        File file=new File(local);if(!file.getCanonicalPath().startsWith(app.getExternalFilesDir(null).getCanonicalPath()+"/"))throw new SecurityException("FIXTURE_PATH_NOT_OWNED");
        String digest;try(var input=Files.newInputStream(file.toPath())){var sha=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)sha.update(buffer,0,count);digest=java.util.HexFormat.of().formatHex(sha.digest());}
        try(var fd=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)){
            Bundle request=ControlWire.request("install","operation",id(),"space",space);if(expected!=null)request.putString("expectedGeneration",expected);request.putParcelable("fd0",fd);request.putStringArrayList("names",new java.util.ArrayList<>(java.util.List.of("base.apk")));request.putStringArrayList("sha256",new java.util.ArrayList<>(java.util.List.of(digest)));
            Bundle installed=app.engine.executeBlocking(request);
            if(!expectedPackage.equals(installed.getString("package"))||!digest.equals(installed.getString("sha256"))||installed.getString("generation")==null)throw new SecurityException("OWNED_FIXTURE_INSTALL_IDENTITY_MISMATCH");
            return installed;
        }
    }
    private JSONObject inspect(WorkbenchApplication app,Bundle target,String install,String process,String command,Boolean invisible)throws Exception{
        JSONObject request=new JSONObject().put("command",command).put("processGeneration",process);if(invisible!=null)request.put("includeInvisible",invisible);
        Bundle response=execute(app,"hook","operation",id(),"project",target.getString("project"),"bindingOperation",target.getString("bindingOperation"),"generation",install,"processGeneration",process,"request",request.toString());
        JSONObject value=new JSONObject(response.getString("result"));redactViewText(value);return value;
    }
    private static void redactViewText(Object value)throws Exception{
        if(value instanceof JSONObject object){object.remove("text");object.remove("contentDescription");for(var keys=object.keys();keys.hasNext();)redactViewText(object.get(keys.next()));}
        else if(value instanceof JSONArray array)for(int i=0;i<array.length();i++)redactViewText(array.get(i));
    }
    private static JSONObject applicationMetadata(android.content.pm.ApplicationInfo info)throws Exception{
        JSONObject out=new JSONObject().put("className",info.className==null?JSONObject.NULL:info.className).put("factory",info.appComponentFactory==null?JSONObject.NULL:info.appComponentFactory).put("sourceDir",info.sourceDir).put("flags",info.flags);
        for(String name:new String[]{"classLoaderName","rustApplicationEntry","rustAppLibName"})try{
            var field=android.content.pm.ApplicationInfo.class.getDeclaredField(name);field.setAccessible(true);Object value=field.get(info);out.put(name,value==null?JSONObject.NULL:value);
        }catch(ReflectiveOperationException unavailable){out.put(name,new JSONObject().put("unavailable",unavailable.getClass().getName()));}
        JSONObject dump=new JSONObject();java.util.Set<String> seen=new java.util.HashSet<>();
        if(info.getClass()!=android.content.pm.ApplicationInfo.class)throw new SecurityException("EXACT_PLATFORM_APPLICATION_INFO_REQUIRED");
        info.dump(line->{
            for(String name:new String[]{"classLoaderName","rustApplicationEntry","rustAppLibName"})if(line.startsWith(name+"=")){
                if(!seen.add(name))throw new IllegalStateException("APPLICATION_CODE_DUMP_DUPLICATE");
                try{dump.put(name,line.substring(name.length()+1));}catch(JSONException error){throw new IllegalStateException("APPLICATION_CODE_DUMP_INVALID",error);}
            }
            // All other dump lines are immediately discarded, never retained.
        },"");
        dump.put("dumpCompleted",true).put("classLoaderNameEmitted",seen.contains("classLoaderName")).put("rustFieldsEmitted",seen.contains("rustApplicationEntry")&&seen.contains("rustAppLibName"));
        if(!seen.contains("classLoaderName"))dump.put("classLoaderName",JSONObject.NULL);
        if(!seen.contains("rustApplicationEntry")&&!seen.contains("rustAppLibName"))dump.put("rustStatus","NOT_EMITTED_RUST_FLAG_FALSE");
        else if(!seen.contains("rustApplicationEntry")||!seen.contains("rustAppLibName"))throw new IllegalStateException("APPLICATION_CODE_DUMP_INCOMPLETE");
        out.put("publicSdkDump",dump);
        return out;
    }
    private static JSONObject reportedNonLauncherMetadata(android.content.pm.PackageManager pm)throws Exception{
        JSONObject result=new JSONObject();
        String[] names={"com.baidu.input_mi","com.hyper.gallery.plugin","com.iflytek.inputmethod.miui","com.mfashiongallery.emag","com.miui.cleanmaster","com.miui.mediaeditor","com.miui.newhome","com.miui.newmidrive","com.miui.screenrecorder","com.xiaomi.bestphotos","com.xiaomi.mibrain.speech","cn.wps.moffice_eng.xiaomi.lite"};
        long retained=android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES|android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS|android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE|android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        for(String name:names){
            JSONObject item=new JSONObject();
            for(int view=0;view<2;view++){
                String key=view==0?"installedView":"retainedView";
                try{
                    var info=pm.getApplicationInfo(name,android.content.pm.PackageManager.ApplicationInfoFlags.of(view==0?0:retained));
                    item.put(key,new JSONObject().put("available",true).put("enabled",info.enabled).put("installedForUser",(info.flags&android.content.pm.ApplicationInfo.FLAG_INSTALLED)!=0).put("publicCodeMetadata",applicationMetadata(info)));
                }catch(android.content.pm.PackageManager.NameNotFoundException absent){item.put(key,new JSONObject().put("available",false).put("exception",absent.getClass().getName()));}
            }
            Intent launch=pm.getLaunchIntentForPackage(name);item.put("launcher",launch==null||launch.getComponent()==null?JSONObject.NULL:launch.getComponent().flattenToShortString());result.put(name,item);
        }
        return result;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();android.app.NotificationManager notificationWitness=null;String peerTargetGeneration=null;
        try{
            String host=getTargetContext().getPackageName();if(!host.startsWith("dev.tripoder.compat"))throw new SecurityException("SEPARATE_QA_REQUIRED");
            String pkg=args.getString("package"),space=args.getString("space"),run=args.getString("run");
            if(!run.matches("[a-zA-Z0-9_-]+")||!space.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("PAIR_ARGS");
            report=new JSONObject().put("package",pkg).put("space",space).put("run",run).put("dataCleared",false);
            output=new File(getTargetContext().getExternalFilesDir(null),"paired-"+run+".json");save("connecting");
            if("true".equals(args.getString("queryNativeCodeMetadata"))){report.put("nativeOriginalCode",NativePublicationInspection.platform(getTargetContext()));save("complete");result.putString("report",report.toString());finish(-1,result);return;}
            WorkbenchApplication app=(WorkbenchApplication)getTargetContext().getApplicationContext();long deadline=SystemClock.elapsedRealtime()+35000;
            while((app.engine==null||!app.engine.ready())&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(25);
            if(app.engine==null||!app.engine.ready())throw new IllegalStateException("QA_ENGINE_NOT_CONNECTED:"+(app.engine==null?"uninitialized":app.engine.failure));
            if("true".equals(args.getString("queryPlatformLibraries"))){
                report.put("bootClassPath",System.getProperty("java.boot.class.path", "UNAVAILABLE")).put("bootClassPathEnv",System.getenv("BOOTCLASSPATH"));
                JSONArray libraries=new JSONArray();var pm=app.getPackageManager();
                for(var library:pm.getSharedLibraries(0))libraries.put(new JSONObject().put("name",library.getName()).put("type",library.getType()).put("version",library.getLongVersion()).put("declaringPackage",library.getDeclaringPackage().getPackageName()).put("declaringVersion",library.getDeclaringPackage().getLongVersionCode()));
                JSONObject resolved=new JSONObject();
                for(String name:new String[]{"com.android.soundrecorder","com.miui.compass","com.miui.weather2"})try{
                    var info=pm.getApplicationInfo(name,android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES));
                    resolved.put(name,new JSONArray(info.sharedLibraryFiles==null?new String[0]:info.sharedLibraryFiles));
                }catch(android.content.pm.PackageManager.NameNotFoundException absent){resolved.put(name,new JSONObject().put("absent",true));}
                JSONObject boot=new JSONObject();for(String name:new String[]{"miui.yellowpage.YellowPage","com.xiaomi.micloudsdk.os.MiCloudSdkVersion","org.apache.commons.logging.LogFactory"}){
                    boolean present;try{Class.forName(name,false,ClassLoader.getSystemClassLoader().getParent());present=true;}catch(ClassNotFoundException absent){present=false;}boot.put(name,present);
                }
                JSONObject applicationMetadata=new JSONObject();for(String name:new String[]{"com.xiaomi.shop","com.xiaomi.micloud.sdk","com.miui.system","com.miui.weather2","com.miui.gallery"}){
                    var info=pm.getApplicationInfo(name,0);applicationMetadata.put(name,new JSONObject().put("installed",applicationMetadata(info)));
                    var archive=pm.getPackageArchiveInfo(info.sourceDir,android.content.pm.PackageManager.GET_META_DATA|android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);if(archive!=null)applicationMetadata.getJSONObject(name).put("archive",applicationMetadata(archive.applicationInfo));
                }
                report.put("query","read-only-platform-library-metadata").put("libraries",libraries).put("resolvedFiles",resolved).put("bootVisible",boot).put("applicationMetadata",applicationMetadata).put("reportedNonLauncherPackages",reportedNonLauncherMetadata(pm));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if("true".equals(args.getString("queryLauncherMetadata"))){
                report.put("query","launcher-declarations-and-component-settings").put("launcherMetadata",LauncherMetadataInspection.inspect(app,space,pkg));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if(args.getString("queryOwnedExit")!=null){
                report.put("query","read-only-stamped-qa-exit").put("ownedExit",OwnedExitInspection.inspect(app,space,pkg,args.getString("queryOwnedExit")));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if("true".equals(args.getString("reverifyOnly"))){
                JSONObject before=JobMutationInspection.installation(app,space,pkg);report.put("before",before).put("query","explicit-existing-install-reverification");save("reverifying");String operation=id();report.put("reverifyOperation",operation);
                JSONObject target=new JSONObject().put("space",space).put("package",pkg).put("installGeneration",before.getString("generation"));WorkbenchAgentLifecycle lifecycle=new WorkbenchAgentLifecycle(app);
                report.put("reverifyResult",lifecycle.call("application.reverify",new JSONObject().put("target",target),operation)).put("reverifyReceipt",lifecycle.receiptIfPresent(operation));
                JSONObject after=JobMutationInspection.installation(app,space,pkg);report.put("after",after);
                if(!before.getString("generation").equals(after.getString("generation"))||!before.getString("dataId").equals(after.getString("dataId"))||!before.getString("signer").equals(after.getString("signer"))||before.getLong("version")!=after.getLong("version")||!after.getString("state").equals("COMMITTED"))throw new AssertionError("REVERIFICATION_IDENTITY_CHANGED");
                save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if("true".equals(args.getString("queryComponentRestarts"))){
                report.put("query","read-only-component-restarts").put("restarts",JobMutationInspection.componentRestarts(app,space,pkg));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if("true".equals(args.getString("queryActivityReferences"))){
                report.put("query","read-only-activity-references").put("activityReferences",JobMutationInspection.activityReferences(app,space,pkg));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if(args.getString("queryOwnedTaskRoot")!=null){
                report.put("query","read-only-owned-task-root").put("taskRoot",TaskRootInspection.inspect(app,space,pkg,Integer.parseInt(args.getString("queryOwnedTaskRoot"))));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if(args.getString("queryBroadcastAdmission")!=null){
                report.put("query","read-only-broadcast-admission").put("broadcast",JobMutationInspection.broadcast(app,args.getString("queryBroadcastAdmission")));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if(args.getString("queryJobMutation")!=null){
                report.put("query","read-only-job-mutation").put("mutation",JobMutationInspection.inspect(app,args.getString("queryJobMutation")));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if(args.getString("queryOperation")!=null){
                report.remove("package");report.remove("space");report.remove("mode");report.put("query","operation");
                JSONObject receipt=WorkbenchAgentLifecycle.json(execute(app,"query","operation",args.getString("queryOperation")));report.put("receipt",receipt);
                if(java.util.Set.of("stopApplication","agentStopApplication").contains(receipt.optString("command")))report.put("stopExecutionFacts",StoppedOperationInspection.inspect(getTargetContext(),args.getString("queryOperation"),receipt));save("complete");
                result.putString("report",report.toString());finish(-1,result);return;
            }
            if(args.getString("verifySpaceCreation")!=null){
                if(!space.equals("owned-provider-authority-contract"))throw new SecurityException("OWNED_SPACE_CREATION_REQUIRED");
                String duplicate=id();boolean refused=false,absent=false;
                try{execute(app,"createSpace","operation",duplicate,"space",space);}catch(Exception expected){refused=String.valueOf(expected.getMessage()).contains("SPACE_ALREADY_EXISTS");}
                try{execute(app,"query","operation",duplicate);}catch(Exception expected){absent=String.valueOf(expected.getMessage()).contains("OPERATION_NOT_FOUND");}
                JSONObject prior=WorkbenchAgentLifecycle.json(execute(app,"query","operation",args.getString("verifySpaceCreation")));
                report.put("duplicateCreateRejectedBeforeAdmission",refused&&absent).put("priorCreationReceipt",prior);
                if(!refused||!absent||!"UNKNOWN".equals(prior.optString("state"))||!"SPACE_CREATION_PERSISTENCE_UNCONFIRMED".equals(prior.optString("detail"))||!"space/owned-provider-authority-contract".equals(prior.optString("effectScope"))||!"createSpace".equals(prior.optString("command"))||!"MAY_HAVE_DISPATCHED".equals(prior.optString("dispatch"))||!prior.optBoolean("spaceCreationExecutionInert"))throw new AssertionError("SPACE_CREATION_REGRESSION_FAILED");
                save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if("true".equals(args.getString("resourcesOnly"))){
                report.remove("package");report.remove("space");report.remove("mode");report.put("query","authority-resources");
                report.put("resources",WorkbenchAgentLifecycle.json(execute(app,"resources")));save("complete");
                result.putString("report",report.toString());finish(-1,result);return;
            }
            Bundle catalog=execute(app,"catalog");JSONArray installs=new JSONArray(catalog.getString("installs")),spaces=new JSONArray(catalog.getString("spaces"));String generation=null;
            if("true".equals(args.getString("queryNativeCode"))){
                report.put("nativeCode",NativeCodeInspection.inspect(app,space,pkg));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if("true".equals(args.getString("queryNativeMissingService"))){
                report.put("nativeMissingService",NativeMissingServiceInspection.inspect(app));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if("true".equals(args.getString("queryNativeHttpEngine"))){
                report.put("nativeHttpEngine",dev.tripoder.httpcontract.HttpChecks.inspect(app));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if("true".equals(args.getString("queryNativeOrdered"))){
                JSONObject checks=new JSONObject();dev.tripoder.launchcontract.OrderedResultChecks.run(app,(name,passed)->{checks.put(name,passed);if(!passed)throw new AssertionError(name);});
                report.put("nativeOrdered",new JSONObject().put("passed",true).put("checks",checks).put("accountDataRead",false));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            for(int i=0;i<installs.length();i++){var row=installs.getJSONObject(i);if(space.equals(row.getString("space"))&&pkg.equals(row.getString("package")))generation=row.getString("generation");}
            if(pkg.equals("dev.tripoder.providerpeer")){
                if(!space.equals("owned-provider-authority-contract"))throw new SecurityException("OWNED_PEER_SPACE_REQUIRED");
                String peerTarget="dev.tripoder.closurefixture";
                for(int i=0;i<installs.length();i++){var row=installs.getJSONObject(i);if(space.equals(row.getString("space"))&&peerTarget.equals(row.getString("package")))peerTargetGeneration=row.getString("generation");}
                if(peerTargetGeneration==null){
                    boolean exists=false;for(int i=0;i<spaces.length();i++)exists|=space.equals(spaces.getString(i));
                    if(!exists){execute(app,"createSpace","operation",id(),"space",space);spaces.put(space);}
                    Bundle seeded=installOwnedFixture(app,space,null,new File(app.getExternalFilesDir(null),"closure-fixture.apk").getPath(),peerTarget);
                    peerTargetGeneration=seeded.getString("generation");report.put("peerTargetInputSha256",seeded.getString("sha256"));
                }
                execute(app,"stopApplication","operation",id(),"space",space,"package",peerTarget,"generation",peerTargetGeneration);
                if("true".equals(args.getString("upgradePeerTarget"))){
                    Bundle upgraded=installOwnedFixture(app,space,peerTargetGeneration,new File(app.getExternalFilesDir(null),"closure-fixture.apk").getPath(),peerTarget);
                    peerTargetGeneration=upgraded.getString("generation");report.put("peerTargetInputSha256",upgraded.getString("sha256")).put("peerTargetUpgradedGeneration",peerTargetGeneration);
                }
                Bundle peerBinding=execute(app,"applicationTarget","space",space,"package",peerTarget,"generation",peerTargetGeneration);

                report.put("peerTarget",new JSONObject().put("package",peerTarget).put("space",space).put("installGeneration",peerTargetGeneration));
                // Permission tests use a deliberately prepared fixture. Cold
                // provider startup has its own concurrent owned-process test.
                String fixtureOperation=id();Bundle preparedFixture=execute(app,"prepareApplicationLaunch","operation",fixtureOperation,"space",space,"package",peerTarget,"generation",peerTargetGeneration);
                Bundle revokedFixture=execute(app,"abandonLaunch","operation",fixtureOperation);
                report.put("peerTargetPreparation",new JSONObject().put("operation",fixtureOperation).put("processGeneration",preparedFixture.getString("generation")).put("pid",preparedFixture.getInt("pid")).put("unenteredEntryRevoked",revokedFixture.containsKey("ticket")).put("state",revokedFixture.getString("state")));
                if(!revokedFixture.containsKey("ticket"))throw new IllegalStateException("OWNED_PEER_TARGET_ENTRY_NOT_REVOKED");
            }
            if("true".equals(args.getString("stopOnly"))){
                if(generation==null)throw new IllegalStateException("STOP_INSTALL_NOT_FOUND");save("stopping-only");
                report.put("stopOperation",id());execute(app,"stopApplication","operation",report.getString("stopOperation"),"space",space,"package",pkg,"generation",generation);
                report.put("resourcesAfterStop",WorkbenchAgentLifecycle.json(execute(app,"resources")));save("complete");result.putString("report",report.toString());finish(-1,result);return;
            }
            if(generation==null||"true".equals(args.getString("upgrade"))){
                if(generation!=null){save("stopping-for-upgrade");execute(app,"stopApplication","operation",id(),"space",space,"package",pkg,"generation",generation);}
                boolean exists=false;for(int i=0;i<spaces.length();i++)exists|=space.equals(spaces.getString(i));
                if(!exists)execute(app,"createSpace","operation",id(),"space",space);
                save("importing");String local=args.getString("localApk");
                if(local==null){JSONObject imported=new WorkbenchAgentImports(app).call("application.importDevice",new JSONObject().put("space",space).put("package",pkg).put("expectedGeneration",""),id());generation=imported.getString("generation");}
                else{
                    File file=new File(local);if(!file.getCanonicalPath().startsWith(app.getExternalFilesDir(null).getCanonicalPath()+"/"))throw new SecurityException("FIXTURE_PATH_NOT_OWNED");
                    String digest;try(var input=Files.newInputStream(file.toPath())){var sha=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)sha.update(buffer,0,count);digest=java.util.HexFormat.of().formatHex(sha.digest());}
                    try(var fd=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)){
                        Bundle request=ControlWire.request("install","operation",id(),"space",space);if(generation!=null)request.putString("expectedGeneration",generation);request.putParcelable("fd0",fd);request.putStringArrayList("names",new java.util.ArrayList<>(java.util.List.of("base.apk")));request.putStringArrayList("sha256",new java.util.ArrayList<>(java.util.List.of(digest)));generation=app.engine.executeBlocking(request).getString("generation");
                        if(generation==null||generation.isEmpty())throw new IllegalStateException("FIXTURE_INSTALL_GENERATION_REQUIRED");
                        report.put("fixtureInputSha256",digest).put("fixtureInstalledGeneration",generation);
                    }
                }
            }
            report.put("installGeneration",generation);save("stopping-before");
            execute(app,"stopApplication","operation",id(),"space",space,"package",pkg,"generation",generation);
            save("resolving-target");
            Bundle target=execute(app,"applicationTarget","space",space,"package",pkg,"generation",generation);report.put("project",target.getString("project")).put("bindingOperation",target.getString("bindingOperation"));

            if(pkg.equals("dev.tripoder.closurefixture")){
                notificationWitness=app.getSystemService(android.app.NotificationManager.class);
                report.put("notificationPolicyAccessGranted",notificationWitness.isNotificationPolicyAccessGranted());
                notificationWitness.createNotificationChannel(new android.app.NotificationChannel("compatibility-witness","Compatibility owner witness",android.app.NotificationManager.IMPORTANCE_LOW));
                notificationWitness.notify("compatibility-owner-witness",999401,new android.app.Notification.Builder(app,"compatibility-witness").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Tripoder QA owner witness").build());
                boolean present=false;long until=SystemClock.elapsedRealtime()+3000;
                do{for(var item:notificationWitness.getActiveNotifications())if(item.getId()==999401&&"compatibility-owner-witness".equals(item.getTag()))present=true;if(!present)SystemClock.sleep(50);}while(!present&&SystemClock.elapsedRealtime()<until);
                report.put("notificationWitnessPosted",present);if(!present)throw new IllegalStateException("REAL_NOTIFICATION_WITNESS_NOT_POSTED");
            }
            String operation=id();report.put("operation",operation);save("preparing");
            Bundle launched;
            if(args.containsKey("component")){
                if(!pkg.equals("dev.tripoder.isolatedfdprobe")||!args.getString("component").equals(pkg+".ProductionActivity"))throw new SecurityException("OWNED_NATIVE_PRODUCTION_ENTRY_REQUIRED");
                launched=execute(app,"prepareLaunch","operation",operation,"project",target.getString("project"),"bindingOperation",target.getString("bindingOperation"),"generation",generation,"component",args.getString("component"));
            }else launched=execute(app,"prepareApplicationLaunch","operation",operation,"space",space,"package",pkg,"generation",generation);
            report.put("launch",WorkbenchAgentLifecycle.json(launched));Intent entry=launched.getParcelable("intent",Intent.class);if(entry==null)throw new IllegalStateException("NO_ENTRY_INTENT");
            long start=SystemClock.elapsedRealtime();report.put("startElapsed",start);save("dispatching");
            runOnMainSync(()->app.startActivity(entry));save("observing");
            SystemClock.sleep(Integer.parseInt(args.getString("windowMs","45000")));
            if("true".equals(args.getString("inspectLive"))){
                JSONObject observations=new JSONObject();String process=launched.getString("generation");
                for(String command:new String[]{"diagnostics","uiDump"})try{
                    observations.put(command,inspect(app,target,generation,process,command,command.equals("uiDump")?Boolean.FALSE:null));
                }catch(Exception error){observations.put(command,new JSONObject().put("queryError",error.getClass().getName()).put("detail",String.valueOf(error.getMessage())));}
                try{observations.put("uiDumpInvisible",inspect(app,target,generation,process,"uiDump",Boolean.TRUE));}catch(Exception error){observations.put("uiDumpInvisibleError",error.getClass().getName());}
                report.put("liveInspection",observations);
            }
            report.put("targets",new JSONObject(execute(app,"agentTargets").getString("result")));
            // Query the exact first worker before later pairs add more physical
            // exits to Android's bounded history. Empty remains UNKNOWN; no
            // attribution to another PID, summary, or observation time.
            report.put("initialWorkerExitAtEnd",OwnedExitInspection.inspect(app,space,pkg,launched.getString("generation")));
            if(pkg.equals("dev.tripoder.servicecontract")||pkg.equals("dev.tripoder.isolatedservicecontract"))report.put("resourcesBeforeStop",WorkbenchAgentLifecycle.json(execute(app,"resources")));
            if(pkg.equals("dev.tripoder.isolatedservicecontract")){
                JSONObject isolated=IsolatedExecutionInspection.inspect(app,space,pkg,generation,launched.getString("generation"));report.put("isolatedExecutionBeforeStop",isolated);
                if(isolated.getInt("currentAuthorityIsolatedPausedGenerations")!=0)throw new IllegalStateException("OWNED_NORMAL_ISOLATED_LIFECYCLE_PAUSED");
            }
            if(pkg.equals("dev.tripoder.isolatedfdprobe")){
                JSONObject isolated=IsolatedExecutionInspection.inspect(app,space,pkg,generation,launched.getString("generation"));report.put("isolatedExecutionBeforeStop",isolated).put("nativePublication",NativePublicationInspection.inspect(app,space,pkg,generation));
                if(isolated.getInt("currentAuthorityIsolatedPausedGenerations")!=0)throw new IllegalStateException("OWNED_NORMAL_NATIVE_ISOLATED_LIFECYCLE_PAUSED");
            }
            if(pkg.equals("dev.tripoder.closurefixture")||pkg.equals("dev.tripoder.launchcontract"))report.put("broadcastsBeforeStop",BroadcastDeliveryInspection.inspect(app,space,pkg,launched.getString("generation")));
            report.put("operationReceipt",WorkbenchAgentLifecycle.json(execute(app,"query","operation",operation)));save("observed");
            boolean captureRequested="true".equals(args.getString("captureAck"));
            boolean captureAcknowledged=awaitCapture(run);report.put("captureRequested",captureRequested).put("captureAcknowledged",captureRequested?captureAcknowledged:JSONObject.NULL);
            String stopOperation=id();report.put("stopOperation",stopOperation);save("stopping");long stopBegin=SystemClock.elapsedRealtime();
            try{execute(app,"stopApplication","operation",stopOperation,"space",space,"package",pkg,"generation",generation);report.put("stopConfirmed",true);}
            finally{
                report.put("stopElapsedMs",SystemClock.elapsedRealtime()-stopBegin);
                if(pkg.equals("dev.tripoder.stopcontract"))awaitOwnedStopObservation(run);
                if(pkg.equals("dev.tripoder.stopcontract"))try{
                    JSONObject status=new JSONObject();
                    for(String line:Files.readAllLines(java.nio.file.Path.of("/proc/"+launched.getInt("pid")+"/status"))){
                        int colon=line.indexOf(':');if(colon<0)continue;String key=line.substring(0,colon);
                        if(java.util.Set.of("State","TracerPid","Uid","Threads").contains(key))status.put(key,line.substring(colon+1).trim());
                    }
                    report.put("ownedKernelStatusAtStopReturn",status);
                }catch(Exception observation){report.put("ownedKernelStatusReadError",observation.getClass().getName());}
                try{report.put("stopReceipt",WorkbenchAgentLifecycle.json(execute(app,"query","operation",stopOperation)));}
                catch(Exception queryError){report.put("stopReceiptReadError",queryError.getClass().getName());}
                if(!report.optBoolean("stopConfirmed"))try{report.put("resourcesAfterUnconfirmedStop",WorkbenchAgentLifecycle.json(execute(app,"resources")));}
                catch(Exception queryError){report.put("stopResourcesReadError",queryError.getClass().getName());}
            }
            if(peerTargetGeneration!=null){execute(app,"stopApplication","operation",id(),"space",space,"package","dev.tripoder.closurefixture","generation",peerTargetGeneration);report.put("peerTargetStopConfirmed",true);}
            report.put("resourcesAfterStop",WorkbenchAgentLifecycle.json(execute(app,"resources")));
            if(!captureAcknowledged)throw new IllegalStateException(report.getString("captureAckFailure")+"_AFTER_STOP");save("complete");
        }catch(Throwable error){try{report.put("failedAt",report.optString("phase")).put("error",android.util.Log.getStackTraceString(error));save("failed");}catch(Exception ignored){}}
        if(notificationWitness!=null)notificationWitness.cancel("compatibility-owner-witness",999401);
        result.putString("report",report==null?"{}":report.toString());finish(report!=null&&report.optString("phase").equals("complete")?-1:0,result);
    }
}
