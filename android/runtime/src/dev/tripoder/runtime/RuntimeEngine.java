package dev.tripoder.runtime;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import org.json.JSONObject;

/** One immutable application identity per process. Not a hostile-code security sandbox. */
public final class RuntimeEngine {
    public static volatile RuntimeEngine current;
    public static volatile boolean adapterReady;
    public final Context host;
    public final String generation,packageName,processName;
    final String installGeneration;
    public final File data;
    final File artifactDirectory;
    final String artifactCanonicalDirectory;
    final String dataCanonicalDirectory;
    final RuntimeExecutionCode executionCode;
    final RuntimeSharedLibraries sharedLibraries;
    final RuntimeStorage storage;
    final RuntimeHookBridge hooks;
    public ClassLoader loader;
    private final ClassLoader artifactLoader;
    AppComponentFactory componentFactory;
    public final Resources resources;
    final dev.tripoder.selfhook.ResourceFrameworkAccess.AssetSnapshot resourceAssets;
    public final ApplicationInfo info;
    public final ApplicationInfo applicationInfo;
    final PackageInfo packageArchive;
    final long firstInstallTime,lastUpdateTime;
    private final Map<String,ActivityInfo> activityInfos=new HashMap<>();
    public Application application;
    /**
     * The application the guest installed as the process application. A hot-fix or plugin proxy may replace the
     * {@link android.app.LoadedApk} application with its own delegate during {@code onCreate}; the created instance
     * stays the ownership anchor, while components created afterwards run with the installed application exactly as
     * they do on a device. Null means the created instance is still the process application.
     */
    private Application published;
    Application guestApplication(){return published!=null?published:application;}
    private volatile boolean applicationAttached;
    /** LoadedApk publishes its Application only after Instrumentation.newApplication (including attach) returns. */
    Application applicationContext(){return applicationAttached?RuntimeSelfHookApplication.installedApplication():null;}
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
    final RuntimeTaskPresentation taskPresentation=new RuntimeTaskPresentation(this);
    final RuntimeConnectivity connectivity=new RuntimeConnectivity(this);
    final RuntimeTelephony telephony=new RuntimeTelephony(this);
    final RuntimeAudio audio=new RuntimeAudio(this);
    final RuntimeBattery battery=new RuntimeBattery(this);
    final RuntimeLocation location=new RuntimeLocation(this);
    final RuntimePower power=new RuntimePower(this);
    final RuntimeUsers users=new RuntimeUsers(this);
    final RuntimeRoles roles=new RuntimeRoles(this);
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
    private record ActivityInstance(String ticket,String instance,ComponentName physicalComponent){}
    private final Map<Activity,ActivityInstance> activityInstances=new IdentityHashMap<>();
    private final Map<Activity,ActivityInfo> activityMetadata=new IdentityHashMap<>();
    private final Map<Activity,Integer> destructionTasks=new IdentityHashMap<>();
    final RuntimeActivityCallers activityCallers=new RuntimeActivityCallers();
    private final Set<Activity> returned=Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Map<String,Map<Integer,String>> pendingResults=new HashMap<>();
    volatile boolean ready;
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
    /** Fixed for this process by the broker's load bundle; subsystems that have a thin implementation route on it (docs/thin-shim-transformation.md). */
    final boolean traceExit;
    final boolean traceStartup;
    final String exitTracePath;
    private volatile RuntimeCodeProbes codeProbes;
    private final IdentityHashMap<ClassLoader,String> loaderReferences=new IdentityHashMap<>();
    String loaderReference(ClassLoader loader){synchronized(loaderReferences){String id=loaderReferences.get(loader);if(id==null){if(loaderReferences.size()>=128)throw new IllegalStateException("CLASS_LOADER_CAPACITY");id=UUID.randomUUID().toString();loaderReferences.put(loader,id);}return id;}}
    synchronized RuntimeCodeProbes codeProbes()throws Exception{if(codeProbes==null)codeProbes=new RuntimeCodeProbes(this,null);return codeProbes;}
    RuntimeCodeProbes existingCodeProbes(){return codeProbes;}
    android.os.ParcelFileDescriptor artifactDirectory()throws Exception{return RuntimeWire.call(broker,RuntimeWire.CODE_ARTIFACT_DIRECTORY,auth()).getParcelable("directory",android.os.ParcelFileDescriptor.class);}
    final boolean platformDefaultApplication;
    public RuntimeEngine(Context host,Bundle input)throws Exception {
        if(current!=null)throw new IllegalStateException("PROCESS_IDENTITY_IMMUTABLE");
        RuntimePlatform.requireValidated();
        startupDeadlineElapsed=input.getLong("startupDeadlineElapsed");
        if(RuntimeStartupDeadline.remaining(startupDeadlineElapsed,SystemClock.elapsedRealtime())==0)throw new IllegalStateException("PROCESS_START_TIMEOUT_UNKNOWN");
        this.host=host;installGeneration=RuntimeWire.required(input,"installGeneration");generation=RuntimeWire.required(input,"generation");packageName=RuntimeWire.required(input,"package");processName=RuntimeWire.required(input,"processName");
        android.os.Bundle diagnostics=host.getPackageManager().getApplicationInfo(host.getPackageName(),PackageManager.GET_META_DATA).metaData;
        traceStartup=diagnostics!=null&&diagnostics.getBoolean("tripoder.diagnostics.startup",false);
        traceExit=traceStartup;
        exitTracePath=traceExit?new File(host.getExternalFilesDir(null),"native-exit-"+generation).toString():null;
        android.util.Log.i("TripoderBoot","exitTracePath="+exitTracePath);
        firstInstallTime=input.getLong("firstInstallTime");lastUpdateTime=input.getLong("lastUpdateTime");
        long bootStart=SystemClock.elapsedRealtime();
        File apk=new File(RuntimeWire.required(input,"apk"));
        artifactDirectory=apk.getParentFile();
        artifactCanonicalDirectory=artifactDirectory.getCanonicalPath();
        String liteFile=input.getString("descriptorLiteFile"),liteSha=input.getString("descriptorLiteSha"),stampDigest=input.getString("artifactStamp");
        boolean trustedLoad=liteFile!=null&&liteSha!=null&&stampDigest!=null;
        ArtifactSet artifacts=trustedLoad?ArtifactSet.loadTrusted(apk.getParentFile(),RuntimeWire.required(input,"descriptorSha"),liteFile,liteSha,stampDigest):ArtifactSet.loadVerified(apk.getParentFile(),RuntimeWire.required(input,"descriptorSha"));
        if(!artifacts.base.equals(apk)||!artifacts.parts.get(0).sha256.equals(RuntimeWire.required(input,"sha256")))throw new SecurityException("LOAD_DIGEST_MISMATCH");
        boot("verify",bootStart);
        platformDefaultApplication=artifacts.baseManifest.applicationName.isEmpty()||artifacts.baseManifest.applicationName.equals(Application.class.getName());
        if(artifacts.parts.isEmpty())throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        storage=new RuntimeStorage(host,input);data=storage.root;dataCanonicalDirectory=data.getCanonicalPath();
        int archiveFlags=PackageManager.GET_CONFIGURATIONS|PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS|PackageManager.GET_META_DATA|PackageManager.GET_PERMISSIONS|PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_SIGNATURES|PackageManager.GET_URI_PERMISSION_PATTERNS|PackageManager.MATCH_DISABLED_COMPONENTS;
        PackageInfo pkg=host.getPackageManager().getPackageArchiveInfo(apk.toString(),archiveFlags);if(pkg==null||!pkg.packageName.equals(packageName))throw new SecurityException("LOAD_PACKAGE_MISMATCH");
        RuntimeManifestIndex.includeSplits(artifacts,pkg);
        boot("archiveInfo",bootStart);
        if(!trustedLoad){
            if(pkg.signingInfo==null)throw new SecurityException("PACKAGE_SIGNING_METADATA_MISSING");
            List<String> signers=new ArrayList<>();for(Signature signature:pkg.signingInfo.getApkContentsSigners())signers.add(ArtifactSet.hex(java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));Collections.sort(signers);
            if(!signers.equals(artifacts.signerDigests))throw new SecurityException("PACKAGE_SIGNING_METADATA_MISMATCH");
        }
        packageArchive=pkg;
        requestedPermissions=pkg.requestedPermissions==null?Set.of():Set.copyOf(Arrays.asList(pkg.requestedPermissions));
        // ApplicationInfo's copy constructor copies its own fields but not PackageItemInfo.metaData. The archive
        // metadata is part of the verified manifest an installed application receives from PackageManager, so carry it
        // explicitly; otherwise every guest reading its own <meta-data> sees null (Alipay's logging key, for example).
        info=new ApplicationInfo(pkg.applicationInfo);info.metaData=pkg.applicationInfo.metaData==null?null:new android.os.Bundle(pkg.applicationInfo.metaData);info.sourceDir=apk.getCanonicalPath();info.publicSourceDir=info.sourceDir;info.dataDir=data.toString();info.deviceProtectedDataDir=new File(data,"device-protected").toString();        File libDir=new File(apk.getParentFile(),"lib");
        File nativeDir=new File(libDir,"arm64");
        if(!nativeDir.isDirectory())nativeDir=new File(libDir,"arm64-v8a");
        if(!nativeDir.isDirectory())nativeDir=new File(libDir,"arm");
        if(!nativeDir.isDirectory())nativeDir=new File(libDir,"armeabi-v7a");
        if(!nativeDir.isDirectory())nativeDir=libDir;
        info.nativeLibraryDir=nativeDir.getCanonicalPath();info.uid=android.os.Process.myUid();GuestPackageManager.setPrimaryCpuAbi(info,"arm64-v8a");
        current=this;
        executionCode=RuntimeExecutionCode.prepare(artifacts,data,installGeneration);
        boot("executionCode",bootStart);
        applicationInfo=GuestPackageManager.copyApplicationInfo(info,true);
        // Establish the code namespace before ART/linker see the first guest path.
        System.loadLibrary("tripoderselfhook");boot("loadLibrary",bootStart);RuntimeSelfHookStorage.install();boot("nativeStorage",bootStart);
        for(ActivityInfo activity:RuntimeActivityIndex.create(artifacts,pkg).values())if(processName.equals(activity.processName))activityInfos.put(activity.name,activity);
        boot("activityIndex",bootStart);
        ClassLoader parent=host.getClassLoader().getParent()!=null?host.getClassLoader().getParent():ClassLoader.getSystemClassLoader().getParent();
        // Declared platform dependencies use the same pinned boot-member registry
        // as the later managed hooks; resolve it before reconstructing their loader.
        RuntimeFixedPreparation.framework();boot("fixedFramework",bootStart);
        // info.nativeLibraryDir is the real, physically-populated execution copy (see
        // RuntimeExecutionCode/ArtifactSet extraction); applicationInfo.nativeLibraryDir is the
        // cosmetic, guest-visible synthetic path the guest sees when it inspects its own
        // ApplicationInfo and never exists on disk. RuntimeDexLoader.createForGuest() takes this
        // exact value as the real linker namespace search path (via sharedLibraries.nativeSearch),
        // so passing the synthetic one here made every guest native library unresolvable.
        sharedLibraries=new RuntimeSharedLibraries(host.getPackageManager(),artifacts,parent,info,info.nativeLibraryDir);
        info.sharedLibraryFiles=sharedLibraries.files.clone();applicationInfo.sharedLibraryFiles=sharedLibraries.files.clone();
        dev.tripoder.selfhook.SharedLibraryFrameworkAccess.project(info,sharedLibraries.metadata);dev.tripoder.selfhook.SharedLibraryFrameworkAccess.project(applicationInfo,sharedLibraries.metadata);
        boot("sharedLibraries",bootStart);
        ClassLoader defining=RuntimeDexLoader.createForGuest(artifacts,info.nativeLibraryDir,sharedLibraries.parent);
        artifactLoader=defining;loader=defining;
        boot("dexLoader",bootStart);
        resources=dev.tripoder.selfhook.ResourceFrameworkAccess.forLoader(host.getPackageManager().getResourcesForApplication(applicationInfo),defining);
        resourceAssets=dev.tripoder.selfhook.ResourceFrameworkAccess.snapshot(resources);
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
        String probeConfiguration=input.getString("codeProbeConfiguration");
        boot("hooks",bootStart);
        if(!adapterReady)throw new IllegalStateException("SELF_HOOK_READY_PUBLICATION_MISSING");
        installWindowSessionProxy();
        if (Boolean.getBoolean("tripoder.debug.mainSampler")) {
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
        }
        applicationStart=new java.util.concurrent.FutureTask<>(()->{
            RuntimeSelfHookStorage.faultStageNative(1); // Application construction/factory.
            recordStart("STARTING");
            Thread startupEvidence=traceStartup?RuntimeStartupDiagnostics.watchStartup(()->application==null?"APPLICATION_CONSTRUCTION_OR_INITIALIZATION":applicationAttached?"ON_CREATE_OR_PUBLICATION":"APPLICATION_ATTACH"):null;
            try{
            RuntimeProcessIdentity.establish(processName);
            android.util.Log.i("TripoderBoot","PROCESS_IDENTITY_READY");
            Thread.currentThread().setContextClassLoader(loader);
            activityManager.facade();
            // Explicit startup tools run after real process identity is established,
            // and still before any guest factory, class initializer or Application.
            if(probeConfiguration!=null){codeProbes=new RuntimeCodeProbes(this,probeConfiguration);codeProbes.start();JSONObject startup=new JSONObject(probeConfiguration).getJSONObject("configuration");if(startup.has("startupHook"))hooks.activateStartup(startup.getJSONObject("startupHook"));}
            List<ProviderInfo> startupProviders=providerIndex.publications(processName,RuntimeComponentState.snapshot(this));
            startupProviders.sort(Comparator.comparingInt((ProviderInfo value)->value.initOrder).reversed());
            RuntimeBridge.prepareApplicationPackage(applicationInfo,loader,resources,processName,startupProviders);
            traceResourceStage("AFTER_LOGICAL_PACKAGE_BEFORE_ANY_GUEST_FACTORY");
            packages.traceEndpoints("BEFORE_GUEST_FACTORY");
            componentFactory=dev.tripoder.selfhook.ApplicationAdapter.createFactory(applicationInfo,loader);
            dev.tripoder.selfhook.ApplicationAdapter.publishFactory(componentFactory);
            ClassLoader selected=componentFactory.instantiateClassLoader(loader,new ApplicationInfo(applicationInfo));if(!ownsLoader(selected))throw new SecurityException("FACTORY_CLASS_LOADER_OUTSIDE_ARTIFACT");dev.tripoder.selfhook.ApplicationAdapter.publishLoader(selected);loader=selected;Thread.currentThread().setContextClassLoader(loader);
            String appName=RuntimeWire.required(input,"application");
            try{Class<?> at=Class.forName("android.app.ActivityThread");
                Object op=at.getMethod("currentOpPackageName").invoke(null);
                Object current=at.getMethod("getApplication").invoke(null);
                android.util.Log.i("TripoderIdentity","instantiate app="+appName+" currentOpPackage="+op
                    +" application="+(current==null?"null":current.getClass().getName()));}catch(Throwable ignored){}
            if(codeProbes!=null)codeProbes.stage("APPLICATION_CONSTRUCTION");
            application=componentFactory.instantiateApplication(loader,appName);
            if(application==null)throw new IllegalStateException("FACTORY_APPLICATION_MISSING");
            logMyAppDiag("STEP1_AFTER_INSTANTIATE", application);
            RuntimeBridge.observeApplicationOwnership("before-attach",host,application);
            traceResourceStage("AFTER_GUEST_CONSTRUCTION_BEFORE_ATTACH");
            Context logical=RuntimeBridge.createApplicationContext(host,application,applicationInfo,loader,componentFactory,resources);
            logMyAppDiag("STEP2_AFTER_CREATE_APP_CTX", application);
            Context guestContext=GuestContext.application(logical,this);
            logMyAppDiag("STEP3_BOUND_PACKAGE", application);
            if (Boolean.getBoolean("tripoder.debug.startupDetails")) try {
                String src = guestContext.getApplicationInfo().sourceDir;
                File f = new File(src);
                android.util.Log.i("TripoderTestFile", "File: " + src + " exists=" + f.exists() + " canRead=" + f.canRead() + " len=" + f.length());
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] b = new byte[4];
                    int n = fis.read(b);
                    android.util.Log.i("TripoderTestFile", "fis.read: n=" + n + " magic=" + (n > 0 ? (char)b[0] + "" + (char)b[1] : "none"));
                }
                FileDescriptor fd = android.system.Os.open(src, android.system.OsConstants.O_RDONLY, 0);
                android.util.Log.i("TripoderTestFile", "Os.open: fd=" + fd);
                android.system.Os.close(fd);
            } catch (Throwable t) {
                android.util.Log.e("TripoderTestFile", "FAILED TO READ sourceDir", t);
            }
            RuntimeSelfHookStorage.faultStageNative(2); // Application.attach.
            updateActivityThreadApplication(application);
            try{RuntimeBridge.attachApplication(application,guestContext);}
            catch(RuntimeException|Error failure){android.util.Log.e("TripoderLoader","attach-failed published="+RuntimeSelfHookApplication.loaderChain(RuntimeSelfHookApplication.publishedLoader()));RuntimeStartupDiagnostics.failure("application-attach",failure);throw failure;}
            finally{RuntimeBridge.observeApplicationOwnership("after-attach",host,application);packages.traceEndpoints("AFTER_APPLICATION_ATTACH");if(codeProbes!=null)codeProbes.stage("APPLICATION_ATTACHED");}
            applicationAttached=true;
            RuntimeSelfHookStorage.faultStageNative(3); // Providers and publication before onCreate.
            updateActivityThreadApplication(application);
            logMyAppDiag("STEP4_AFTER_ATTACH_APP", application);
            android.util.Log.i("TripoderBoot","APPLICATION_ATTACH base="+(application.getBaseContext()==null?"null":application.getBaseContext().getClass().getName()));
            if (Boolean.getBoolean("tripoder.debug.startupDetails")) try {
                Class<?> cls = application.getClass();
                StringBuilder sb = new StringBuilder("DIAG_APP cls=" + cls.getName() + ": ");
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        sb.append(f.getName()).append("=").append(f.get(null)).append("; ");
                    }
                }
                android.util.Log.i("TripoderBoot", sb.toString());
            } catch (Throwable t) {
                android.util.Log.w("TripoderBoot", "DIAG_APP failed", t);
            }
            RuntimeBridge.checkApplicationOwnership(host,application);
            traceResourceStage("AFTER_GUEST_ATTACH");
            // attachBaseContext is where hot-fix/plugin runtimes publish the loader that can see their DEX. Anything
            // published from here on (providers, activities) must resolve through that loader.
            loader=RuntimeSelfHookApplication.adoptLoader(loader);Thread.currentThread().setContextClassLoader(loader);
            application.registerActivityLifecycleCallbacks((RuntimeApplication)host);
            // Android consumes the bind provider list after attach. Shells may
            // remove bootstrap selectors and later request their existing
            // admitted publications through installContentProviders.
            providers.initialize();
            recordStart("ON_CREATE");
            RuntimeSelfHookStorage.faultStageNative(4);
            try{new Instrumentation().callApplicationOnCreate(application);}
            catch(RuntimeException|Error failure){RuntimeStartupDiagnostics.failure("application-onCreate",failure);throw failure;}
            finally{
                RuntimeBridge.observeApplicationOwnership("after-onCreate",host,application);
                packages.traceEndpoints("AFTER_APPLICATION_CREATE");
                if(codeProbes!=null)codeProbes.stage("APPLICATION_CREATED");
            }
            Application installed=RuntimeSelfHookApplication.publishApplication();
            RuntimeSelfHookStorage.faultStageNative(5); // onCreate returned.
            published=installed;
            updateActivityThreadApplication(installed);
            android.util.Log.i("TripoderBoot","APPLICATION_PUBLISHED:"+installed.getClass().getName());
            loader=RuntimeSelfHookApplication.adoptLoader(loader);Thread.currentThread().setContextClassLoader(loader);
            application.unregisterActivityLifecycleCallbacks((RuntimeApplication)host);
            application.registerActivityLifecycleCallbacks((RuntimeApplication)host);
            if (published != null && published != application) {
                published.unregisterActivityLifecycleCallbacks((RuntimeApplication)host);
                published.registerActivityLifecycleCallbacks((RuntimeApplication)host);
            }
            RuntimeBridge.checkApplicationOwnership(host,application);
            recordStart("READY");ready=true;event("application-ready");return null;
            }catch(Exception|Error error){try{recordStart("FAILED");}catch(Exception receipt){error.addSuppressed(receipt);}throw error;}
            finally{if(startupEvidence!=null)startupEvidence.interrupt();}
        });
        constructed.countDown();event("adapter-ready");
    }
    private void traceResourceStage(String stage){
        if(!traceStartup)return;
        try{JSONObject value=new JSONObject().put("stage",stage).put("assets",new JSONObject(resourceAssets.relations(resources))).put("nativeLabelComparisons",new JSONObject(resourceAssets.nativeLabelRelations(resources,applicationInfo.sourceDir,info.sourceDir,host.getApplicationInfo().sourceDir)));android.util.Log.i("TripoderResourceStage",value.toString());}
        catch(Exception unavailable){android.util.Log.w("TripoderResourceStage",stage+":UNAVAILABLE:"+unavailable.getClass().getName());}
    }
    private static void logMyAppDiag(String tag, Application app) {
        if (!Boolean.getBoolean("tripoder.debug.startupDetails") || app == null) return;
        try {
            Class<?> cls = app.getClass();
            StringBuilder sb = new StringBuilder("DIAG_APP [" + tag + "] cls=" + cls.getName() + " base=" + (app.getBaseContext() == null ? "null" : app.getBaseContext().getClass().getName()) + ": ");
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    sb.append(f.getName()).append("=").append(f.get(null)).append("; ");
                }
            }
            android.util.Log.i("TripoderBoot", sb.toString());
        } catch (Throwable t) {
            android.util.Log.w("TripoderBoot", "DIAG_APP [" + tag + "] failed", t);
        }
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
    /** The verified defining loader: the only ClassLoader that holds this installation's DEX. */
    ClassLoader artifactLoader(){return artifactLoader;}
    boolean ownsDataDirLoader(ClassLoader candidate) {
        if (candidate == null || info == null) return false;
        for (ClassLoader cursor = candidate; cursor != null; cursor = cursor.getParent()) {
            if (cursor instanceof dalvik.system.BaseDexClassLoader) {
                String desc = cursor.toString();
                if (info.dataDir != null && desc.contains(info.dataDir)) return true;
                if (info.deviceProtectedDataDir != null && desc.contains(info.deviceProtectedDataDir)) return true;
                if (info.sourceDir != null && desc.contains(info.sourceDir)) return true;
            }
        }
        return false;
    }
    boolean ownsLoader(ClassLoader candidate){return RuntimeSelfHookLoaders.owns(artifactLoader,candidate,application==null?null:application.getClass())||ownsDataDirLoader(candidate);}
    /**
     * The physical form of a launch record.
     *
     * <p>Android does not promise that the intent a client receives still names the component the caller started: a
     * platform may resolve a record's visible component back from the ActivityInfo it was given, so the same record
     * can arrive as the guest component while still carrying its lease. The lease is the identity; its slot is the
     * physical component this process owns. Returns null when the record carries no lease of this generation.
     */
    private final Map<String,String> thinTicketStubs=new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<Activity> thinControllerRoots=Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    public Intent leasedRecord(Intent intent)throws Exception{
        if(intent==null)return null;String ticket=intent.getStringExtra("tripoder.ticket");if(ticket==null)return null;
        {
            String cached=thinTicketStubs.get(ticket);
            if(cached!=null&&RuntimeActivitySlots.owns(cached))return new Intent(intent).setComponent(new ComponentName(host.getPackageName(),cached));
        }
        Bundle query=RuntimeActivityConstruction.request(generation,intent,guestLoader());Bundle row=RuntimeWire.call(broker,RuntimeWire.ACTIVITY_RECORD,query);
        String stub=RuntimeWire.required(row,"stub");if(!RuntimeActivitySlots.owns(stub))throw new SecurityException("ACTIVITY_LEASE_STUB_INVALID");
        thinTicketStubs.put(ticket,stub);
        return new Intent(intent).setComponent(new ComponentName(host.getPackageName(),stub));
    }
    /**
     * The loader authoritative for resolving guest classes at this moment.
     *
     * <p>Android gives the application authority over its own class loader: {@code LoadedApk.mClassLoader} is what the
     * platform passes to every component factory, and hot-fix, plugin and App Bundle runtimes replace it to install DEX
     * that only their loader can see. A component constructed through the loader captured at boot therefore reports
     * classes the application legitimately published as missing. This follows the published loader while its parent
     * chain still ends at this installation's verified loader; anything else would be a foreign loader and keeps the
     * verified loader.
     */
    public ClassLoader guestLoader(){
        ClassLoader published=RuntimeSelfHookApplication.publishedLoader();
        return published!=null&&published!=loader&&ownsLoader(published)?published:loader;
    }
    /** Guest code, or the exact platform WebView provider loader captured as an explicit dependency. */
    boolean ownsReceiverLoader(ClassLoader candidate){return ownsLoader(candidate)||sharedLibraries.owns(candidate)||webView.ownsProviderLoader(candidate);}
    boolean ownsReceiverType(Class<?> type){return type!=null&&(ownsReceiverLoader(type.getClassLoader())||dev.tripoder.selfhook.SystemReceiverAccess.ownsType(type));}
    boolean ownsActivity(Activity activity){return activityInstances.containsKey(activity);}
    ComponentName physicalActivityComponent(Activity activity){
        ActivityInstance identity=activityInstances.get(activity);
        if(identity==null)throw new SecurityException("ACTIVITY_INSTANCE_MISSING");
        return identity.physicalComponent();
    }
    /** Failure diagnostics only: the guest Activities this engine constructed. */
    java.util.List<Activity> diagnosticActivities(){return new java.util.ArrayList<>(activityInstances.keySet());}
    Bundle auth(){Bundle b=new Bundle();b.putString("generation",generation);return b;}
    public synchronized ContentResolver settingsResolver(){if(settingsResolver==null){settingsProvider=new SettingsReadProvider(host.getContentResolver());android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="settings";info.exported=false;settingsProvider.attachInfo(host,info);ContentProvider router=providers.router();ProviderInfo routeInfo=new ProviderInfo();routeInfo.authority="tripoder.resolver";routeInfo.exported=false;router.attachInfo(host,routeInfo);settingsResolver=ContentResolver.wrap(router);}return settingsResolver;}
    public ContentProvider settingsProvider(){settingsResolver();return settingsProvider;}
    public boolean ownsResolver(ContentResolver resolver){return resolver==settingsResolver;}
    public void event(String stage){try{Bundle b=auth();b.putString("stage",stage);RuntimeWire.call(broker,RuntimeWire.EVENT,b);}catch(Exception e){throw new IllegalStateException("BROKER_EVENT_FAILED",e);}}
    RuntimeActivityCreation.Permit claimCreation(Intent intent)throws Exception {
        // Bundle values unparcel lazily and keep the loader seen at their first access; own it before any extras read.
        intent.setExtrasClassLoader(guestLoader());
        String ticket=RuntimeWire.required(intent.getExtras(),"tripoder.ticket");
        String activityName=intent.getStringExtra("tripoder.component");
        if(generation.equals(intent.getStringExtra("tripoder.generation"))&&intent.getBooleanExtra("tripoder.thinRoute",false)){
            if(!activityInfos.containsKey(activityName))throw new SecurityException("ACTIVITY_SESSION_MISMATCH");
            Intent visible=RuntimeActivityIntent.visible(intent,packageName,guestLoader());
            thinTicketStubs.put(ticket,intent.getComponent().getClassName());
            Bundle caller=new Bundle();
            caller.putString("callerKind",intent.getStringExtra("tripoder.callerKind")!=null?intent.getStringExtra("tripoder.callerKind"):"CONTROLLER");
            caller.putString("callerPackage",intent.getStringExtra("tripoder.callerPackage")!=null?intent.getStringExtra("tripoder.callerPackage"):packageName);
            if(intent.getStringExtra("tripoder.callerComponent")!=null)caller.putString("callerComponent",intent.getStringExtra("tripoder.callerComponent"));
            if(intent.getStringExtra("tripoder.callerPhysical")!=null)caller.putString("callerPhysical",intent.getStringExtra("tripoder.callerPhysical"));
            caller.putBoolean("expectsResult",intent.getBooleanExtra("tripoder.expectsResult",false));
            ActivityInfo effective=effectiveActivity(activityName);
            return new RuntimeActivityCreation.Permit(this,intent,visible,ticket,ticket,effective.targetActivity==null?activityName:effective.targetActivity,caller);
        }
        if(!activityInfos.containsKey(activityName))throw new SecurityException("ACTIVITY_SESSION_MISMATCH");
        Intent visible=RuntimeActivityIntent.visible(intent,packageName,guestLoader());
        String instance=ticket;Bundle claim=auth();claim.putString("ticket",ticket);claim.putString("component",activityName);claim.putString("instance",instance);claim.putString("stub",intent.getComponent().getClassName());claim.putString("delivery",RuntimeWire.required(intent.getExtras(),"tripoder.delivery"));Bundle claimed=RuntimeWire.call(broker,RuntimeWire.ACTIVITY_CLAIM,claim);
        if(claimed.containsKey("rejectedActivity"))return new RuntimeActivityCreation.Permit(claimed.getString("rejectedActivity"));
        thinTicketStubs.put(ticket,intent.getComponent().getClassName());
        ActivityInfo effective=effectiveActivity(activityName);
        return new RuntimeActivityCreation.Permit(this,intent,visible,ticket,instance,effective.targetActivity==null?activityName:effective.targetActivity,claimed);
    }
    private ActivityInfo effectiveActivity(String name){
        ActivityInfo raw=activityInfos.get(name);if(raw==null)throw new IllegalArgumentException("COMPONENT_NOT_DECLARED:activity:"+name);
        ActivityInfo selected=RuntimeComponentState.activity(this,raw);return RuntimeActivityIndex.require(java.util.Map.of(name,selected),name);
    }
    void registerCreation(RuntimeActivityCreation.Permit permit,Activity activity){
        Intent intent=permit.physical,visible=permit.visible;String activityName=RuntimeWire.required(intent.getExtras(),"tripoder.component");
        String callback=intent.getStringExtra("tripoder.callback"),op=intent.getStringExtra("tripoder.operation");
        if(callback!=null)callbacks.put(activity,new String[]{callback,op});
        if(!intent.getBooleanExtra("tripoder.thinRoute",false))thinControllerRoots.add(activity);
        activityInstances.put(activity,new ActivityInstance(permit.ticket,permit.instance,permit.physical.getComponent()));
        activityCallers.initial(activity,permit.caller);
        ActivityInfo admittedInfo=new ActivityInfo(activityInfos.get(activityName));admittedInfo.enabled=true; // The creation permit admitted this exact enabled component.
        activityMetadata.put(activity,admittedInfo);visibleIntents.put(activity,visible);
    }
    public void activityAttached(Activity activity){taskPresentation.attached(activity);if(!thinControllerRoots.contains(activity))return;try{Bundle b=activityAuth(activity);b.putInt("taskId",activity.getTaskId());RuntimeWire.call(broker,RuntimeWire.ACTIVITY_ATTACHED,b);}catch(Exception error){throw new IllegalStateException("ACTIVITY_ATTACH_ACK_FAILED",error);}}
    private final java.util.Set<Activity> createdActivities=java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    public void activityCreated(Activity activity){if(!createdActivities.add(activity))return;applyGuestWindowInsetsIfNeeded(activity);return;}
    private void applyGuestWindowInsetsIfNeeded(Activity activity){
        if(info==null||info.targetSdkVersion<=0||info.targetSdkVersion>=35)return;
        android.view.Window window=activity.getWindow();if(window==null)return;
        // Legacy apps can already draw behind the system bars and handle their
        // own insets without hiding either bar. Do not install a second listener
        // on their content root (or replace one supplied by the application).
        if(guestOwnsWindowInsets(window))return;
        android.view.View content=window.findViewById(android.R.id.content);if(content==null)return;
        int left=content.getPaddingLeft(),top=content.getPaddingTop(),right=content.getPaddingRight(),bottom=content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((view,windowInsets)->{
            android.view.Window win=activity.getWindow();
            // A window can switch to immersive layout after onCreate. Undo only
            // our compatibility padding, retaining the application's own padding.
            if(win!=null&&guestOwnsWindowInsets(win))view.setPadding(left,top,right,bottom);
            else{
                android.graphics.Insets bars=windowInsets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                view.setPadding(left+bars.left,top+bars.top,right+bars.right,bottom+bars.bottom);
            }
            return windowInsets;
        });
        content.requestApplyInsets();
    }
    private static boolean guestOwnsWindowInsets(android.view.Window window){
        android.view.WindowManager.LayoutParams attrs=window.getAttributes();
        int visibility=attrs.systemUiVisibility|window.getDecorView().getSystemUiVisibility();
        return (attrs.flags&android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)!=0
            ||(visibility&(android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION))!=0;
    }
    public void activityResumed(Activity activity){return;}
    public void activityDestroying(Activity activity){destructionTasks.put(activity,activity.getTaskId());}
    public void activityDestroyed(Activity activity){try{Integer task=destructionTasks.remove(activity);if(task==null)throw new IllegalStateException("ACTIVITY_PRE_DESTROY_MISSING");Bundle b=activityAuth(activity);boolean reportDestroy=thinControllerRoots.remove(activity)||callbacks.containsKey(activity);if(reportDestroy){b.putBoolean("finishing",activity.isFinishing());b.putInt("taskId",task);RuntimeWire.call(broker,RuntimeWire.ACTIVITY_DESTROYED,b);}if(activity.isFinishing())pendingResults.remove(b.getString("ticket"));callbacks.remove(activity);visibleIntents.remove(activity);activityInstances.remove(activity);activityMetadata.remove(activity);activityCallers.remove(activity);returned.remove(activity);createdActivities.remove(activity);}catch(Exception error){throw new IllegalStateException("ACTIVITY_DESTROY_ACK_FAILED",error);}}
    private Bundle activityAuth(Activity activity){ActivityInstance identity=activityInstances.get(activity);if(identity==null)throw new SecurityException("ACTIVITY_INSTANCE_MISSING");Bundle b=auth();b.putString("ticket",identity.ticket());b.putString("instance",identity.instance());b.putInt("taskId",activity.getTaskId());return b;}
    public ActivityInfo activityInfo(Activity activity){ActivityInfo declared=activityMetadata.get(activity);if(declared==null)throw new SecurityException("ACTIVITY_NOT_DECLARED_IN_PROCESS");return new ActivityInfo(declared);}
    public int theme(Activity activity){return activityInfo(activity).getThemeResource();}
    public Intent visibleIntent(Activity activity){Intent intent=visibleIntents.get(activity);if(intent==null)throw new SecurityException("ACTIVITY_NOT_REGISTERED");return intent;}
    public Intent newIntent(Activity activity,Intent physical,ComponentCaller caller)throws Exception{
        if(!ownsActivity(activity)||physical==null||physical.getComponent()==null||!host.getPackageName().equals(physical.getComponent().getPackageName()))throw new SecurityException("ACTIVITY_NEW_INTENT_TARGET_MISMATCH");
        physical.setExtrasClassLoader(guestLoader());
        {
            Intent visible=RuntimeActivityIntent.visible(physical,packageName,guestLoader());
            if(!ActivityComponentLedger.canonical(effectiveActivity(visible.getComponent().getClassName())).equals(ActivityComponentLedger.canonical(activityInfo(activity))))throw new SecurityException("ACTIVITY_NEW_INTENT_COMPONENT_MISMATCH");
            String callerPkg=physical.getStringExtra("tripoder.callerPackage");
            activityCallers.enter(activity,callerPkg!=null&&!callerPkg.isEmpty()?callerPkg:packageName,caller);
            return visible;
        }

    }
    public void newIntentComplete(Activity activity,String delivery,boolean success)throws Exception{{activityCallers.leave(activity);return;}}
    public Intent route(Activity caller,Intent intent,int requestCode)throws Exception {
        if(!visibleIntents.containsKey(caller))throw new SecurityException("ACTIVITY_NOT_REGISTERED");
        Map<Integer,String> pending=pendingResults.computeIfAbsent(activityAuth(caller).getString("ticket"),k->new HashMap<>());
        if(requestCode>=0&&pending.containsKey(requestCode))throw new IllegalStateException("REQUEST_CODE_ALREADY_IN_FLIGHT");
        String digest;
        {intent=new Intent(intent);digest="thin";}
        Bundle request=activityAuth(caller);request.putString("package",intent.getComponent().getPackageName());request.putString("component",intent.getComponent().getClassName());request.putString("operation",UUID.randomUUID().toString());request.putString("requestDigest",digest);request.putBoolean("wantsResult",requestCode>=0);request.putInt("flags",intent.getFlags());
        Bundle response;
        try{response=RuntimeWire.call(broker,RuntimeWire.ROUTE,request);}catch(IllegalStateException e){if(String.valueOf(e.getMessage()).contains("PACKAGE_NOT_IN_SPACE"))throw new ActivityNotFoundException("PACKAGE_NOT_IN_SPACE:"+intent.getComponent().getPackageName());throw e;}
        if(requestCode>=0)pending.put(requestCode,RuntimeWire.required(response,"operation"));
        String stub=RuntimeWire.required(response,"stub"),ticket=RuntimeWire.required(response,"ticket");
        android.util.Log.i("TripoderActivities", "ROUTING activity: caller=" + caller.getClass().getName() + " target=" + intent.getComponent().getClassName() + " stub=" + stub + " flags=0x" + Integer.toHexString(intent.getFlags()));
        thinTicketStubs.put(ticket,stub);
        Intent routed=new Intent(intent).setComponent(new ComponentName(host,stub));
        Bundle origExtras=intent.getExtras();routed.putExtra("tripoder.extras",origExtras!=null&&!origExtras.isEmpty());
        routed.putExtra("tripoder.flags",intent.getFlags()).putExtra("tripoder.component",intent.getComponent().getClassName()).putExtra("tripoder.generation",RuntimeWire.required(response,"generation")).putExtra("tripoder.operation",RuntimeWire.required(response,"operation")).putExtra("tripoder.ticket",ticket).putExtra("tripoder.delivery",RuntimeWire.required(response,"delivery"));
        routed.putExtra("tripoder.thinRoute",true);
        if(response.containsKey("callerKind"))routed.putExtra("tripoder.callerKind",response.getString("callerKind")).putExtra("tripoder.callerPackage",response.getString("callerPackage")).putExtra("tripoder.expectsResult",response.getBoolean("expectsResult"));
        if(response.getString("callerComponent")!=null)routed.putExtra("tripoder.callerComponent",response.getString("callerComponent"));
        if(response.getString("callerPhysical")!=null)routed.putExtra("tripoder.callerPhysical",response.getString("callerPhysical"));
        if(requestCode>=0)routed.putExtra("tripoder.callback",RuntimeWire.required(response,"callback"));routed.setExtrasClassLoader(guestLoader());return routed;
    }
    /** A start requested through a non-Activity Context: a new task begun by the process itself, never a result request. */
    public Intent routeFromContext(Intent intent)throws Exception{
        if(intent.getComponent()==null)throw new IllegalArgumentException("ACTIVITY_COMPONENT_REQUIRED");
        String digest;
        {intent=new Intent(intent);digest="thin";}
        if((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)==0)throw new android.util.AndroidRuntimeException("Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag. Is this really what you want?");
        Bundle request=auth();request.putBoolean("fromContext",true);request.putString("package",intent.getComponent().getPackageName());request.putString("component",intent.getComponent().getClassName());request.putString("operation",UUID.randomUUID().toString());request.putString("requestDigest",digest);request.putBoolean("wantsResult",false);request.putInt("flags",intent.getFlags());
        Bundle response;
        try{response=RuntimeWire.call(broker,RuntimeWire.ROUTE,request);}catch(IllegalStateException e){if(String.valueOf(e.getMessage()).contains("PACKAGE_NOT_IN_SPACE"))throw new ActivityNotFoundException("PACKAGE_NOT_IN_SPACE:"+intent.getComponent().getPackageName());throw e;}
        String stub=RuntimeWire.required(response,"stub"),ticket=RuntimeWire.required(response,"ticket");
        android.util.Log.i("TripoderActivities", "ROUTING context activity: target=" + intent.getComponent().getClassName() + " stub=" + stub + " flags=0x" + Integer.toHexString(intent.getFlags()));
        thinTicketStubs.put(ticket,stub);
        Intent routed=new Intent(intent).setComponent(new ComponentName(host,stub));
        Bundle origExtras=intent.getExtras();routed.putExtra("tripoder.extras",origExtras!=null&&!origExtras.isEmpty());
        routed.putExtra("tripoder.flags",intent.getFlags()).putExtra("tripoder.component",intent.getComponent().getClassName()).putExtra("tripoder.generation",RuntimeWire.required(response,"generation")).putExtra("tripoder.operation",RuntimeWire.required(response,"operation")).putExtra("tripoder.ticket",ticket).putExtra("tripoder.delivery",RuntimeWire.required(response,"delivery"));
        routed.putExtra("tripoder.thinRoute",true);
        if(response.containsKey("callerKind"))routed.putExtra("tripoder.callerKind",response.getString("callerKind")).putExtra("tripoder.callerPackage",response.getString("callerPackage")).putExtra("tripoder.expectsResult",response.getBoolean("expectsResult"));
        routed.setExtrasClassLoader(guestLoader());
        return routed;
    }
    /** The host's own components are never a guest start target: the guest shares the host UID, so this is a policy boundary, not a kernel one. */
    public boolean targetsHost(Intent intent){
        if(intent==null)return false;ComponentName component=intent.getComponent();String pkg=component!=null?component.getPackageName():intent.getPackage();
        return pkg!=null&&host.getPackageName().equals(pkg);
    }
    public void activityStartResult(Object value,int result)throws Exception{
        return;

    }
    public void returning(Activity activity,int resultCode,Intent result)throws Exception {
        if(returned.contains(activity))return;
        RuntimeActivityResult.require(result,activityCallers.samePackageResult(activity,packageName));
        String[] callback=callbacks.get(activity);if(callback==null){returned.add(activity);return;} // The controller receives the same checked result shape.
        try{
            Bundle request=auth();request.putString("callback",callback[0]);request.putString("operation",callback[1]);RuntimeWire.call(broker,RuntimeWire.RESULT,request);
        }finally{
            returned.add(activity);callbacks.remove(activity);
        }
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


    static void updateActivityThreadApplication(Application app) {
        if (app == null) return;
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cat = atCls.getDeclaredMethod("currentActivityThread");
            cat.setAccessible(true);
            Object at = cat.invoke(null);
            if (at != null) {
                try {
                    java.lang.reflect.Field initAppField = atCls.getDeclaredField("mInitialApplication");
                    initAppField.setAccessible(true);
                    // Shell Applications (NetEase NIS, Jiagu, Bangcle...) publish their real delegate here from
                    // attachBaseContext and later read it back to forward onCreate. Overwriting a guest-published
                    // delegate with the shell itself makes the shell forward onCreate to itself forever. Only the
                    // physical host value is ours to replace.
                    Object published = initAppField.get(at);
                    RuntimeEngine engine = RuntimeEngine.current;
                    boolean ours = published == null || (engine != null && published == engine.host);
                    if (ours) initAppField.set(at, app);
                    else android.util.Log.i("TripoderHardened", "Kept guest-published initial application " + published.getClass().getName());
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Field allAppsField = atCls.getDeclaredField("mAllApplications");
                    allAppsField.setAccessible(true);
                    Object allApps = allAppsField.get(at);
                    if (allApps instanceof java.util.List list) {
                        synchronized (list) {
                            if (!list.contains(app)) list.add(0, app);
                        }
                    }
                } catch (Throwable ignored) {}

                try {
                    Object pkgInfo = dev.tripoder.selfhook.ApplicationAdapter.packageInfo();
                    if (pkgInfo != null) {
                        java.lang.reflect.Field mBoundAppField = atCls.getDeclaredField("mBoundApplication");
                        mBoundAppField.setAccessible(true);
                        Object boundApp = mBoundAppField.get(at);
                        if (boundApp != null) {
                            java.lang.reflect.Field infoField = boundApp.getClass().getDeclaredField("info");
                            infoField.setAccessible(true);
                            infoField.set(boundApp, pkgInfo);
                        }
                    }
                } catch (Throwable ignored) {}

                try {
                    RuntimeEngine engine = RuntimeEngine.current;
                    if (engine != null) {
                        String hostPkg = engine.host.getPackageName();
                        Context base = app.getBaseContext();
                        while (base instanceof android.content.ContextWrapper) {
                            base = ((android.content.ContextWrapper) base).getBaseContext();
                        }
                        if (base != null) {
                            try {
                                java.lang.reflect.Field opField = base.getClass().getDeclaredField("mOpPackageName");
                                opField.setAccessible(true);
                                opField.set(base, hostPkg);
                                android.util.Log.i("TripoderHardened", "Enforced host opPackageName on base ContextImpl of " + app.getClass().getName());
                            } catch (NoSuchFieldException ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}

                android.util.Log.i("TripoderHardened", "Updated ActivityThread application to " + app.getClass().getName());
            }
        } catch (Throwable t) {
            android.util.Log.w("TripoderHardened", "Failed to update ActivityThread application", t);
        }
    }

    /** The bind record owns the package before newApplication starts; mApplication remains unpublished until attach returns. */


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
    /**
     * App widget queries are component-scoped: the platform answers them by checking the provider's package against
     * the caller's UID, and a guest package was never installed for this UID, so forwarding the guest's query can only
     * produce a SecurityException. A virtual device does not publish guest widget providers, so the boundary answers
     * exactly what an empty device answers and forwards only host-scoped calls.
     */
    private void installAppWidgetBoundary() {
        try {
            // The contract is whatever interface AppWidgetManager stores its binder as. Android has moved it between
            // packages (android.appwidget.IAppWidgetService -> com.android.internal.appwidget.IAppWidgetService), and
            // the boundary must implement the type the platform actually calls, not a remembered name.
            Class<?> manager=Class.forName("android.appwidget.AppWidgetManager");
            java.lang.reflect.Field service=manager.getDeclaredField("sService");service.setAccessible(true);
            Class<?> contract=service.getType();
            Object real=service.get(null);
            Object proxy=java.lang.reflect.Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(p,method,args)->{
                String name=method.getName();
                if("asBinder".equals(name))return real==null?null:((android.os.IInterface)real).asBinder();
                if("queryLocalInterface".equals(name))return contract.getName().equals(args==null?null:args[0])?p:null;
                if("hashCode".equals(name))return System.identityHashCode(p);
                if("equals".equals(name))return p==(args==null?null:args[0]);
                if(!hostScoped(args))return absent(method.getReturnType());
                if(real==null)return absent(method.getReturnType());
                try{return method.invoke(real,args);}catch(java.lang.reflect.InvocationTargetException ite){throw ite.getCause();}
            });
            service.set(null,proxy);
            android.util.Log.i("TripoderHardened","Installed IAppWidgetService boundary");
        } catch (Throwable t) {
            android.util.Log.e("TripoderHardened","Failed to install IAppWidgetService boundary",t);
        }
    }
    private boolean hostScoped(Object[] args){
        if(args==null)return true;
        for(Object arg:args){
            if(arg instanceof android.content.ComponentName name&&!host.getPackageName().equals(name.getPackageName()))return false;
            if(arg instanceof String name&&packageName.equals(name))return false;
        }
        return true;
    }
    private static Object absent(Class<?> type){
        if(type==int[].class)return new int[0];
        if(type==long[].class)return new long[0];
        if(type==boolean.class)return false;
        if(type==int.class)return 0;
        if(type==long.class)return 0L;
        if(type==void.class)return null;
        if(type==java.util.List.class)return java.util.Collections.emptyList();
        if(type.isArray())return java.lang.reflect.Array.newInstance(type.getComponentType(),0);
        return null;
    }
}

