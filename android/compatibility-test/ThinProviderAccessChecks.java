package dev.tripoder.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;

/** Real broker/SQLite authorization models with synthetic PIDs, never Binder identity evidence. */
final class ThinProviderAccessChecks {
    private final BiConsumer<String,Boolean> check;
    private int nextPid=210000;
    private RuntimeBroker broker;
    private Map<String,RuntimeBroker.Slot> slots;
    private RuntimeBroker.Slot source,target;
    private String open;
    private static final String PKG="dev.tripoder.closurefixture";
    ThinProviderAccessChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private interface Request {void run()throws Exception;}
    private void denied(String name,String code,Request body)throws Exception{
        boolean refused=false;try{body.run();}catch(Exception error){refused=error instanceof ExecutionContract.ContractException?code.equals(((ExecutionContract.ContractException)error).code):String.valueOf(error.getMessage()).contains(code);}
        check.accept("thin-access-model-"+name,refused);
    }
    private static String generation(RuntimeBroker.Slot slot){return slot.allocation.session.processGeneration;}
    private static long count(SQLiteDatabase db,String table){try(var c=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){c.moveToFirst();return c.getLong(0);}}
    private ApkStore.BundleInstall install(Context owned,Context assets,String asset,String space,String directory)throws Exception{
        File stage=new File(owned.getFilesDir(),directory);if(!stage.mkdir())throw new IllegalStateException("THIN_MODEL_STAGE");
        try(var input=assets.getAssets().open(asset)){Files.copy(input,new File(stage,"base.apk").toPath());}
        var artifacts=ArtifactSet.materialize(stage);
        var info=owned.getPackageManager().getPackageArchiveInfo(artifacts.base.toString(),PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS|PackageManager.MATCH_DISABLED_COMPONENTS);
        var install=new ApkStore.BundleInstall(space,UUID.randomUUID().toString(),artifacts.base,artifacts.parts.get(0).sha256,info,new File(stage,"data"),artifacts);
        var db=broker.store.getWritableDatabase();db.execSQL("INSERT OR IGNORE INTO spaces VALUES(?,1)",new Object[]{space});
        db.execSQL("INSERT INTO installs VALUES(?,?,?,?,?,?,'COMMITTED','',1,'owned',?,'owned-import')",new Object[]{space,info.packageName,install.generation,artifacts.base.toString(),artifacts.parts.get(0).sha256,artifacts.descriptorSha,install.data.getName()});
        InstallProviderIndex.publish(db,space,install.generation,InstallProviderIndex.authorities(artifacts));return install;
    }
    private RuntimeBroker.Slot slot(ApkStore.BundleInstall install,RuntimeMode mode,boolean attach){
        // Additional scratch generations use distinct process identities; no physical process is launched.
        String process=slots.values().stream().anyMatch(s->s.install==install)?install.info.packageName+":owned_model_"+nextPid:install.info.packageName;
        return slot(install,mode,attach,process);
    }
    private RuntimeBroker.Slot slot(ApkStore.BundleInstall install,RuntimeMode mode,boolean attach,String process){
        var allocated=broker.contract.allocate(install.space,install.info.packageName,install.generation,process,android.os.Process.myUid());
        String stub=RuntimeSlot.activity(nextPid%8).getName();broker.store.reserve(allocated.session,stub);
        var slot=new RuntimeBroker.Slot(install,allocated,stub,mode,null);slots.put(generation(slot),slot);
        int observedPid=nextPid++;if(attach)broker.store.session(broker.contract.attach(allocated.bootstrapToken,observedPid,android.os.Process.myUid()));
        return slot;
    }
    private String lease(RuntimeBroker.Slot slot,String component){return broker.providers.ensurePublication(slot,PKG+component).getString("lease");}
    private Bundle request(RuntimeBroker.Slot from,String lease,String kind,String suffix){
        Bundle input=new Bundle();input.putString("generation",generation(target));input.putString("source",generation(from));input.putString("lease",lease);input.putString("kind",kind);input.putString("uri","content://"+PKG+suffix);
        var caller=broker.contract.getSession(generation(from));input.putInt("callerPid",caller.pid>0?caller.pid:290000);input.putInt("callerUid",caller.pid>0?caller.uid:caller.expectedUid);return input;
    }
    private Bundle call(Bundle input)throws Exception{
        // This scratch authority has no live controller/recovery supervisor.
        // Exercise the actual authenticated provider route with model PIDs;
        // the separate peer verifies the real Endpoint and Binder transport.
        var owner=broker.contract.getSession(generation(target));var route=RuntimeBroker.class.getDeclaredMethod("routeProvider",int.class,Bundle.class,int.class,int.class);route.setAccessible(true);
        try{return (Bundle)route.invoke(broker,RuntimeWire.PROVIDER_THIN_ACCESS,input,owner.pid,owner.uid);}
        catch(java.lang.reflect.InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw new AssertionError(cause);}
    }
    private static Bundle batch(Bundle input,String[] uris,String[] kinds){Bundle routes=new Bundle();routes.putStringArray("uris",uris);routes.putStringArray("kinds",kinds);input.putBundle("batchRoutes",routes);return input;}
    void run(Context owned,Context assets)throws Exception{
        broker=new RuntimeBroker(owned);
        try{
            var field=RuntimeBroker.class.getDeclaredField("slots");field.setAccessible(true);slots=(Map<String,RuntimeBroker.Slot>)field.get(broker);
            var targetInstall=install(owned,assets,"thin-target.apk","owned-thin","target");
            var sourceInstall=install(owned,assets,"thin-source.apk","owned-thin","source");
            var foreignInstall=install(owned,assets,"thin-source.apk","owned-thin-foreign","foreign");
            target=slot(targetInstall,RuntimeMode.THIN,true);source=slot(sourceInstall,RuntimeMode.THIN,true);
            synchronized(broker){
                broker.providers.reserve(target);open=lease(target,".PeerProviders$Open");String protectedLease=lease(target,".PeerProviders$Protected"),path=lease(target,".PeerProviders$Path"),privateLease=lease(target,".OwnedDocumentsProvider");
                String history=UUID.randomUUID().toString();broker.contract.accept(history,"owned-model","a".repeat(64));broker.contract.start(history);broker.contract.markUnknown(history,"RETAINED_THIN_MODEL_UNKNOWN");
                var db=broker.store.getWritableDatabase();long operations=count(db,"operations"),calls=count(db,"provider_calls"),acquisitions=count(db,"provider_acquisitions");
                Bundle good=request(source,open,"query",".peer-open/owned"),answer=call(good);var caller=broker.contract.getSession(generation(source));
                check.accept("thin-access-model-attached-caller-derived-from-authenticated-session",caller.identity.packageName.equals(answer.getString("callerPackage"))&&answer.getInt("callerPid")==caller.pid&&answer.getInt("callerUid")==caller.uid&&generation(source).equals(answer.getString("source")));
                broker.store.session(broker.contract.markRunning(generation(source),caller.pid,caller.uid));call(good);check.accept("thin-access-model-running-caller-accepted",true);
                Bundle disabled=new Bundle();disabled.putInt(PKG+".PeerProviders$Open",2);targetInstall.applyComponentSettings(disabled);
                try{call(good);check.accept("thin-access-model-existing-publication-handle-survives-component-disable",!new RuntimeProviderIndex(targetInstall.info.providers,targetInstall.components()).component(PKG+".PeerProviders$Open").enabled);}
                finally{targetInstall.applyComponentSettings(new Bundle());}
                Bundle changed=new Bundle(good);changed.putInt("callerPid",caller.pid+40000);denied("wrong-pid","CALLER_MISMATCH",()->call(changed));
                Bundle uid=new Bundle(good);uid.putInt("callerUid",caller.uid+1);denied("wrong-uid","CALLER_MISMATCH",()->call(uid));
                Bundle borrowed=new Bundle(good);borrowed.putString("source",generation(target));denied("borrow-target-generation","CALLER_MISMATCH",()->call(borrowed));
                Bundle unknown=new Bundle(good);unknown.putString("source",UUID.randomUUID().toString());denied("unknown-source","SESSION_MISSING",()->call(unknown));
                source.closing=true;try{denied("closing-source","PROCESS_CLOSING",()->call(good));}finally{source.closing=false;}
                target.closing=true;try{denied("closing-target","PROCESS_CLOSING",()->call(good));}finally{target.closing=false;}
                slots.remove(generation(target));try{denied("missing-target-slot","STALE_SESSION",()->call(good));}finally{slots.put(generation(target),target);}
                var foreign=slot(foreignInstall,RuntimeMode.THIN,true);denied("cross-space","PROVIDER_SPACE_MISMATCH",()->call(request(foreign,open,"query",".peer-open/owned")));
                var thickSource=new RuntimeBroker.Slot(source.install,source.allocation,source.activityStub,RuntimeMode.THICK,null);slots.put(generation(source),thickSource);
                try{denied("thick-source","PROVIDER_THIN_MODE_REQUIRED",()->call(good));}finally{slots.put(generation(source),source);}
                var thickTarget=new RuntimeBroker.Slot(target.install,target.allocation,target.activityStub,RuntimeMode.THICK,null);slots.put(generation(target),thickTarget);
                try{denied("thick-target","PROVIDER_THIN_MODE_REQUIRED",()->call(good));}finally{slots.put(generation(target),target);}
                var otherTarget=slot(targetInstall,RuntimeMode.THIN,true,PKG+":remote");broker.providers.reserve(otherTarget);String otherLease=lease(otherTarget,".CanonicalProvider$Remote");
                denied("lease-owned-by-other-target","PROVIDER_LEASE_OWNER_MISMATCH",()->call(request(source,otherLease,"query",".peer-open/owned")));
                denied("unknown-lease","PROVIDER_LEASE_UNKNOWN",()->call(request(source,UUID.randomUUID().toString(),"query",".peer-open/owned")));
                denied("authority-mismatch","PROVIDER_AUTHORITY_NOT_PUBLISHED",()->call(request(source,open,"query",".peer-protected/owned")));
                denied("private-publication","PROVIDER_NOT_EXPORTED",()->call(request(source,privateLease,"query",".documents/owned")));
                for(String kind:List.of("query","insert","call"))denied("permission-"+kind,"PROVIDER_PERMISSION_UNSUPPORTED",()->call(request(source,protectedLease,kind,".peer-protected/owned")));
                denied("path-permission","PROVIDER_PATH_PERMISSION_UNSUPPORTED",()->call(request(source,path,"query",".peer-path/private/owned")));
                call(request(source,path,"query",".peer-path/public/owned"));check.accept("thin-access-model-public-path-remains-allowed",true);
                for(boolean reverse:new boolean[]{false,true}){
                    String allowed="content://"+PKG+".peer-path/public/owned",blocked="content://"+PKG+".peer-path/private/owned";
                    Bundle mixed=batch(request(source,path,"applyBatch",".peer-path"),new String[]{reverse?blocked:allowed,reverse?allowed:blocked},new String[]{"query","query"});
                    denied("mixed-batch-"+reverse,"PROVIDER_PATH_PERMISSION_UNSUPPORTED",()->call(mixed));
                }
                denied("empty-private-batch","PROVIDER_NOT_EXPORTED",()->call(batch(request(source,privateLease,"applyBatch",".documents"),new String[0],new String[0])));
                denied("cross-authority-batch","PROVIDER_BATCH_ROUTE_INVALID",()->call(batch(request(source,open,"applyBatch",".peer-open"),new String[]{"content://"+PKG+".peer-path/public"},new String[]{"query"})));
                call(request(target,privateLease,"query",".documents/owned"));check.accept("thin-access-model-canonical-owner-retains-private-access",true);
                var allocated=slot(sourceInstall,RuntimeMode.THIN,false);denied("unattached-source","SESSION_INACTIVE",()->call(request(allocated,open,"query",".peer-open/owned")));
                for(boolean failed:new boolean[]{false,true}){
                    var ended=slot(sourceInstall,RuntimeMode.THIN,true);if(failed)broker.store.session(broker.contract.markFailed(generation(ended),"OWNED_MODEL"));else broker.store.session(broker.contract.markDead(generation(ended)));
                    denied(failed?"failed-source":"dead-source","SESSION_INACTIVE",()->call(request(ended,open,"query",".peer-open/owned")));
                }
                check.accept("thin-access-model-readonly-gate-creates-no-journal-or-reference",operations==count(db,"operations")&&calls==count(db,"provider_calls")&&acquisitions==count(db,"provider_acquisitions"));
                check.accept("thin-access-model-original-unknown-outcome-retained",broker.contract.query(history).state==ExecutionContract.OperationState.UNKNOWN&&"RETAINED_THIN_MODEL_UNKNOWN".equals(broker.contract.query(history).detail));
            }
        }finally{broker.store.close();}
    }
}
