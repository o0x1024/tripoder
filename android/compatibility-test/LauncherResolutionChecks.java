package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;

/** Real SDK IntentFilter matching over the signed owned fixture; no Activity is dispatched. */
final class LauncherResolutionChecks {
    private static final String PKG="dev.tripoder.launchcontract";
    private final BiConsumer<String,Boolean> check;
    LauncherResolutionChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private static boolean selected(Intent value,String name,String category){return value!=null&&value.getComponent()!=null&&value.getComponent().getClassName().equals(PKG+name)&&value.getAction().equals(Intent.ACTION_MAIN)&&value.hasCategory(category)&&(value.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)!=0;}
    private void refused(ApkStore.BundleInstall install,String expected,String code)throws Exception{
        Intent chosen=GuestIntentResolver.launchIntent(install,false);check.accept("launch-model-restricted-entry-remains-selected-"+expected,chosen!=null&&chosen.getComponent().getClassName().equals(PKG+expected));
        boolean denied=false;try{RuntimeComponents.authorize(RuntimeComponents.activity(install,chosen.getComponent().getClassName()),"dev.tripoder.owned.controller");}catch(SecurityException failure){denied=code.equals(failure.getMessage());}
        check.accept("launch-model-restricted-entry-is-refused-without-another-selection-"+expected,denied&&selected(GuestIntentResolver.launchIntent(install,false),expected,Intent.CATEGORY_INFO));
    }
    void run(Context owned,Context assets)throws Exception{
        File stage=new File(owned.getFilesDir(),"launcher-apk");if(!stage.mkdir())throw new IllegalStateException("LAUNCH_MODEL_STAGE");
        try(var input=assets.getAssets().open("launcher-contract.apk")){Files.copy(input,new File(stage,"base.apk").toPath());}
        ArtifactSet artifacts=ArtifactSet.materialize(stage);
        String descriptorBefore=artifacts.descriptorJson,digestBefore=artifacts.descriptorSha;
        var info=owned.getPackageManager().getPackageArchiveInfo(artifacts.base.toString(),PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS|PackageManager.MATCH_DISABLED_COMPONENTS);
        RuntimeManifestIndex.includeSplits(artifacts,info);RuntimeManifestFilters.prepare(owned,artifacts,info);
        boolean resourcePriority=false,rawReference=false,servicePriority=false,receiverPriority=false;
        for(var component:artifacts.components){
            if(component.name.equals(PKG+".Info")){resourcePriority=artifacts.filters(component).get(0).priority==-5;rawReference=component.declaration.children.stream().anyMatch(element->element.name.equals("intent-filter")&&element.android("priority","").startsWith("@0x"));}
            if(component.name.equals(PKG+".PriorityService"))servicePriority=artifacts.filters(component).get(0).priority==-20;
            if(component.name.equals(PKG+".PriorityReceiver"))receiverPriority=artifacts.filters(component).get(0).priority==-30;
        }
        check.accept("launch-model-reference-priority-resolved-by-sealed-sdk-resources",resourcePriority&&servicePriority);
        check.accept("launch-model-receiver-reference-priority-uses-the-shared-projection",receiverPriority);
        check.accept("launch-model-projection-keeps-original-descriptor-and-reference",rawReference&&descriptorBefore.equals(artifacts.descriptorJson)&&digestBefore.equals(artifacts.descriptorSha));
        var compiled=artifacts.filterSpecs;artifacts.prepareFilters(id->{throw new AssertionError("FILTER_PROJECTION_MUST_NOT_REFREEZE");});
        check.accept("launch-model-published-projection-is-not-resolved-again",compiled==artifacts.filterSpecs);
        ArtifactSet lite=ArtifactSet.loadTrusted(stage,artifacts.descriptorSha,artifacts.liteJson(),ArtifactIntegrityCache.stampDigest(stage));
        lite.prepareFilters(id->{throw new AssertionError("LITE_PROJECTION_MUST_NOT_RESOLVE_RESOURCES");});
        boolean litePriorities=true;int liteFound=0;
        for(var component:lite.components){
            if(component.name.equals(PKG+".Info")){litePriorities&=lite.filters(component).get(0).priority==-5;liteFound++;}
            if(component.name.equals(PKG+".PriorityService")){litePriorities&=lite.filters(component).get(0).priority==-20;liteFound++;}
            if(component.name.equals(PKG+".PriorityReceiver")){litePriorities&=lite.filters(component).get(0).priority==-30;liteFound++;}
        }
        check.accept("launch-model-worker-lite-carries-the-same-resolved-priorities",litePriorities&&liteFound==3);
        File literalStage=new File(owned.getFilesDir(),"literal-launcher-apk");if(!literalStage.mkdir())throw new IllegalStateException("LITERAL_LAUNCH_MODEL_STAGE");
        try(var input=assets.getAssets().open("launcher-literals.apk")){Files.copy(input,new File(literalStage,"base.apk").toPath());}
        ArtifactSet literals=ArtifactSet.materialize(literalStage);boolean signedPriority=false;
        for(var component:literals.components)if(component.name.equals(PKG+".Info"))signedPriority=ArtifactSet.manifestFilters(component).get(0).priority==-5;
        check.accept("launch-model-signed-binary-priority-preserves-int32-bits",signedPriority);
        File failedStage=new File(owned.getFilesDir(),"failed-filter-projection");if(!failedStage.mkdir())throw new IllegalStateException("FAILED_FILTER_MODEL_STAGE");
        try(var input=assets.getAssets().open("launcher-contract.apk")){Files.copy(input,new File(failedStage,"base.apk").toPath());}
        ArtifactSet failed=ArtifactSet.materialize(failedStage);RuntimeException expected=new IllegalStateException("OWNED_FILTER_RESOURCE_FAILURE");boolean exact=false;
        try{failed.prepareFilters(id->{throw expected;});}catch(RuntimeException error){exact=error==expected;}
        check.accept("launch-model-failed-resource-lookup-publishes-no-partial-filter-map",exact&&failed.filterSpecs.isEmpty());
        ApkStore.BundleInstall install=new ApkStore.BundleInstall("owned-launch",UUID.randomUUID().toString(),artifacts.base,artifacts.parts.get(0).sha256,info,new File(stage,"data"),artifacts);
        Intent first=GuestIntentResolver.launchIntent(install,false);
        check.accept("launch-model-info-has-priority-over-launcher",selected(first,".Info",Intent.CATEGORY_INFO));
        Map<String,String> categories=GuestIntentResolver.entryCategories(install);
        check.accept("launch-model-classification-uses-the-same-real-filter-matches",Intent.CATEGORY_INFO.equals(categories.get(PKG+".Info"))&&Intent.CATEGORY_LAUNCHER.equals(categories.get(PKG+".LaunchHigh"))&&!categories.containsKey(PKG+".InfoWithData"));
        check.accept("launch-model-data-restricted-higher-priority-info-is-not-a-match",!first.getComponent().getClassName().equals(PKG+".InfoWithData"));
        var alias=RuntimeComponents.activity(install,first.getComponent().getClassName());
        check.accept("launch-model-alias-preserves-name-and-target",alias.name.equals(PKG+".Info")&&alias.targetActivity.equals(PKG+".Main"));
        first.setAction("owned-mutated-intent").setComponent(new ComponentName(PKG,PKG+".Main"));
        check.accept("launch-model-result-mutation-does-not-change-authority-selection",selected(GuestIntentResolver.launchIntent(install,false),".Info",Intent.CATEGORY_INFO));
        Bundle states=new Bundle();states.putInt(PKG+".PreferredInfo",1);install.applyComponentSettings(states);
        check.accept("launch-model-enabled-candidate-uses-filter-priority",selected(GuestIntentResolver.launchIntent(install,false),".PreferredInfo",Intent.CATEGORY_INFO));
        install.applyComponentSettings(new Bundle());check.accept("launch-model-default-restores-disabled-declaration",selected(GuestIntentResolver.launchIntent(install,false),".Info",Intent.CATEGORY_INFO));
        states=new Bundle();states.putInt(PKG+".Info",2);install.applyComponentSettings(states);
        check.accept("launch-model-no-matching-info-uses-highest-priority-launcher",selected(GuestIntentResolver.launchIntent(install,false),".LaunchHigh",Intent.CATEGORY_LAUNCHER));
        states.putInt(PKG+".LaunchHigh",2);states.putInt(PKG+".LaunchLow",2);install.applyComponentSettings(states);
        check.accept("launch-model-no-enabled-matching-entry-is-absent",GuestIntentResolver.launchIntent(install,false)==null);
        states=new Bundle();states.putInt(PKG+".PrivateInfo",1);install.applyComponentSettings(states);refused(install,".PrivateInfo","COMPONENT_NOT_EXPORTED");
        states=new Bundle();states.putInt(PKG+".PermissionInfo",1);install.applyComponentSettings(states);refused(install,".PermissionInfo","COMPONENT_PERMISSION_UNSUPPORTED");
        install.applyComponentSettings(new Bundle());check.accept("launch-model-leanback-query-does-not-invent-a-tv-entry",GuestIntentResolver.launchIntent(install,true)==null);
        check.accept("launch-model-state-restoration-keeps-original-info-selection",selected(GuestIntentResolver.launchIntent(install,false),".Info",Intent.CATEGORY_INFO));
        concurrentProjection(install);
    }
    private void concurrentProjection(ApkStore.BundleInstall install)throws Exception{
        var start=new java.util.concurrent.CountDownLatch(1);var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var writer=workers.submit(()->{try{start.await();for(int i=0;i<250;i++){Bundle states=new Bundle();states.putInt(PKG+".PreferredInfo",i%2==0?1:0);install.applyComponentSettings(states);Thread.yield();}}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}});
            var reader=workers.submit(()->{try{start.await();for(int i=0;i<250;i++){
                var entries=RuntimeControllerLaunch.entries(install);
                for(int n=0;n<entries.length();n++){var entry=entries.getJSONObject(n);if(entry.getString("component").equals(PKG+".PreferredInfo")&&(!entry.getBoolean("launcher")||!entry.getString("entryCategory").equals(Intent.CATEGORY_INFO)))throw new AssertionError("LAUNCH_ENTRY_STATE_CATEGORY_TORN");}
                Thread.yield();
            }}catch(Exception failure){throw new IllegalStateException(failure);}});
            start.countDown();writer.get(10,java.util.concurrent.TimeUnit.SECONDS);reader.get(10,java.util.concurrent.TimeUnit.SECONDS);
            check.accept("launch-model-concurrent-state-and-entry-classification-share-one-snapshot",true);
        }finally{workers.shutdownNow();install.applyComponentSettings(new Bundle());}
    }
}
