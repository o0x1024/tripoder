package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.os.*;
import java.io.*;
import java.util.*;

/** One immutable application identity per process. Not a hostile-code security sandbox. */
public final class RuntimeEngine {
    public static volatile RuntimeEngine current;
    public static volatile boolean adapterReady;
    public final Context host;
    public final String generation,packageName,processName;
    public final File data;
    final RuntimeStorage storage;
    final RuntimeHookBridge hooks;
    public ClassLoader loader;
    private final ClassLoader artifactLoader;
    AppComponentFactory componentFactory;
    public final Resources resources;
    public final ApplicationInfo info;
    final PackageInfo packageArchive;
    final long firstInstallTime,lastUpdateTime;
    private final Map<String,ActivityInfo> activityInfos=new HashMap<>();
    public Application application;
    private ContentResolver settingsResolver;
    private SettingsReadProvider settingsProvider;
    private final IBinder broker;
    final RuntimeJobScheduler jobScheduler=new RuntimeJobScheduler(this);
    final RuntimeJobs jobs=new RuntimeJobs(this);
    final RuntimeServices services=new RuntimeServices(this);
    final RuntimeProviders providers;
    final RuntimeProviderIndex providerIndex;
    final RuntimeServiceIndex serviceIndex;
    final RuntimeActivityManager activityManager=new RuntimeActivityManager(this);
    final RuntimeConnectivity connectivity=new RuntimeConnectivity(this);
    final RuntimeTelephony telephony=new RuntimeTelephony(this);
    final RuntimeAudio audio=new RuntimeAudio(this);
    final RuntimeBattery battery=new RuntimeBattery(this);
    final RuntimePower power=new RuntimePower(this);
    final RuntimeNetworkStats networkStats=new RuntimeNetworkStats();
    final RuntimeWifiP2p wifiP2p=new RuntimeWifiP2p(this);
    final RuntimeNotifications notifications=new RuntimeNotifications(this);
    final RuntimeShortcutCapability shortcuts=new RuntimeShortcutCapability(this);
    final RuntimePackages packages=new RuntimePackages(this);
    final RuntimePackageContexts packageContexts=new RuntimePackageContexts(this);
    final Set<String> requestedPermissions;
    final RuntimeReceivers receivers=new RuntimeReceivers(this);
    private final Map<Activity,String[]> callbacks=new java.util.IdentityHashMap<>();
    private final Map<Activity,Intent> visibleIntents=new java.util.IdentityHashMap<>();
    private final Map<Activity,String[]> activityInstances=new IdentityHashMap<>();
    private final Map<Activity,ActivityInfo> activityMetadata=new IdentityHashMap<>();
    private final Map<Activity,Integer> destructionTasks=new IdentityHashMap<>();
    final RuntimeActivityCallers activityCallers=new RuntimeActivityCallers();
    private final Set<Activity> returned=Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Map<String,Map<Integer,String>> pendingResults=new HashMap<>();
    private boolean ready;
    boolean ownsWindow(android.view.View root){for(Activity activity:activityInstances.keySet())if(activity.getWindow()!=null&&activity.getWindow().peekDecorView()==root)return true;return false;}
    private final java.util.concurrent.FutureTask<Void> applicationStart;
    private final java.util.concurrent.CountDownLatch constructed=new java.util.concurrent.CountDownLatch(1);
    final long startupDeadlineElapsed;
    final boolean platformDefaultApplication;
    public RuntimeEngine(Context host,Bundle input)throws Exception {
        if(current!=null)throw new IllegalStateException("PROCESS_IDENTITY_IMMUTABLE");
        RuntimePlatform.requireValidated();
        startupDeadlineElapsed=input.getLong("startupDeadlineElapsed");
        if(RuntimeStartupDeadline.remaining(startupDeadlineElapsed,SystemClock.elapsedRealtime())==0)throw new IllegalStateException("PROCESS_START_TIMEOUT_UNKNOWN");
        this.host=host;generation=RuntimeWire.required(input,"generation");packageName=RuntimeWire.required(input,"package");processName=RuntimeWire.required(input,"processName");
        firstInstallTime=input.getLong("firstInstallTime");lastUpdateTime=input.getLong("lastUpdateTime");
        File apk=new File(RuntimeWire.required(input,"apk"));if(!ApkStore.digest(apk).equals(RuntimeWire.required(input,"sha256")))throw new SecurityException("LOAD_DIGEST_MISMATCH");
        ArtifactSet artifacts=ArtifactSet.verify(apk.getParentFile(),RuntimeWire.required(input,"descriptorSha"));
        platformDefaultApplication=artifacts.baseManifest.applicationName.isEmpty()||artifacts.baseManifest.applicationName.equals(Application.class.getName());
        if(artifacts.parts.size()!=1)throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        storage=new RuntimeStorage(host,input);data=storage.root;
        PackageInfo pkg=host.getPackageManager().getPackageArchiveInfo(apk.toString(),PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS|PackageManager.GET_META_DATA|PackageManager.GET_PERMISSIONS|PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_SIGNATURES|PackageManager.GET_URI_PERMISSION_PATTERNS|PackageManager.MATCH_DISABLED_COMPONENTS);if(pkg==null||!pkg.packageName.equals(packageName))throw new SecurityException("LOAD_PACKAGE_MISMATCH");
        if(pkg.signingInfo==null)throw new SecurityException("PACKAGE_SIGNING_METADATA_MISSING");
        List<String> signers=new ArrayList<>();for(Signature signature:pkg.signingInfo.getApkContentsSigners())signers.add(ArtifactSet.hex(java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));Collections.sort(signers);
        if(!signers.equals(artifacts.signerDigests))throw new SecurityException("PACKAGE_SIGNING_METADATA_MISMATCH");packageArchive=pkg;
        requestedPermissions=pkg.requestedPermissions==null?Set.of():Set.copyOf(Arrays.asList(pkg.requestedPermissions));
        info=new ApplicationInfo(pkg.applicationInfo);info.sourceDir=apk.toString();info.publicSourceDir=apk.toString();info.dataDir=data.toString();info.deviceProtectedDataDir=null;info.nativeLibraryDir=new File(apk.getParentFile(),"lib").getCanonicalPath();info.uid=android.os.Process.myUid();
        for(ActivityInfo activity:RuntimeActivityIndex.create(artifacts,pkg).values())if(processName.equals(activity.processName))activityInfos.put(activity.name,activity);
        artifactLoader=RuntimeDexLoader.create(artifacts,info.nativeLibraryDir,host.getClassLoader());loader=artifactLoader;
        resources=host.getPackageManager().getResourcesForApplication(info);
        var declarations=RuntimeManifestIndex.resolve(artifacts,pkg);providerIndex=new RuntimeProviderIndex(pkg.providers,declarations);serviceIndex=new RuntimeServiceIndex(pkg.services,declarations);
        providers=new RuntimeProviders(this,pkg.providers,java.util.Objects.requireNonNull(input.getBundle("providers")),declarations);
        packages.captureInfrastructure();
        broker=input.getBinder("broker");if(broker==null)throw new SecurityException("BROKER_MISSING");hooks=new RuntimeHookBridge(this,input,artifacts);current=this;
        // An old controller cannot be replaced under a still-executing guest identity.
        broker.linkToDeath(()->android.os.Process.killProcess(android.os.Process.myPid()),0);
        Bundle registration=auth();registration.putString("bootstrap",RuntimeWire.required(input,"bootstrap"));RuntimeWire.call(broker,RuntimeWire.REGISTER,registration);
        System.loadLibrary("tripoderselfhook");
        RuntimeSelfHookProduct.install(this);
        if(!adapterReady)throw new IllegalStateException("SELF_HOOK_READY_PUBLICATION_MISSING");
        applicationStart=new java.util.concurrent.FutureTask<>(()->{
            recordStart("STARTING");
            try{
            Thread.currentThread().setContextClassLoader(loader);
            componentFactory=info.appComponentFactory==null?new AppComponentFactory():(AppComponentFactory)loader.loadClass(info.appComponentFactory).getDeclaredConstructor().newInstance();
            ClassLoader selected=componentFactory.instantiateClassLoader(loader,GuestPackageManager.copyApplicationInfo(info,true));if(!ownsLoader(selected))throw new SecurityException("FACTORY_CLASS_LOADER_OUTSIDE_ARTIFACT");loader=selected;Thread.currentThread().setContextClassLoader(loader);
            application=componentFactory.instantiateApplication(loader,RuntimeWire.required(input,"application"));
            if(application==null)throw new IllegalStateException("FACTORY_APPLICATION_MISSING");
            RuntimeBridge.observeApplicationOwnership("before-attach",host,application);
            Context logical=RuntimeBridge.createApplicationContext(host,application,GuestPackageManager.copyApplicationInfo(info,true),loader,componentFactory,resources);
            try{RuntimeBridge.attachApplication(application,GuestContext.application(logical,this));}
            finally{RuntimeBridge.observeApplicationOwnership("after-attach",host,application);}
            RuntimeBridge.checkApplicationOwnership(host,application);
            application.registerActivityLifecycleCallbacks((RuntimeApplication)host);
            providers.initialize();
            recordStart("ON_CREATE");
            try{new Instrumentation().callApplicationOnCreate(application);}
            finally{RuntimeBridge.observeApplicationOwnership("after-onCreate",host,application);}
            RuntimeBridge.checkApplicationOwnership(host,application);
            recordStart("READY");ready=true;event("application-ready");return null;
            }catch(Exception|Error error){try{recordStart("FAILED");}catch(Exception receipt){error.addSuppressed(receipt);}throw error;}
        });
        constructed.countDown();event("adapter-ready");
    }
    /** Component preparation never waits for Application.onCreate to finish on another process. */
    public void startApplication()throws Exception{
        awaitConstructed();
        if(Looper.myLooper()==Looper.getMainLooper())applicationStart.run();else new Handler(Looper.getMainLooper()).post(applicationStart);
        try{applicationStart.get(RuntimeStartupDeadline.remaining(startupDeadlineElapsed,SystemClock.elapsedRealtime()),java.util.concurrent.TimeUnit.MILLISECONDS);}
        catch(java.util.concurrent.TimeoutException timeout){RuntimeStartupDiagnostics.timeout();throw timeout;}
    }
    static void awaitConstructed()throws Exception{
        long deadline=RuntimeStartupDeadline.after(SystemClock.elapsedRealtime());RuntimeEngine engine;
        while((engine=current)==null){if(SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("ENGINE_CONSTRUCTION_TIMEOUT_UNKNOWN");Thread.sleep(10);}
        if(!engine.constructed.await(RuntimeStartupDeadline.remaining(engine.startupDeadlineElapsed,SystemClock.elapsedRealtime()),java.util.concurrent.TimeUnit.MILLISECONDS))throw new IllegalStateException("ENGINE_CONSTRUCTION_TIMEOUT_UNKNOWN");
    }
    Bundle brokerCall(int code,Bundle input)throws Exception{return RuntimeWire.call(broker,code,input);}
    private void recordStart(String stage)throws Exception{Bundle input=auth();input.putString("stage",stage);brokerCall(RuntimeWire.APPLICATION_START_EVENT,input);}
    boolean ownsLoader(ClassLoader candidate){for(ClassLoader c=candidate;c!=null;c=c.getParent())if(c==artifactLoader)return true;return false;}
    boolean ownsActivity(Activity activity){return activityInstances.containsKey(activity);}
    /** Failure diagnostics only: the guest Activities this engine constructed. */
    java.util.List<Activity> diagnosticActivities(){return new java.util.ArrayList<>(activityInstances.keySet());}
    Bundle auth(){Bundle b=new Bundle();b.putString("generation",generation);return b;}
    public synchronized ContentResolver settingsResolver(){if(settingsResolver==null){settingsProvider=new SettingsReadProvider(host.getContentResolver());android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="settings";info.exported=false;settingsProvider.attachInfo(host,info);ContentProvider router=providers.router();ProviderInfo routeInfo=new ProviderInfo();routeInfo.authority="tripoder.resolver";routeInfo.exported=false;router.attachInfo(host,routeInfo);settingsResolver=ContentResolver.wrap(router);}return settingsResolver;}
    public ContentProvider settingsProvider(){settingsResolver();return settingsProvider;}
    public boolean ownsResolver(ContentResolver resolver){return resolver==settingsResolver;}
    public void event(String stage){try{Bundle b=auth();b.putString("stage",stage);RuntimeWire.call(broker,RuntimeWire.EVENT,b);}catch(Exception e){throw new IllegalStateException("BROKER_EVENT_FAILED",e);}}
    RuntimeActivityCreation.Permit claimCreation(Intent intent)throws Exception {
        String ticket=RuntimeWire.required(intent.getExtras(),"tripoder.ticket");Bundle gate=auth();gate.putString("candidate",ticket);gate.putString("stub",intent.getComponent().getClassName());gate.putString("kind","factory");
        if(RuntimeWire.call(broker,RuntimeWire.ACTIVITY_ENTRY_STATUS,gate).getBoolean("closed"))return new RuntimeActivityCreation.Permit(ActivityEntryFences.REJECTED);
        String activityName=intent.getStringExtra("tripoder.component");
        if(!activityInfos.containsKey(activityName))throw new SecurityException("ACTIVITY_SESSION_MISMATCH");
        Intent visible=RuntimeActivityIntent.visible(intent,packageName,loader);
        String instance=UUID.randomUUID().toString();Bundle claim=auth();claim.putString("ticket",ticket);claim.putString("component",activityName);claim.putString("instance",instance);claim.putString("stub",intent.getComponent().getClassName());claim.putString("delivery",RuntimeWire.required(intent.getExtras(),"tripoder.delivery"));claim.putString("payloadDigest",RuntimeActivityIntent.receivedDigest(intent,visible));Bundle claimed=RuntimeWire.call(broker,RuntimeWire.ACTIVITY_CLAIM,claim);
        if(claimed.containsKey("rejectedActivity"))return new RuntimeActivityCreation.Permit(claimed.getString("rejectedActivity"));
        ActivityInfo effective=RuntimeActivityIndex.require(activityInfos,activityName);
        return new RuntimeActivityCreation.Permit(this,intent,visible,ticket,instance,effective.targetActivity==null?activityName:effective.targetActivity,claimed);
    }
    void registerCreation(RuntimeActivityCreation.Permit permit,Activity activity){
        Intent intent=permit.physical,visible=permit.visible;String activityName=RuntimeWire.required(intent.getExtras(),"tripoder.component");
        String callback=intent.getStringExtra("tripoder.callback"),op=intent.getStringExtra("tripoder.operation");
        if(callback!=null)callbacks.put(activity,new String[]{callback,op});
        activityInstances.put(activity,new String[]{permit.ticket,permit.instance});
        activityCallers.initial(activity,permit.caller);
        activityMetadata.put(activity,new ActivityInfo(activityInfos.get(activityName)));visibleIntents.put(activity,visible);
    }
    public void activityAttached(Activity activity){try{Bundle b=activityAuth(activity);b.putInt("taskId",activity.getTaskId());RuntimeWire.call(broker,RuntimeWire.ACTIVITY_ATTACHED,b);}catch(Exception error){throw new IllegalStateException("ACTIVITY_ATTACH_ACK_FAILED",error);}}
    public void activityCreated(Activity activity){try{Bundle b=activityAuth(activity);b.putInt("taskId",activity.getTaskId());RuntimeWire.call(broker,RuntimeWire.ACTIVITY_CREATED,b);}catch(Exception error){throw new IllegalStateException("ACTIVITY_CREATE_ACK_FAILED",error);}}
    public void activityResumed(Activity activity){try{RuntimeWire.call(broker,RuntimeWire.ACTIVITY_RESUMED,activityAuth(activity));}catch(Exception error){throw new IllegalStateException("ACTIVITY_RESUME_ACK_FAILED",error);}}
    public void activityDestroying(Activity activity){destructionTasks.put(activity,activity.getTaskId());}
    public void activityDestroyed(Activity activity){try{Integer task=destructionTasks.remove(activity);if(task==null)throw new IllegalStateException("ACTIVITY_PRE_DESTROY_MISSING");Bundle b=activityAuth(activity);b.putBoolean("finishing",activity.isFinishing());b.putInt("taskId",task);RuntimeWire.call(broker,RuntimeWire.ACTIVITY_DESTROYED,b);if(activity.isFinishing())pendingResults.remove(b.getString("ticket"));callbacks.remove(activity);visibleIntents.remove(activity);activityInstances.remove(activity);activityMetadata.remove(activity);activityCallers.remove(activity);returned.remove(activity);}catch(Exception error){throw new IllegalStateException("ACTIVITY_DESTROY_ACK_FAILED",error);}}
    private Bundle activityAuth(Activity activity){String[] identity=activityInstances.get(activity);if(identity==null)throw new SecurityException("ACTIVITY_INSTANCE_MISSING");Bundle b=auth();b.putString("ticket",identity[0]);b.putString("instance",identity[1]);b.putInt("taskId",activity.getTaskId());return b;}
    public ActivityInfo activityInfo(Activity activity){ActivityInfo declared=activityMetadata.get(activity);if(declared==null)throw new SecurityException("ACTIVITY_NOT_DECLARED_IN_PROCESS");return new ActivityInfo(declared);}
    public int theme(Activity activity){return activityInfo(activity).getThemeResource();}
    public Intent visibleIntent(Activity activity){Intent intent=visibleIntents.get(activity);if(intent==null)throw new SecurityException("ACTIVITY_NOT_REGISTERED");return intent;}
    public Intent newIntent(Activity activity,Intent physical,ComponentCaller caller)throws Exception{
        if(!ownsActivity(activity)||physical==null||physical.getComponent()==null||!host.getPackageName().equals(physical.getComponent().getPackageName()))throw new SecurityException("ACTIVITY_NEW_INTENT_TARGET_MISMATCH");
        Bundle admission=auth();admission.putString("candidate",RuntimeWire.required(physical.getExtras(),"tripoder.ticket"));admission.putString("stub",physical.getComponent().getClassName());
        if(brokerCall(RuntimeWire.ACTIVITY_ENTRY_STATUS,admission).getBoolean("closed")){android.util.Log.i("TripoderRuntime","ACTIVITY_CLOSED_NEW_INTENT_REJECTED:"+admission.getString("candidate"));return null;}
        Intent visible=RuntimeActivityIntent.visible(physical,packageName,loader);if(!ActivityComponentLedger.canonical(RuntimeActivityIndex.require(activityInfos,visible.getComponent().getClassName())).equals(ActivityComponentLedger.canonical(activityInfo(activity))))throw new SecurityException("ACTIVITY_NEW_INTENT_COMPONENT_MISMATCH");
        Bundle b=activityAuth(activity);b.putString("candidate",RuntimeWire.required(physical.getExtras(),"tripoder.ticket"));b.putString("delivery",RuntimeWire.required(physical.getExtras(),"tripoder.delivery"));b.putString("stub",physical.getComponent().getClassName());b.putString("payloadDigest",RuntimeActivityIntent.receivedDigest(physical,visible));
        Bundle reply=RuntimeWire.call(broker,RuntimeWire.ACTIVITY_NEW_INTENT,b);if(reply.getBoolean("closed")){android.util.Log.i("TripoderRuntime","ACTIVITY_CLOSED_NEW_INTENT_REJECTED:"+b.getString("candidate"));return null;}activityCallers.enter(activity,RuntimeWire.required(reply,"callerPackage"),caller);return visible;
    }
    public void newIntentComplete(Activity activity,String delivery,boolean success)throws Exception{try{Bundle b=activityAuth(activity);b.putString("delivery",delivery);b.putBoolean("success",success);RuntimeWire.call(broker,RuntimeWire.ACTIVITY_NEW_INTENT_ACK,b);}finally{activityCallers.leave(activity);}}
    public Intent route(Activity caller,Intent intent,int requestCode)throws Exception {
        if(!visibleIntents.containsKey(caller))throw new SecurityException("ACTIVITY_NOT_REGISTERED");
        Map<Integer,String> pending=pendingResults.computeIfAbsent(activityAuth(caller).getString("ticket"),k->new HashMap<>());
        if(requestCode>=0&&pending.containsKey(requestCode))throw new IllegalStateException("REQUEST_CODE_ALREADY_IN_FLIGHT");
        intent=RuntimeActivityIntent.snapshot(intent);
        String digest=RuntimeActivityIntent.digest(intent);
        Bundle request=activityAuth(caller);request.putString("package",intent.getComponent().getPackageName());request.putString("component",intent.getComponent().getClassName());request.putString("operation",UUID.randomUUID().toString());request.putString("requestDigest",digest);request.putBoolean("wantsResult",requestCode>=0);request.putInt("flags",intent.getFlags());
        Bundle response;
        try{response=RuntimeWire.call(broker,RuntimeWire.ROUTE,request);}catch(IllegalStateException e){if(String.valueOf(e.getMessage()).contains("PACKAGE_NOT_IN_SPACE"))throw new ActivityNotFoundException("PACKAGE_NOT_IN_SPACE:"+intent.getComponent().getPackageName());throw e;}
        if(requestCode>=0)pending.put(requestCode,RuntimeWire.required(response,"operation"));
        Intent routed=new Intent(intent).setComponent(new ComponentName(host,RuntimeWire.required(response,"stub")));
        routed.putExtra("tripoder.extras",intent.getExtras()!=null);
        routed.putExtra("tripoder.flags",intent.getFlags()).putExtra("tripoder.component",intent.getComponent().getClassName()).putExtra("tripoder.generation",RuntimeWire.required(response,"generation")).putExtra("tripoder.operation",RuntimeWire.required(response,"operation")).putExtra("tripoder.ticket",RuntimeWire.required(response,"ticket")).putExtra("tripoder.delivery",RuntimeWire.required(response,"delivery"));if(requestCode>=0)routed.putExtra("tripoder.callback",RuntimeWire.required(response,"callback"));return routed;
    }
    public void activityStartResult(Object value,int result)throws Exception{
        if(!(value instanceof Intent))throw new SecurityException("ACTIVITY_START_RESULT_INTENT_MISSING");
        Intent intent=(Intent)value;if(intent.getComponent()==null||!host.getPackageName().equals(intent.getComponent().getPackageName()))throw new SecurityException("ACTIVITY_START_RESULT_COMPONENT_MISMATCH");
        Bundle b=auth();b.putString("candidate",RuntimeWire.required(intent.getExtras(),"tripoder.ticket"));b.putString("delivery",RuntimeWire.required(intent.getExtras(),"tripoder.delivery"));b.putString("stub",intent.getComponent().getClassName());b.putInt("startResult",result);RuntimeWire.call(broker,RuntimeWire.ACTIVITY_START_RESULT,b);
    }
    public void returning(Activity activity,int resultCode,Intent result)throws Exception {
        if(returned.contains(activity))return;
        RuntimeActivityResult.require(result);
        String[] callback=callbacks.get(activity);if(callback==null){returned.add(activity);return;} // The controller receives the same checked result shape.
        Bundle request=auth();request.putString("callback",callback[0]);request.putString("operation",callback[1]);RuntimeWire.call(broker,RuntimeWire.RESULT,request);
        returned.add(activity);callbacks.remove(activity);
    }
    public Bundle check(Bundle input)throws Exception {Bundle b=auth();b.putString("callback",RuntimeWire.required(input,"callback"));b.putString("operation",RuntimeWire.required(input,"operation"));if(input.containsKey("overrideGeneration"))b.putString("generation",input.getString("overrideGeneration"));return RuntimeWire.call(broker,RuntimeWire.RESULT,b);}
    public String receiving(Activity caller,int requestCode,int resultCode,ComponentCaller physical)throws Exception {
        Bundle b=activityAuth(caller);Map<Integer,String> pending=pendingResults.get(b.getString("ticket"));String op=pending==null?null:pending.remove(requestCode);if(op==null)throw new SecurityException("UNREQUESTED_RESULT");
        b.putString("operation",op);b.putInt("resultCode",resultCode);Bundle accepted=RuntimeWire.call(broker,RuntimeWire.ACTIVITY_RESULT_ENTER,b);
        activityCallers.enterResult(caller,RuntimeWire.required(accepted,"callerPackage"),physical);return op;
    }
    public void received(Activity caller,String operation,int resultCode,boolean success)throws Exception {
        try{Bundle b=activityAuth(caller);b.putString("operation",operation);b.putInt("resultCode",resultCode);b.putBoolean("success",success);RuntimeWire.call(broker,RuntimeWire.RECEIVED,b);}
        finally{activityCallers.leave(caller);}
    }
}
