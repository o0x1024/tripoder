package dev.tripoder.runtime;

import android.os.Bundle;
import java.util.concurrent.*;
import org.json.JSONArray;

/** Production SQL/receipt paths with a deliberately blocked or failing APK loader. */
public final class CatalogMetadataTest {
    private static int checks;
    private static void check(boolean passed,String name){if(!passed)throw new AssertionError(name);checks++;}
    private interface Attempt{void run()throws Exception;}
    private static void rejects(Attempt attempt,String reason)throws Exception{
        try{attempt.run();throw new AssertionError("accepted "+reason);}catch(IllegalStateException expected){check(expected.getMessage().contains(reason),reason);}
    }
    private static void install(RuntimeBroker broker,String space,String generation,String data){
        synchronized(broker){broker.store.db.execSQL("INSERT OR REPLACE INTO installs(space,package,generation,dataId,state) VALUES ('"+space+"','owned.app','"+generation+"','"+data+"','COMMITTED')");}
    }
    public static void main(String[] args)throws Exception{
        RuntimeBroker broker=new RuntimeBroker();install(broker,"a","g1","data1");install(broker,"b","g2","data2");
        broker.apks.loading=()->{throw new AssertionError("listing tried to open an APK");};
        Bundle catalog=RuntimeControllerCatalog.read(broker);
        check(new JSONArray(catalog.getString("installs")).length()==2,"both spaces listed without APKs");
        JSONArray owners=new JSONArray(catalog.getString("applications"));String project=owners.getJSONObject(0).getString("project");
        check(owners.length()==1,"one owner across spaces");
        ProjectTargetBindings bindings=new ProjectTargetBindings(broker);
        Bundle first=bindings.resolveMetadataIn(project,"a"),second=bindings.resolveMetadataIn(project,"b");
        check(!first.getString("bindingOperation").equals(second.getString("bindingOperation")),"separate binding revisions per space");
        int receipts=broker.store.operations.size();
        check(catalog.getString("projects").equals(RuntimeControllerCatalog.read(broker).getString("projects")),"repeat reads preserve revisions");
        check(receipts==broker.store.operations.size()&&broker.apks.loads==0,"repeat reads create no receipts or APK loads");

        bindings.setModeIn("mode1","trusted-workbench",project,"a","thick");
        install(broker,"a","g3","data1");
        broker.store.db.execSQL("UPDATE project_refs SET state='STALE' WHERE space='a'");
        RuntimeControllerCatalog.read(broker);Bundle upgraded=bindings.resolveMetadataIn(project,"a");
        check(upgraded.getString("generation").equals("g3")&&upgraded.getString("mode").equals("thick"),"metadata repair preserves mode on upgrade");
        check(new ApplicationProjects(broker).ensure("owned.app").equals(project),"upgrade preserves canonical project");
        check(bindings.resolveMetadataIn(project,"b").getString("bindingOperation").equals(second.getString("bindingOperation")),"upgrade does not replace sibling binding");
        rejects(()->bindings.resolveBinding(project,first.getString("bindingOperation")),"PROJECT_NOT_BOUND");
        check(broker.apks.loads==0,"stale identity rejected before opening APK");

        // A slow real target must leave the global lock available to a concurrent list read.
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        broker.apks.loading=()->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("loader release timeout");}catch(InterruptedException e){throw new AssertionError(e);}};
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<Bundle> target=pool.submit(()->new ApplicationProjects(broker).target("a","owned.app","g3"));
            check(entered.await(2,TimeUnit.SECONDS),"target reached APK loader without global lock");
            Future<Bundle> listing=pool.submit(()->RuntimeControllerCatalog.read(broker));
            check(new JSONArray(listing.get(2,TimeUnit.SECONDS).getString("installs")).length()==2,"catalog completes while APK load is blocked");
            release.countDown();check(target.get(2,TimeUnit.SECONDS).getString("generation").equals("g3"),"real target still resolves artifacts");
        }finally{release.countDown();pool.shutdownNow();}

        // Mutations racing the out-of-lock parse cannot return a usable stale target.
        broker.apks.loading=()->install(broker,"a","g4","data1");
        rejects(()->bindings.resolveBinding(project,upgraded.getString("bindingOperation")),"PROJECT_BINDING_STALE");
        RuntimeControllerCatalog.read(broker);Bundle latest=bindings.resolveMetadataIn(project,"a");
        broker.apks.loading=()->{synchronized(broker){broker.store.db.execSQL("DELETE FROM installs WHERE space='a'");}};
        rejects(()->bindings.resolveBinding(project,latest.getString("bindingOperation")),"PROJECT_BINDING_STALE");
        install(broker,"a","g4","otherdata");
        rejects(()->bindings.resolveMetadataIn(project,"a"),"PROJECT_BINDING_STALE");

        broker.store.db.execSQL("UPDATE installs SET state='CORRUPT' WHERE space='a'");
        int loads=broker.apks.loads;
        check(new JSONArray(RuntimeControllerCatalog.read(broker).getString("installs")).length()==2,"corrupt install remains visible without parsing");
        check(broker.apks.loads==loads,"corrupt list does not load artifacts");
        check(new JSONArray(RuntimeControllerCatalog.read(broker).getString("applications")).getJSONObject(0).getString("project").equals(project),"corruption retains application ownership");
        System.out.println("catalog-metadata-host: "+checks+" checks passed");
    }
}
