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
    public final ApplicationInfo applicationInfo;
    final PackageInfo packageArchive;
    final long firstInstallTime,lastUpdateTime;
    private final Map<String,ActivityInfo> activityInfos=new HashMap<>();
    public Application application;
    private ContentResolver settingsResolver;
    private SettingsReadProvider settingsProvider;
    private final IBinder broker;
    final RuntimeJobScheduler jobScheduler=new RuntimeJobScheduler(this);
    final RuntimeJobs jobs=new RuntimeJobs(this);
    final RuntimePendingIntents pendingIntents=new RuntimePendingIntents(this);
    final RuntimeAlarms alarms=new RuntimeAlarms(this);
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
    final RuntimeWebView webView=new RuntimeWebView(this);
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
    boolean ownsWindow(android.view.View root){
        if(root==null||!root.isAttachedToWindow()||root.getRootView()!=root)return false;
        if(!(root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams candidate))return false;
        for(Activity activity:activityInstances.keySet()){
            if(activity.isDestroyed()||activity.getWindow()==null)continue;
            android.view.View decor=activity.getWindow().peekDecorView();
            if(decor==null||!decor.isAttachedToWindow())continue;
            if(decor==root)return true;
            if(!(decor.getLayoutParams() instanceof android.view.WindowManager.LayoutParams owner))continue;
            // Dialogs have a separate DecorView and IWindow, but Android assigns
            // them the owning Activity's application token. Subwindows instead
            // carry the parent IWindow token. Never infer ownership from Context.
            if(candidate.type>=android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                    &&candidate.type<=android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW
                    &&owner.token!=null&&owner.token==candidate.token)return true;
            if(candidate.type>=android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW
                    &&candidate.type<=android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW
                    &&decor.getWindowToken()!=null&&decor.getWindowToken()==candidate.token)return true;
        }
        return false;
    }
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
        long bootStart=SystemClock.elapsedRealtime();
        File apk=new File(RuntimeWire.required(input,"apk"));
        String liteFile=input.getString("descriptorLiteFile"),liteSha=input.getString("descriptorLiteSha"),stampDigest=input.getString("artifactStamp");
        ArtifactSet artifacts=liteFile!=null&&liteSha!=null&&stampDigest!=null?ArtifactSet.loadTrusted(apk.getParentFile(),RuntimeWire.required(input,"descriptorSha"),liteFile,liteSha,stampDigest):ArtifactSet.loadVerified(apk.getParentFile(),RuntimeWire.required(input,"descriptorSha"));
        if(!artifacts.base.equals(apk)||!artifacts.parts.get(0).sha256.equals(RuntimeWire.required(input,"sha256")))throw new SecurityException("LOAD_DIGEST_MISMATCH");
        boot("verify",bootStart);
        platformDefaultApplication=artifacts.baseManifest.applicationName.isEmpty()||artifacts.baseManifest.applicationName.equals(Application.class.getName());
        if(artifacts.parts.isEmpty())throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        storage=new RuntimeStorage(host,input);data=storage.root;
        PackageInfo pkg=host.getPackageManager().getPackageArchiveInfo(apk.toString(),PackageManager.GET_CONFIGURATIONS|PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS|PackageManager.GET_META_DATA|PackageManager.GET_PERMISSIONS|PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_SIGNATURES|PackageManager.GET_URI_PERMISSION_PATTERNS|PackageManager.MATCH_DISABLED_COMPONENTS);if(pkg==null||!pkg.packageName.equals(packageName))throw new SecurityException("LOAD_PACKAGE_MISMATCH");
        RuntimeManifestIndex.includeSplits(artifacts,pkg);
        boot("archiveInfo",bootStart);
        if(pkg.signingInfo==null)throw new SecurityException("PACKAGE_SIGNING_METADATA_MISSING");
        List<String> signers=new ArrayList<>();for(Signature signature:pkg.signingInfo.getApkContentsSigners())signers.add(ArtifactSet.hex(java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));Collections.sort(signers);
        if(!signers.equals(artifacts.signerDigests))throw new SecurityException("PACKAGE_SIGNING_METADATA_MISMATCH");packageArchive=pkg;
        requestedPermissions=pkg.requestedPermissions==null?Set.of():Set.copyOf(Arrays.asList(pkg.requestedPermissions));
        info=new ApplicationInfo(pkg.applicationInfo);info.sourceDir=apk.toString();info.publicSourceDir=apk.toString();info.dataDir=data.toString();info.deviceProtectedDataDir=new File(data,"device-protected").toString();info.nativeLibraryDir=new File(apk.getParentFile(),"lib").getCanonicalPath();info.uid=android.os.Process.myUid();GuestPackageManager.setPrimaryCpuAbi(info,"arm64-v8a");
        applicationInfo=GuestPackageManager.copyApplicationInfo(info,true);
        for(ActivityInfo activity:RuntimeActivityIndex.create(artifacts,pkg).values())if(processName.equals(activity.processName))activityInfos.put(activity.name,activity);
        boot("activityIndex",bootStart);
        artifactLoader=RuntimeDexLoader.create(artifacts,info.nativeLibraryDir,host.getClassLoader());loader=artifactLoader;
        boot("dexLoader",bootStart);
        resources=host.getPackageManager().getResourcesForApplication(info);
        boot("resources",bootStart);
        var declarations=RuntimeManifestIndex.resolve(artifacts,pkg);manifestDeclarations=artifacts.components;filterSpecs=artifacts.filterSpecs;providerIndex=new RuntimeProviderIndex(pkg.providers,declarations);serviceIndex=new RuntimeServiceIndex(pkg.services,declarations);
        providers=new RuntimeProviders(this,pkg.providers,java.util.Objects.requireNonNull(input.getBundle("providers")),declarations);
        boot("indexes",bootStart);
        packages.captureInfrastructure();webView.capture();
        boot("infrastructure",bootStart);
        broker=input.getBinder("broker");if(broker==null)throw new SecurityException("BROKER_MISSING");hooks=new RuntimeHookBridge(this,input,artifacts);current=this;
        // An old controller cannot be replaced under a still-executing guest identity.
        broker.linkToDeath(()->android.os.Process.killProcess(android.os.Process.myPid()),0);
        Bundle registration=auth();registration.putString("bootstrap",RuntimeWire.required(input,"bootstrap"));RuntimeWire.call(broker,RuntimeWire.REGISTER,registration);
        RuntimeExitHistory.stamp(host,generation,data.getName());
        boot("register",bootStart);
        System.loadLibrary("tripoderselfhook");
        RuntimeSelfHookProduct.install(this);
        boot("hooks",bootStart);
        if(!adapterReady)throw new IllegalStateException("SELF_HOOK_READY_PUBLICATION_MISSING");
        installWindowSessionProxy();
        RuntimeFixedPreparation.neutralizeVendorMonitors();
        Thread sampler = new Thread(() -> {
            Thread main = Looper.getMainLooper().getThread();
            while (true) {
                try {
                    Thread.sleep(1000);
                    StackTraceElement[] stack = main.getStackTrace();
                    if (stack != null && stack.length > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Math.min(stack.length, 15); i++) {
                            sb.append("\n  at ").append(stack[i]);
                        }
                        android.util.Log.i("TripoderSampler", "MainThread[" + processName + "]:" + sb);
                    }
                } catch (Throwable ignored) {}
            }
        }, "Tripoder-MainSampler");
        sampler.setDaemon(true);
        sampler.start();
        applicationStart=new java.util.concurrent.FutureTask<>(()->{
            recordStart("STARTING");
            try{
            Thread.currentThread().setContextClassLoader(loader);
            componentFactory=info.appComponentFactory==null?new AppComponentFactory():(AppComponentFactory)loader.loadClass(info.appComponentFactory).getDeclaredConstructor().newInstance();
            ClassLoader selected=componentFactory.instantiateClassLoader(loader,GuestPackageManager.copyApplicationInfo(info,true));if(!ownsLoader(selected))throw new SecurityException("FACTORY_CLASS_LOADER_OUTSIDE_ARTIFACT");loader=selected;Thread.currentThread().setContextClassLoader(loader);
            String appName=RuntimeWire.required(input,"application");
            boolean bypassShell=false;
            if(appName.equals("com.netease.nis.wrapper.MyApplication")){appName="com.netease.cloudmusic.CloudMusicApplication";bypassShell=true;} // TEST: bypass NIS shell
            updateActivityThreadProcessName(processName);
            application=componentFactory.instantiateApplication(loader,appName);
            if(application==null)throw new IllegalStateException("FACTORY_APPLICATION_MISSING");
            RuntimeBridge.observeApplicationOwnership("before-attach",host,application);
            Context logical=RuntimeBridge.createApplicationContext(host,application,GuestPackageManager.copyApplicationInfo(info,true),loader,componentFactory,resources);
            Context guestContext=GuestContext.application(logical,this);
            if (bypassShell) {

                try {
                    Class<?> wrapperCls = loader.loadClass("com.netease.cloudmusic.common.ApplicationWrapper");
                    java.lang.reflect.Method getInstance = wrapperCls.getMethod("getInstance");
                    Object wrapper = getInstance.invoke(null);
                    if (wrapper instanceof ContextWrapper cw && cw.getBaseContext() == null) {
                        java.lang.reflect.Method attach = wrapperCls.getMethod("attachBaseContext", Context.class);
                        attach.invoke(wrapper, application);
                        android.util.Log.i("TripoderHardened", "Pre-attached ApplicationWrapper with application");
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TripoderHardened", "Failed to pre-attach ApplicationWrapper", t);
                }
                try {
                    Class<?> oCls = loader.loadClass("ej.o");
                    Class<?> hCls = loader.loadClass("ej.h");
                    Class<?> iCls = loader.loadClass("ej.i");
                    java.lang.reflect.Field lField = oCls.getDeclaredField("l");
                    lField.setAccessible(true);
                    Object lazyVal = lField.get(null);
                    if (lazyVal != null) {
                        java.lang.reflect.Method getVal = lazyVal.getClass().getMethod("getValue");
                        Object iInst = getVal.invoke(lazyVal);
                        java.lang.reflect.Field aField = iCls.getDeclaredField("a");
                        aField.setAccessible(true);
                        Object origH = aField.get(iInst);
                        Object proxyH = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{hCls}, (proxy, method, args) -> {
                            if ("c".equals(method.getName()) && args != null && args.length >= 2) {
                                Throwable err = (Throwable) args[0];
                                Object task = args[1];
                                String taskName = "unknown";
                                try {
                                    taskName = (String) task.getClass().getMethod("taskName").invoke(task);
                                } catch (Throwable ignored) {}
                                android.util.Log.e("TripoderHardened", "!!! STARTUP TASK FAILED: " + taskName, err);
                            }
                            return method.invoke(origH, args);
                        });
                        aField.set(iInst, proxyH);
                        android.util.Log.i("TripoderHardened", "Successfully installed StartupManager error proxy on ej.i.a");
                    }
                } catch (Throwable t) {
                    android.util.Log.w("TripoderHardened", "Failed to install ej.i.a proxy", t);
                }
            }
            try{RuntimeBridge.attachApplication(application,guestContext);}
            finally{RuntimeBridge.observeApplicationOwnership("after-attach",host,application);}
            RuntimeBridge.checkApplicationOwnership(host,application);
            application.registerActivityLifecycleCallbacks((RuntimeApplication)host);
            boolean deferredProviders = !bypassShell && isHardenedShell(application, loader, info);

            if (!deferredProviders) {
                providers.initialize();
            }
            recordStart("ON_CREATE");
            try{new Instrumentation().callApplicationOnCreate(application);}
            finally{RuntimeBridge.observeApplicationOwnership("after-onCreate",host,application);}
            if (deferredProviders) {
                providers.initialize();
            }
            application.unregisterActivityLifecycleCallbacks((RuntimeApplication)host);
            application.registerActivityLifecycleCallbacks((RuntimeApplication)host);
            RuntimeBridge.checkApplicationOwnership(host,application);
            Thread scoutImmunity = new Thread(() -> {
                for (int i = 0; i < 30; i++) {
                    RuntimeFixedPreparation.neutralizeVendorMonitors();
                    try { Thread.sleep(1000); } catch (Throwable ignored) {}
                }
            }, "Tripoder-Scout-Immunity");
            scoutImmunity.setDaemon(true);
            scoutImmunity.start();
            recordStart("READY");ready=true;event("application-ready");return null;
            }catch(Exception|Error error){try{recordStart("FAILED");}catch(Exception receipt){error.addSuppressed(receipt);}throw error;}
        });
        constructed.countDown();event("adapter-ready");
    }
    private static boolean isHardenedShell(Application application, ClassLoader loader, ApplicationInfo info) {
        if (application == null) return false;
        String clsName = application.getClass().getName();
        if (clsName.contains(".wrapper.") || clsName.contains(".stub.") || clsName.contains(".StubShell.")
            || clsName.startsWith("com.secneo.") || clsName.startsWith("com.ijiami.") || clsName.startsWith("com.qihoo.util.")) {
            return true;
        }
        for (String fieldName : new String[]{"strAppName", "APPLICATION_CLASS_NAME", "APP_NAME", "realApplication", "newApp"}) {
            try {
                java.lang.reflect.Field f = application.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object val = f.get(application);
                if (val instanceof String s && !s.isEmpty() && !s.equals(clsName)) return true;
                if (val instanceof Application && val != application) return true;
            } catch (Throwable ignored) {}
        }
        if (info != null && info.metaData != null) {
            for (String metaKey : new String[]{"app_name", "APPLICATION_CLASS_NAME", "android.app.Application", "real_application"}) {
                String val = info.metaData.getString(metaKey);
                if (val != null && !val.isEmpty() && !val.equals(clsName)) return true;
            }
        }
        return false;
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
    private volatile byte[] guestProcessStateSummary;
    void setGuestProcessStateSummary(byte[] summary){if(summary!=null&&summary.length>128)throw new IllegalArgumentException("PROCESS_STATE_SUMMARY_TOO_LARGE");guestProcessStateSummary=summary==null?null:summary.clone();}
    private static void boot(String stage,long start){android.util.Log.i("TripoderBoot",stage+" +"+(SystemClock.elapsedRealtime()-start)+"ms");}
    /** The base manifest's components with their full declaration trees (intent filters included); the resolved index used elsewhere keeps only identity and state. */
    volatile List<ApkManifest.Component> manifestDeclarations=List.of();
    volatile Map<String,List<ArtifactSet.FilterSpec>> filterSpecs=Map.of();
    boolean isBroker(Object value){return value!=null&&value==broker;}
    boolean ownsLoader(ClassLoader candidate){for(ClassLoader c=candidate;c!=null;c=c.getParent())if(c==artifactLoader)return true;return false;}
    /** Guest code, or the exact platform WebView provider loader captured as an explicit dependency. */
    boolean ownsReceiverLoader(ClassLoader candidate){return ownsLoader(candidate)||webView.ownsProviderLoader(candidate);}
    boolean ownsActivity(Activity activity){return activityInstances.containsKey(activity);}
    /** Failure diagnostics only: the guest Activities this engine constructed. */
    java.util.List<Activity> diagnosticActivities(){return new java.util.ArrayList<>(activityInstances.keySet());}
    Bundle auth(){Bundle b=new Bundle();b.putString("generation",generation);return b;}
    public synchronized ContentResolver settingsResolver(){if(settingsResolver==null){settingsProvider=new SettingsReadProvider(host.getContentResolver());android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="settings";info.exported=false;settingsProvider.attachInfo(host,info);ContentProvider router=providers.router();ProviderInfo routeInfo=new ProviderInfo();routeInfo.authority="tripoder.resolver";routeInfo.exported=false;router.attachInfo(host,routeInfo);settingsResolver=ContentResolver.wrap(router);}return settingsResolver;}
    public ContentProvider settingsProvider(){settingsResolver();return settingsProvider;}
    public boolean ownsResolver(ContentResolver resolver){return resolver==settingsResolver;}
    public void event(String stage){try{Bundle b=auth();b.putString("stage",stage);RuntimeWire.call(broker,RuntimeWire.EVENT,b);}catch(Exception e){throw new IllegalStateException("BROKER_EVENT_FAILED",e);}}
    RuntimeActivityCreation.Permit claimCreation(Intent intent)throws Exception {
        // Bundle values unparcel lazily and keep the loader seen at their first access; own it before any extras read.
        intent.setExtrasClassLoader(loader);
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
    private final java.util.Set<Activity> createdActivities=java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    public void activityCreated(Activity activity){if(!createdActivities.add(activity))return;applyGuestWindowInsetsIfNeeded(activity);try{Bundle b=activityAuth(activity);b.putInt("taskId",activity.getTaskId());RuntimeWire.call(broker,RuntimeWire.ACTIVITY_CREATED,b);}catch(Exception error){throw new IllegalStateException("ACTIVITY_CREATE_ACK_FAILED",error);}}
    private void applyGuestWindowInsetsIfNeeded(Activity activity){
        if(info==null||info.targetSdkVersion<=0||info.targetSdkVersion>=35)return;
        android.view.Window window=activity.getWindow();if(window==null)return;
        android.view.View content=window.findViewById(android.R.id.content);if(content==null)return;
        content.setOnApplyWindowInsetsListener((view,windowInsets)->{
            android.view.Window win=activity.getWindow();
            boolean fullscreen=win!=null&&(win.getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)!=0;
            if(fullscreen)view.setPadding(0,0,0,0);
            else{
                android.graphics.Insets bars=windowInsets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            }
            return windowInsets;
        });
        content.requestApplyInsets();
    }
    public void activityResumed(Activity activity){try{RuntimeWire.call(broker,RuntimeWire.ACTIVITY_RESUMED,activityAuth(activity));}catch(Exception error){throw new IllegalStateException("ACTIVITY_RESUME_ACK_FAILED",error);}}
    public void activityDestroying(Activity activity){destructionTasks.put(activity,activity.getTaskId());}
    public void activityDestroyed(Activity activity){try{Integer task=destructionTasks.remove(activity);if(task==null)throw new IllegalStateException("ACTIVITY_PRE_DESTROY_MISSING");Bundle b=activityAuth(activity);b.putBoolean("finishing",activity.isFinishing());b.putInt("taskId",task);RuntimeWire.call(broker,RuntimeWire.ACTIVITY_DESTROYED,b);if(activity.isFinishing())pendingResults.remove(b.getString("ticket"));callbacks.remove(activity);visibleIntents.remove(activity);activityInstances.remove(activity);activityMetadata.remove(activity);activityCallers.remove(activity);returned.remove(activity);createdActivities.remove(activity);}catch(Exception error){throw new IllegalStateException("ACTIVITY_DESTROY_ACK_FAILED",error);}}
    private Bundle activityAuth(Activity activity){String[] identity=activityInstances.get(activity);if(identity==null)throw new SecurityException("ACTIVITY_INSTANCE_MISSING");Bundle b=auth();b.putString("ticket",identity[0]);b.putString("instance",identity[1]);b.putInt("taskId",activity.getTaskId());return b;}
    public ActivityInfo activityInfo(Activity activity){ActivityInfo declared=activityMetadata.get(activity);if(declared==null)throw new SecurityException("ACTIVITY_NOT_DECLARED_IN_PROCESS");return new ActivityInfo(declared);}
    public int theme(Activity activity){return activityInfo(activity).getThemeResource();}
    public Intent visibleIntent(Activity activity){Intent intent=visibleIntents.get(activity);if(intent==null)throw new SecurityException("ACTIVITY_NOT_REGISTERED");return intent;}
    public Intent newIntent(Activity activity,Intent physical,ComponentCaller caller)throws Exception{
        if(!ownsActivity(activity)||physical==null||physical.getComponent()==null||!host.getPackageName().equals(physical.getComponent().getPackageName()))throw new SecurityException("ACTIVITY_NEW_INTENT_TARGET_MISMATCH");
        physical.setExtrasClassLoader(loader);
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
        Bundle origExtras=intent.getExtras();routed.putExtra("tripoder.extras",origExtras!=null&&!origExtras.isEmpty());
        routed.putExtra("tripoder.flags",intent.getFlags()).putExtra("tripoder.component",intent.getComponent().getClassName()).putExtra("tripoder.generation",RuntimeWire.required(response,"generation")).putExtra("tripoder.operation",RuntimeWire.required(response,"operation")).putExtra("tripoder.ticket",RuntimeWire.required(response,"ticket")).putExtra("tripoder.delivery",RuntimeWire.required(response,"delivery"));if(requestCode>=0)routed.putExtra("tripoder.callback",RuntimeWire.required(response,"callback"));return routed;
    }
    /** A start requested through a non-Activity Context: a new task begun by the process itself, never a result request. */
    public Intent routeFromContext(Intent intent)throws Exception{
        if(intent.getComponent()==null)throw new IllegalArgumentException("ACTIVITY_COMPONENT_REQUIRED");
        intent=RuntimeActivityIntent.snapshot(intent);
        if((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)==0)throw new android.util.AndroidRuntimeException("Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag. Is this really what you want?");
        Bundle request=auth();request.putBoolean("fromContext",true);request.putString("package",intent.getComponent().getPackageName());request.putString("component",intent.getComponent().getClassName());request.putString("operation",UUID.randomUUID().toString());request.putString("requestDigest",RuntimeActivityIntent.digest(intent));request.putBoolean("wantsResult",false);request.putInt("flags",intent.getFlags());
        Bundle response;
        try{response=RuntimeWire.call(broker,RuntimeWire.ROUTE,request);}catch(IllegalStateException e){if(String.valueOf(e.getMessage()).contains("PACKAGE_NOT_IN_SPACE"))throw new ActivityNotFoundException("PACKAGE_NOT_IN_SPACE:"+intent.getComponent().getPackageName());throw e;}
        Intent routed=new Intent(intent).setComponent(new ComponentName(host,RuntimeWire.required(response,"stub")));
        Bundle origExtras=intent.getExtras();routed.putExtra("tripoder.extras",origExtras!=null&&!origExtras.isEmpty());
        routed.putExtra("tripoder.flags",intent.getFlags()).putExtra("tripoder.component",intent.getComponent().getClassName()).putExtra("tripoder.generation",RuntimeWire.required(response,"generation")).putExtra("tripoder.operation",RuntimeWire.required(response,"operation")).putExtra("tripoder.ticket",RuntimeWire.required(response,"ticket")).putExtra("tripoder.delivery",RuntimeWire.required(response,"delivery"));return routed;
    }
    /** The host's own components are never a guest start target: the guest shares the host UID, so this is a policy boundary, not a kernel one. */
    public boolean targetsHost(Intent intent){
        if(intent==null)return false;ComponentName component=intent.getComponent();String pkg=component!=null?component.getPackageName():intent.getPackage();
        return pkg!=null&&host.getPackageName().equals(pkg);
    }
    public void activityStartResult(Object value,int result)throws Exception{
        if(!(value instanceof Intent))return;
        Intent intent=(Intent)value;if(intent.getComponent()==null||!host.getPackageName().equals(intent.getComponent().getPackageName()))return;
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
    private final Map<String,Set<Integer>> systemResults=new HashMap<>();
    /** Results the platform itself will deliver (not routed through the authority): remembered per Activity ticket and request code. */
    /** A fired activity/service PendingIntent: the guest application itself starts the component, from its own Context, so the start takes the ordinary guest route. */
    void dispatchPending(String kind,byte[] payload)throws Exception{
        if(!"activity".equals(kind)&&!"service".equals(kind))throw new IllegalArgumentException("PENDING_DISPATCH_KIND");
        Intent intent=RuntimeBroadcastIntent.decode(payload);Application app=application;if(app==null)throw new IllegalStateException("PENDING_DISPATCH_APPLICATION_NOT_READY");
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);Throwable[] failure=new Throwable[1];
        new Handler(Looper.getMainLooper()).post(()->{
            try{if(kind.equals("activity"))app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));else app.startService(intent);}
            catch(Throwable error){failure[0]=error;}finally{done.countDown();}
        });
        if(!done.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("PENDING_DISPATCH_TIMEOUT");
        if(failure[0]!=null)throw new IllegalStateException("PENDING_DISPATCH_FAILED:"+failure[0],failure[0]);
    }
    void noteSystemResult(Activity caller,int requestCode)throws Exception{
        String ticket=activityAuth(caller).getString("ticket");synchronized(systemResults){systemResults.computeIfAbsent(ticket,k->new HashSet<>()).add(requestCode);}
    }
    public String receiving(Activity caller,int requestCode,int resultCode,ComponentCaller physical)throws Exception {
        Bundle b=activityAuth(caller);
        synchronized(systemResults){Set<Integer> mine=systemResults.get(b.getString("ticket"));if(mine!=null&&mine.remove(requestCode))return "";}Map<Integer,String> pending=pendingResults.get(b.getString("ticket"));String op=pending==null?null:pending.remove(requestCode);if(op==null)throw new SecurityException("UNREQUESTED_RESULT");
        b.putString("operation",op);b.putInt("resultCode",resultCode);Bundle accepted=RuntimeWire.call(broker,RuntimeWire.ACTIVITY_RESULT_ENTER,b);
        activityCallers.enterResult(caller,RuntimeWire.required(accepted,"callerPackage"),physical);return op;
    }
    public void received(Activity caller,String operation,int resultCode,boolean success)throws Exception {
        if(operation.isEmpty())return;// a platform result has no authority operation to acknowledge
        try{Bundle b=activityAuth(caller);b.putString("operation",operation);b.putInt("resultCode",resultCode);b.putBoolean("success",success);RuntimeWire.call(broker,RuntimeWire.RECEIVED,b);}
        finally{activityCallers.leave(caller);}
    }
    static void updateActivityThreadProcessName(String processName) {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cat = atCls.getDeclaredMethod("currentActivityThread");
            cat.setAccessible(true);
            Object at = cat.invoke(null);
            if (at != null) {
                java.lang.reflect.Field mBoundAppField = atCls.getDeclaredField("mBoundApplication");
                mBoundAppField.setAccessible(true);
                Object boundApp = mBoundAppField.get(at);
                if (boundApp != null) {
                    java.lang.reflect.Field procNameField = boundApp.getClass().getDeclaredField("processName");
                    procNameField.setAccessible(true);
                    procNameField.set(boundApp, processName);
                    java.lang.reflect.Field appInfoField = boundApp.getClass().getDeclaredField("appInfo");
                    appInfoField.setAccessible(true);
                    ApplicationInfo boundInfo = (ApplicationInfo) appInfoField.get(boundApp);
                    if (boundInfo != null) {
                        boundInfo.processName = processName;
                    }
                    android.util.Log.i("TripoderHardened", "Updated ActivityThread mBoundApplication.processName to " + processName);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("TripoderHardened", "Failed to update mBoundApplication.processName", t);
        }
    }
    private void installWindowSessionProxy() {
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            java.lang.reflect.Method getSessionMethod = wmgClass.getMethod("getWindowSession");
            Object realSession = getSessionMethod.invoke(null);
            Class<?> sessionInterface = Class.forName("android.view.IWindowSession");
            java.lang.reflect.Field sessionField = wmgClass.getDeclaredField("sWindowSession");
            sessionField.setAccessible(true);
            String hostPkg = host.getPackageName();
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                wmgClass.getClassLoader(),
                new Class<?>[]{sessionInterface},
                (p, method, args) -> {
                    String mName = method.getName();
                    if ("asBinder".equals(mName)) return ((IInterface) realSession).asBinder();
                    if ("equals".equals(mName) && args != null && args.length == 1) return p == args[0];
                    if ("hashCode".equals(mName)) return System.identityHashCode(p);
                    if (args != null) {
                        for (Object arg : args) {
                            if (arg instanceof android.view.WindowManager.LayoutParams params) {
                                params.packageName = hostPkg;
                            }
                        }
                    }
                    try {
                        return method.invoke(realSession, args);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                }
            );
            sessionField.set(null, proxy);
            android.util.Log.i("TripoderHardened", "Installed IWindowSession proxy for host package: " + hostPkg);
        } catch (Throwable t) {
            android.util.Log.e("TripoderHardened", "Failed to install IWindowSession proxy", t);
        }
    }
}

