package dev.tripoder.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.webkit.WebView;
import java.util.Arrays;
import java.util.Objects;

/** The platform-selected WebView provider is an explicit code/resource dependency.
 * It never makes arbitrary installed packages part of the guest package world. */
final class RuntimeWebView {
    private static final long QUERY_FLAGS=PackageManager.GET_META_DATA|PackageManager.GET_SIGNATURES
        |PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_SHARED_LIBRARY_FILES
        |PackageManager.MATCH_UNINSTALLED_PACKAGES|PackageManager.MATCH_DISABLED_COMPONENTS
        |PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
    private final RuntimeEngine engine;
    private PackageInfo provider;
    private Resources resources;
    private dev.tripoder.selfhook.ResourceFrameworkAccess.AssetSnapshot diagnosticAssets;
    private dev.tripoder.selfhook.ResourceFrameworkAccess.AssetSnapshot diagnosticSharedAssets;
    private dev.tripoder.selfhook.ResourceFrameworkAccess.AssetSnapshot diagnosticExtendedAssets;
    private String diagnosticAssetStatus="NOT_CAPTURED";
    private String diagnosticSharedAssetStatus="NOT_CAPTURED";
    private String diagnosticExtendedAssetStatus="NOT_CAPTURED";
    private dev.tripoder.selfhook.ResourceDependencyAccess.RegistrationPlan resourcePlan;
    private enum ResourceState { PREPARED,REGISTERING,REGISTERED,FAILED }
    private volatile ResourceState resourceState=ResourceState.PREPARED;
    private final java.util.concurrent.CompletableFuture<Void> resourceRegistration=new java.util.concurrent.CompletableFuture<>();
    private volatile Thread resourceRegistrar;
    private Object updater;
    private final java.util.Set<android.content.ComponentName> components=new java.util.HashSet<>();
    private boolean captured;
    final RuntimeWebViewServices services;
    RuntimeWebView(RuntimeEngine engine){this.engine=engine;services=new RuntimeWebViewServices(engine,this);}
    void capture()throws Exception{
        if(captured)throw new IllegalStateException("WEBVIEW_DEPENDENCY_ALREADY_CAPTURED");captured=true;
        PackageInfo selected=WebView.getCurrentWebViewPackage();
        if(selected==null)return; // No provider is a real platform absence, not a replacement engine.
        provider=engine.host.getPackageManager().getPackageInfo(selected.packageName,PackageManager.PackageInfoFlags.of(QUERY_FLAGS|PackageManager.GET_ACTIVITIES|PackageManager.GET_RECEIVERS|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS));
        if(provider.applicationInfo==null||provider.signatures==null||provider.signatures.length==0
                ||!selected.packageName.equals(provider.packageName)||selected.getLongVersionCode()!=provider.getLongVersionCode())
            throw new SecurityException("WEBVIEW_PROVIDER_CAPTURE_CHANGED");
        resources=engine.host.getPackageManager().getResourcesForApplication(provider.applicationInfo);
        resourcePlan=dev.tripoder.selfhook.ResourceDependencyAccess.plan(engine.resources,provider.applicationInfo,engine.applicationInfo);
        if(engine.traceStartup)try{diagnosticAssets=dev.tripoder.selfhook.ResourceFrameworkAccess.snapshot(resources);diagnosticAssetStatus="CAPTURED_BEFORE_GUEST";}
        catch(Exception unavailable){diagnosticAssetStatus="UNAVAILABLE:"+unavailable.getClass().getName();}
        if(engine.traceStartup)try{diagnosticSharedAssets=dev.tripoder.selfhook.ResourceDependencyAccess.sharedLibrarySnapshot(provider.applicationInfo,engine.applicationInfo);diagnosticSharedAssetStatus="CANONICAL_SHARED_CAPTURED_BEFORE_GUEST";}
        catch(Exception unavailable){diagnosticSharedAssetStatus="UNAVAILABLE:"+unavailable.getClass().getName();}
        if(engine.traceStartup){diagnosticExtendedAssets=resourcePlan.layout();diagnosticExtendedAssetStatus="CANONICAL_EXTENDED_CAPTURED_BEFORE_GUEST";}
        for(android.content.pm.ComponentInfo[] group:new android.content.pm.ComponentInfo[][]{provider.activities,provider.receivers,provider.services,provider.providers})
            if(group!=null)for(var component:group)components.add(new android.content.ComponentName(component.packageName,component.name));
        updater=Objects.requireNonNull(engine.host.getSystemService("webviewupdate"),"WEBVIEW_UPDATE_SERVICE_UNAVAILABLE");
        // Stable for this installation/data incarnation and logical process,
        // different for another installation or process; cold starts reuse it.
        String key=engine.storage.space+"\n"+engine.packageName+"\n"+engine.storage.dataId+"\n"+engine.processName;
        String suffix=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        WebView.setDataDirectorySuffix("tripoder-"+suffix);
    }
    boolean owns(String name){return provider!=null&&provider.packageName.equals(name);}
    /** Standard Android overlay/RRO partitions: root-owned, SELinux-protected, never
     * a package's own bytes. Their late binding is ordinary platform churn, not a
     * guest or dependency growing its own resource set. */
    private static final String[] SYSTEM_OVERLAY_PREFIXES={
        "/product/overlay/","/odm/overlay/","/vendor/overlay/","/system_ext/overlay/","/system/overlay/","/data/resource-cache/"};
    void requireGuestResources(Resources view){
        ResourceState state=resourceState;
        boolean registered=state==ResourceState.REGISTERING||state==ResourceState.REGISTERED;
        // getAssetPath() reports the guest-visible (virtualized) path, e.g.
        // /data/user/0/<pkg>/..., not this process's real on-disk storage. A
        // dynamic module/plugin loader calling AssetManager.addAssetPath on its
        // own extracted or installed bytes stays under its own dataDir/sourceDir;
        // the explicitly captured+signature-verified WebView provider (see
        // capture()) can likewise keep adding its own resources after its one
        // frozen layout was computed. Neither vector can be a foreign package.
        java.util.List<String> owned=new java.util.ArrayList<>();
        if(engine.applicationInfo.dataDir!=null)owned.add(engine.applicationInfo.dataDir);
        if(engine.applicationInfo.sourceDir!=null)owned.add(new java.io.File(engine.applicationInfo.sourceDir).getParent());
        if(provider!=null&&provider.applicationInfo!=null){
            if(provider.applicationInfo.dataDir!=null)owned.add(provider.applicationInfo.dataDir);
            if(provider.applicationInfo.sourceDir!=null)owned.add(new java.io.File(provider.applicationInfo.sourceDir).getParent());
        }
        owned.addAll(Arrays.asList(SYSTEM_OVERLAY_PREFIXES));
        engine.resourceAssets.requireOwnedLayout(view,registered?resourcePlan.layout():null,owned.toArray(new String[0]));
    }
    Object registerResources(Object receiver,String name,ApplicationInfo supplied,RuntimeManagedInstaller.Original original)throws Throwable{
        boolean owner;
        synchronized(this){
            if(!owns(name)||resourcePlan==null)throw new SecurityException("RESOURCE_DEPENDENCY_OWNER_REQUIRED");
            resourcePlan.requireEntry(receiver);
            if(engine.application==null)throw new IllegalStateException("RESOURCE_DEPENDENCY_APPLICATION_REQUIRED");
            if(resourceState==ResourceState.FAILED)throw new IllegalStateException("RESOURCE_DEPENDENCY_REGISTRATION_FAILED");
            if(resourceState==ResourceState.REGISTERING&&resourceRegistrar==Thread.currentThread())throw new IllegalStateException("RESOURCE_DEPENDENCY_REGISTRATION_CYCLE");
            packageInfo(name,QUERY_FLAGS,RuntimePackageBridge.userId());identity(supplied);resourcePlan.requireInputs(supplied,engine.applicationInfo);
            requireGuestResources(engine.resources);owner=resourceState==ResourceState.PREPARED;
            if(owner){resourceRegistrar=Thread.currentThread();resourceState=ResourceState.REGISTERING;}
        }
        if(!owner){
            // Same dependency, same real registration: concurrent/repeated
            // requests share its completion rather than a second owner/effect.
            try{resourceRegistration.get();}catch(java.util.concurrent.ExecutionException failure){throw failure.getCause();}
            packageInfo(name,QUERY_FLAGS,RuntimePackageBridge.userId());resourcePlan.requireInputs(supplied,engine.applicationInfo);resourcePlan.requireRecorded();requireGuestResources(engine.resources);return null;
        }
        try{
            // The platform consumes a trusted copy. Mutable caller Bundles and
            // arrays cannot alter the already compiled resource dependency.
            Object result=original.call(new Object[]{name,resourcePlan.canonicalInput()});
            packageInfo(name,QUERY_FLAGS,RuntimePackageBridge.userId());resourcePlan.requireInputs(supplied,engine.applicationInfo);
            resourcePlan.requireRecorded();requireGuestResources(engine.resources);
            resourceState=ResourceState.REGISTERED;
            resourceRegistration.complete(null);
            if(engine.traceStartup)android.util.Log.i("TripoderResourceDependency","SELECTED_PROVIDER_REGISTERED_WITH_PRECOMPILED_LAYOUT");
            return result;
        }catch(Throwable failure){resourceState=ResourceState.FAILED;resourceRegistration.completeExceptionally(failure);throw failure;}
        finally{resourceRegistrar=null;}
    }
    java.util.Map<String,Object> diagnosticAssetRelations(Resources view){
        java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("status",diagnosticAssetStatus);
        if(diagnosticAssets!=null)result.put("relations",diagnosticAssets.relations(view));
        result.put("canonicalSharedStatus",diagnosticSharedAssetStatus);if(diagnosticSharedAssets!=null)result.put("canonicalSharedRelations",diagnosticSharedAssets.relations(view));
        result.put("canonicalExtendedStatus",diagnosticExtendedAssetStatus);if(diagnosticExtendedAssets!=null)result.put("canonicalExtendedRelations",diagnosticExtendedAssets.relations(view));return result;
    }
    /** The provider loads its code in a loader whose complete dex path is exactly the captured provider APK set. */
    boolean ownsProviderLoader(ClassLoader candidate){
        if(provider==null||!(candidate instanceof dalvik.system.PathClassLoader))return false;
        java.util.Set<String> expected=new java.util.HashSet<>();expected.add(provider.applicationInfo.sourceDir);
        if(provider.applicationInfo.splitSourceDirs!=null)expected.addAll(Arrays.asList(provider.applicationInfo.splitSourceDirs));
        java.util.Set<String> actual=new java.util.HashSet<>();String text=candidate.toString();String marker="zip file \"";
        for(int at=text.indexOf(marker);at>=0;at=text.indexOf(marker,at+1)){int start=at+marker.length();int end=text.indexOf('"',start);if(end<0)return false;actual.add(text.substring(start,end));}
        return !actual.isEmpty()&&actual.equals(expected);
    }
    int componentEnabled(android.content.ComponentName component,int user){
        if(component==null||!components.contains(component))throw new RuntimePackages.PackageAbsent(component==null?"null":component.flattenToShortString());
        require(component.getPackageName(),0,user);
        return dev.tripoder.selfhook.PackageFrameworkAccess.componentEnabled(component,user);
    }
    android.content.pm.ServiceInfo serviceInfo(android.content.ComponentName component,long flags,int user){
        if(component==null||!owns(component.getPackageName()))throw new SecurityException("WEBVIEW_SERVICE_OWNER_REQUIRED");
        require(component.getPackageName(),flags,user);
        android.content.pm.ServiceInfo expected=null;
        if(provider.services!=null)for(var value:provider.services)if(value.name.equals(component.getClassName()))expected=value;
        if(expected==null)throw new RuntimePackages.PackageAbsent(component.flattenToShortString());
        packageInfo(component.getPackageName(),QUERY_FLAGS,user);
        var actual=(android.content.pm.ServiceInfo)dev.tripoder.selfhook.PackageFrameworkAccess.physical("getServiceInfo",component,flags,user);
        if(actual==null||!Objects.equals(expected.name,actual.name)||!Objects.equals(expected.packageName,actual.packageName)
                ||!Objects.equals(expected.processName,actual.processName)||expected.flags!=actual.flags||expected.exported!=actual.exported
                ||!Objects.equals(expected.permission,actual.permission)||actual.applicationInfo==null
                ||expected.applicationInfo.uid!=actual.applicationInfo.uid)
            throw new SecurityException("WEBVIEW_SERVICE_IDENTITY_CHANGED");
        return actual;
    }
    Object updateService(){if(!captured||updater==null)throw new IllegalStateException("WEBVIEW_PROVIDER_UNAVAILABLE");return updater;}
    void requireCodeUse(String name,int reason){
        // API37 PackageManager.NOTIFY_PACKAGE_USE_CROSS_PACKAGE, emitted by LoadedApk.
        if(reason!=6)throw new SecurityException("WEBVIEW_PROVIDER_USE_REASON_DENIED");
        packageInfo(name,QUERY_FLAGS,RuntimePackageBridge.userId());
    }
    void notifyDexLoad(String name,java.util.Map<?,?> contexts,String isa){
        String physical=engine.host.getPackageName();
        if((!physical.equals(name)&&!engine.packageName.equals(name))||!"arm64".equals(isa)
                ||contexts==null||contexts.isEmpty()||contexts.size()>32)throw new SecurityException("DEX_LOAD_REPORT_IDENTITY_DENIED");
        java.util.Set<String> allowed=new java.util.HashSet<>();
        allowed.add(engine.host.getApplicationInfo().sourceDir);allowed.add(engine.info.sourceDir);allowed.add("/system/framework/org.apache.http.legacy.jar");
        if(provider!=null){allowed.add(provider.applicationInfo.sourceDir);
            if(provider.applicationInfo.splitSourceDirs!=null)allowed.addAll(Arrays.asList(provider.applicationInfo.splitSourceDirs));
            if(provider.applicationInfo.sharedLibraryFiles!=null)allowed.addAll(Arrays.asList(provider.applicationInfo.sharedLibraryFiles));}
        for(String a:new java.util.ArrayList<>(allowed))try{allowed.add(new java.io.File(a).getCanonicalPath());}catch(Exception ignored){}
        // The guest's own artifact tree (base.apk, splits, lib/*.so, dex/*) is authenticated and
        // sealed immutable by the install verifier, so a dex-load report naming a file inside it is a
        // legitimate self-reference -- apps routinely pack a secondary dex inside a native library
        // (Alibaba SecurityGuard libsgmain.so, Tinker, ...) and report it to PMS for background dexopt.
        // This is strictly more conservative than the ownData branch below, which already permits the
        // guest's MUTABLE data dir; refusing it only turned a normal self-report into an app crash.
        java.util.Map<String,String> checked=new java.util.LinkedHashMap<>();
        for(var entry:contexts.entrySet()){
            if(!(entry.getKey() instanceof String path)||!(entry.getValue() instanceof String context)||context.length()>65536)
                throw new SecurityException("DEX_LOAD_REPORT_SHAPE_DENIED");
            try{
                java.io.File file=new java.io.File(path);String canonical=file.getCanonicalPath();
                boolean ownData=canonical.startsWith(engine.data.getCanonicalPath()+java.io.File.separator);
                boolean ownArtifact=canonical.startsWith(new java.io.File(engine.info.nativeLibraryDir).getParentFile().getCanonicalPath()+java.io.File.separator);
                boolean platform=(canonical.startsWith("/system/")||canonical.startsWith("/system_ext/")||canonical.startsWith("/product/")||canonical.startsWith("/apex/")||canonical.startsWith("/vendor/")||canonical.startsWith("/odm/"))
                    &&(path.startsWith("/system/")||path.startsWith("/system_ext/")||path.startsWith("/product/")||path.startsWith("/apex/")||path.startsWith("/vendor/")||path.startsWith("/odm/"));
                boolean allowedPath=allowed.contains(path)||allowed.contains(canonical);
                if((!allowedPath&&!ownData&&!ownArtifact&&!platform)||!file.isFile())
                    throw new SecurityException("DEX_LOAD_REPORT_PATH_DENIED:"+path);
            }catch(java.io.IOException error){throw new IllegalStateException("DEX_LOAD_REPORT_PATH_UNAVAILABLE",error);}
            checked.put(path,context);
        }
        // PMS attributes execution to the actually installed host UID/package;
        // guest package identity remains unchanged at every public API boundary.
        dev.tripoder.selfhook.PackageFrameworkAccess.notifyDexLoad(physical,checked,isa);
    }
    private void require(String name,long flags,int user){
        if(!owns(name)||user!=RuntimePackageBridge.userId())throw new SecurityException("WEBVIEW_PROVIDER_QUERY_DENIED");
        if((flags&~QUERY_FLAGS)!=0)throw new UnsupportedOperationException("WEBVIEW_PROVIDER_QUERY_FLAGS_UNSUPPORTED");
    }
    private void identity(ApplicationInfo actual){
        ApplicationInfo expected=provider.applicationInfo;
        if(actual==null||!Objects.equals(expected.packageName,actual.packageName)||expected.uid!=actual.uid
                ||!Objects.equals(expected.sourceDir,actual.sourceDir)||!Objects.equals(expected.publicSourceDir,actual.publicSourceDir)
                ||!Arrays.equals(expected.splitSourceDirs,actual.splitSourceDirs)
                ||!Arrays.equals(expected.sharedLibraryFiles,actual.sharedLibraryFiles))
            throw new SecurityException("WEBVIEW_PROVIDER_CODE_IDENTITY_CHANGED");
        try{if(!Arrays.equals(dev.tripoder.selfhook.ResourceDependencyAccess.apkPaths(expected),dev.tripoder.selfhook.ResourceDependencyAccess.apkPaths(actual)))throw new SecurityException("WEBVIEW_PROVIDER_RESOURCE_PROFILE_CHANGED");}
        catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("WEBVIEW_PROVIDER_RESOURCE_PROFILE_UNAVAILABLE",failure);}
    }
    PackageInfo packageInfo(String name,long flags,int user){
        require(name,flags,user);
        PackageInfo live=(PackageInfo)dev.tripoder.selfhook.PackageFrameworkAccess.physical("package",name,QUERY_FLAGS,user);
        if(live==null||provider.getLongVersionCode()!=live.getLongVersionCode()
                ||!new java.util.HashSet<>(Arrays.asList(provider.signatures)).equals(new java.util.HashSet<>(Arrays.asList(live.signatures))))
            throw new SecurityException("WEBVIEW_PROVIDER_VERSION_OR_SIGNER_CHANGED");
        identity(live.applicationInfo);
        // Preserve normal flag-shaped PackageInfo output after the full identity check.
        PackageInfo result=(PackageInfo)dev.tripoder.selfhook.PackageFrameworkAccess.physical("package",name,flags,user);
        if(result==null||result.getLongVersionCode()!=provider.getLongVersionCode())throw new SecurityException("WEBVIEW_PROVIDER_QUERY_CHANGED");
        return result;
    }
    ApplicationInfo applicationInfo(String name,long flags,int user){
        packageInfo(name,flags,user);
        ApplicationInfo result=(ApplicationInfo)dev.tripoder.selfhook.PackageFrameworkAccess.physical("application",name,flags,user);
        // A query without GET_SHARED_LIBRARY_FILES intentionally omits that field.
        if(result==null||!Objects.equals(provider.applicationInfo.sourceDir,result.sourceDir)||!owns(result.packageName))
            throw new SecurityException("WEBVIEW_PROVIDER_APPLICATION_CHANGED");
        return result;
    }
    Resources resources(String name,ApplicationInfo supplied){
        require(name,0,RuntimePackageBridge.userId());if(supplied!=null)identity(supplied);return resources;
    }
    Resources contextResources(Context context){
        if(context==null||!context.getClass().getName().equals("android.app.ContextImpl")||!owns(context.getPackageName()))
            throw new SecurityException("WEBVIEW_PROVIDER_CONTEXT_REQUIRED");
        identity(context.getApplicationInfo());return context.getResources();
    }
}
