package dev.tripoder.runtime;

import android.app.PendingIntent;
import android.content.*;
import android.os.*;
import java.util.*;

/** Real Android tokens with one durable mutation owner; no system RPC under the broker lock. */
final class RuntimePendingIntentBroker {
    private final RuntimeBroker broker;
    private final PendingIntentLedger ledger;
    RuntimePendingIntentBroker(RuntimeBroker broker){this.broker=broker;ledger=broker.pendingIntents;}
    /** Schema cutover withdraws the old physical route; original operations and
     * their UNKNOWN outcomes are preserved. Re-observe the exact system token,
     * never replay a send or infer a guest completion from its absence. */
    void retireDirectTransports()throws Exception{
        outsideLock();for(var value:ledger.retiredDirectRoutes()){
            PendingIntent token=lookup(value);if(token!=null)token.cancel();
            String operation=UUID.randomUUID().toString(),digest=ApkStore.textDigest("retire-direct-transport:"+value.version);
            synchronized(broker){atomic(()->{broker.contract.accept(operation,"pending-install:"+value.install,digest);broker.contract.start(operation);broker.contract.succeed(operation,"OLD_TRANSPORT_TOKEN_ABSENT_OR_CANCEL_RETURNED");ledger.retiredTransportReceipt(operation,value);});}
        }
    }
    Bundle route(Bundle request,int pid,int uid)throws Exception{
        ApkStore.BundleInstall install;
        synchronized(broker){String generation=RuntimeWire.required(request,"generation");broker.contract.authenticate(generation,pid,uid);install=broker.requiredSlot(generation).install;}
        String kind=RuntimeWire.required(request,"kind");
        if(kind.equals("view")){synchronized(broker){current(install);return view(owned(install,RuntimeWire.required(request,"version")));}}
        return mutate(install,request,kind);
    }
    /** Serialize live client mutations without holding the Broker monitor over
     * Android RPCs. Completion/ingress callbacks never take this monitor. A
     * durable owner left UNKNOWN by a dead authority still fails requireIdle. */
    private synchronized Bundle mutate(ApkStore.BundleInstall install,Bundle request,String kind)throws Exception{
        if(kind.equals("alarm"))return new RuntimeAlarmBroker(broker).route(install,request);
        if(kind.equals("get"))return get(install,request.getInt("requestCode"),RuntimeBroadcastIntent.decode(request.getByteArray("payload")),request.getInt("flags"),RuntimeWire.required(request,"operation"),request.getString("tokenKind","broadcast"));
        String version=RuntimeWire.required(request,"version");PendingIntentLedger.Version value;
        synchronized(broker){current(install);value=owned(install,version);}
        if(kind.equals("cancel")){cancel(install,value,RuntimeWire.required(request,"operation"));return new Bundle();}
        throw new UnsupportedOperationException("PENDING_OPERATION_UNSUPPORTED");
    }
    Bundle get(ApkStore.BundleInstall install,int requestCode,Intent input,int flags,String operation)throws Exception{return get(install,requestCode,input,flags,operation,"broadcast");}
    /** tokenKind: "broadcast" (the original token), or "activity"/"service": the same ledger, but the token's physical target is an authority trampoline that makes the guest start the component. */
    Bundle get(ApkStore.BundleInstall install,int requestCode,Intent input,int flags,String operation,String tokenKind)throws Exception{
        if(!Set.of("broadcast","activity","service").contains(tokenKind))throw new UnsupportedOperationException("PENDING_KIND_UNSUPPORTED:"+tokenKind);
        outsideLock();RuntimePendingIntentPolicy.validate(install.info.applicationInfo.targetSdkVersion,flags);
        Intent logical=RuntimePendingIntentPayload.logical(input,tokenKind);String key=RuntimePendingIntentPayload.identity(install.generation,install.data.getName(),requestCode,flags,logical);
        if(!tokenKind.equals("broadcast"))key=ApkStore.textDigest(tokenKind+":"+key);
        RuntimePendingIntentPolicy.validateDestination(install.info.applicationInfo.targetSdkVersion,flags,logical.getComponent()!=null,logical.getPackage()!=null);
        String digest=ApkStore.textDigest("GET:"+key+":"+flags+":"+Base64.getEncoder().encodeToString(RuntimeBroadcastIntent.encodeFor(tokenKind,logical)));
        PendingIntentLedger.Version prior;ApkManifest.Component receiver;
        // In thin mode Android owns token lookup. A read-only get/NO_CREATE
        // needs no operation row, SQLite transaction, fsync or mutation lease.
        // UPDATE/CANCEL still use the mutation path below so payload changes and
        // token replacement retain their actual system semantics.
        boolean readOnlyLookup;PendingIntentLedger.Version lookupPrior;
        synchronized(broker){
            current(install);
            readOnlyLookup=(flags&(RuntimePendingIntentPolicy.UPDATE_CURRENT|RuntimePendingIntentPolicy.CANCEL_CURRENT))==0;
            lookupPrior=readOnlyLookup?ledger.current(key):null;
        }
        if(readOnlyLookup){
            PendingIntent observed=lookupPrior==null?null:lookup(lookupPrior);
            if(observed!=null)return token(lookupPrior,observed);
            if((flags&RuntimePendingIntentPolicy.NO_CREATE)!=0)return new Bundle();
        }
        synchronized(broker){
            current(install);

            if(tokenKind.equals("broadcast"))receiver=logical.getComponent()==null?null:receiver(install,logical,true);
            else receiver=component(install,logical,tokenKind,true);
            ledger.requireIdle(install.generation);prior=ledger.current(key);
            atomic(()->begin(operation,install,prior==null?"":prior.version,"GET",digest));
        }
        boolean effectAttempted=false;
        try{
            PendingIntent observed=prior==null?absent(install,key,requestCode,flags,logical,tokenKind):lookup(prior);
            boolean noCreate=(flags&RuntimePendingIntentPolicy.NO_CREATE)!=0,cancel=(flags&RuntimePendingIntentPolicy.CANCEL_CURRENT)!=0;
            if(observed!=null&&!cancel){
                if((flags&RuntimePendingIntentPolicy.UPDATE_CURRENT)!=0){
                    effectAttempted=true;
                    PendingIntent updated=create(prior.kind(),requestCode,physical(prior,logical),physicalFlags(prior)|PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_NO_CREATE);
                    if(updated!=null&&!updated.equals(observed))throw new IllegalStateException("PENDING_UPDATE_TOKEN_CHANGED");
                    observed=updated;
                    if(updated!=null)synchronized(broker){ledger.payload(prior.version,RuntimeBroadcastIntent.encodeFor(prior.kind(),logical));}
                }
                if(observed!=null)synchronized(broker){atomic(()->finish(operation,"SYSTEM_TOKEN_OBSERVED"));return token(ledger.get(prior.version),observed);}
            }
            if(prior!=null){synchronized(broker){ledger.retire(prior.version);}if(observed!=null){effectAttempted=true;observed.cancel();}}
            if(noCreate){synchronized(broker){atomic(()->finish(operation,"SYSTEM_NO_CREATE_ABSENT"));}return new Bundle();}
            PendingIntentLedger.Version prepared;
            synchronized(broker){
                current(install);String version=BroadcastLedger.token();
                RuntimeBroker.Slot live=receiver==null?null:broker.findProcess(install.space,install.info.packageName,receiver.process);String hookGeneration=live!=null&&broker.store.hookRecoveryBlocked(live.allocation.session.processGeneration)?live.allocation.session.processGeneration:null;
                ledger.prepare(version,key,install,receiver==null?"":receiver.process,tokenKind.equals("broadcast")?(receiver==null?"":receiver.name):tokenKind+":"+receiver.name,requestCode,RuntimePendingIntentPolicy.identity(flags),RuntimeBroadcastIntent.encodeFor(tokenKind,logical),hookGeneration);
                prepared=ledger.get(version);
            }
            effectAttempted=true;PendingIntent created=Objects.requireNonNull(create(prepared.kind(),requestCode,physical(prepared,logical),physicalFlags(prepared)),"PENDING_CREATE_NULL");
            synchronized(broker){atomic(()->{current(install);ledger.state(prepared.version,"ACTIVE");finish(operation,"SYSTEM_TOKEN_RETURNED:"+prepared.version);});return token(ledger.get(prepared.version),created);}
        }catch(Exception error){try{failure(operation,error,effectAttempted);}catch(Exception receipt){error.addSuppressed(receipt);}throw error;}
    }
    void cancel(ApkStore.BundleInstall install,PendingIntentLedger.Version value,String operation)throws Exception{
        outsideLock();String digest=ApkStore.textDigest("CANCEL:"+value.version);synchronized(broker){current(install);ledger.requireIdle(install.generation);atomic(()->{begin(operation,install,value.version,"CANCEL",digest);ledger.retire(value.version);});}
        boolean effectAttempted=false;
        try{PendingIntent physical=lookup(value);if(physical!=null){effectAttempted=true;physical.cancel();}synchronized(broker){atomic(()->{ledger.state(value.version,"CANCEL_RETURNED");finish(operation,"SYSTEM_CANCEL_RETURNED");});}}
        catch(Exception error){try{failure(operation,error,effectAttempted);}catch(Exception receipt){error.addSuppressed(receipt);}throw error;}
    }
    PendingIntent lookup(PendingIntentLedger.Version value){
        outsideLock();return create(value.kind(),value.requestCode,physical(value,value.logical()),physicalFlags(value)|PendingIntent.FLAG_NO_CREATE);
    }
    /** The physical token for a kind: a broadcast to the ingress receiver, or a start of the activity/service trampoline. */
    private PendingIntent create(String kind,int requestCode,Intent intent,int flags){
        switch(kind){
            case "activity":return PendingIntent.getActivity(broker.host,requestCode,intent,flags);
            case "service":return PendingIntent.getService(broker.host,requestCode,intent,flags);
            default:return PendingIntent.getBroadcast(broker.host,requestCode,intent,flags);
        }
    }
    private static ApkManifest.Component component(ApkStore.BundleInstall install,Intent intent,String kind,boolean allowDisabled){
        ComponentName component=intent.getComponent();if(component==null)throw new UnsupportedOperationException("PENDING_KIND_COMPONENT_REQUIRED");
        if(!install.info.packageName.equals(component.getPackageName()))throw new SecurityException("PENDING_TARGET_OUTSIDE_INSTALL");
        // A launcher entry is often an <activity-alias>, which is an activity under its own name.
        if(kind.equals("activity"))try{return RuntimeComponents.declared(install,"activity",component.getClassName(),allowDisabled);}catch(IllegalArgumentException notActivity){return RuntimeComponents.declared(install,"activity-alias",component.getClassName(),allowDisabled);}
        return RuntimeComponents.declared(install,kind,component.getClassName(),allowDisabled);
    }
    private PendingIntent absent(ApkStore.BundleInstall install,String key,int requestCode,int flags,Intent logical,String kind){
        // A disjoint URI namespace is queried on Android even when no logical row
        // exists. This never allocates a process, row, or substitute token.
        String probe=kind.equals("activity")?RuntimePendingActivityIngress.class.getName():kind.equals("service")?RuntimePendingServiceIngress.class.getName():RuntimeReceiverSlots.type(0).getName();
        Intent physical=RuntimePendingIntentPayload.physical(broker.host,probe,key,logical);
        physical.setDataAndType(android.net.Uri.parse("tripoder-pending://absent/"+install.generation+"/"+key),physical.getType());
        PendingIntent result=create(kind,requestCode,physical,RuntimePendingIntentPolicy.physical(RuntimePendingIntentPolicy.identity(flags))|PendingIntent.FLAG_NO_CREATE);
        if(result!=null)throw new SecurityException("PENDING_UNOWNED_SYSTEM_TOKEN");return null;
    }
    private Intent physical(PendingIntentLedger.Version value,Intent logical){
        Intent physical=RuntimePendingIntentPayload.physical(broker.host,value.stub(),value.version,logical).setAction(value.routeAction);
        // The guest's own flags travel in the payload; the trampoline itself is started plainly.
        String kind=value.kind();if(kind.equals("activity"))physical.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);else if(kind.equals("service"))physical.setFlags(0);
        return physical;
    }
    private static int physicalFlags(PendingIntentLedger.Version value){return RuntimePendingIntentPolicy.physical(value.flags);}
    PendingIntentLedger.Version owned(ApkStore.BundleInstall install,String version){
        PendingIntentLedger.Version value=ledger.get(version);
        if(!value.space.equals(install.space)||!value.pkg.equals(install.info.packageName)||!value.install.equals(install.generation)||!value.data.equals(install.data.getName()))throw new SecurityException("PENDING_CREATOR_MISMATCH");
        return value;
    }
    private static ApkManifest.Component receiver(ApkStore.BundleInstall install,Intent intent,boolean allowDisabled){
        ComponentName component=intent.getComponent();if(!install.info.packageName.equals(component.getPackageName())||intent.getPackage()!=null&&!install.info.packageName.equals(intent.getPackage()))throw new SecurityException("PENDING_RECEIVER_OUTSIDE_INSTALL");
        return RuntimeComponents.declared(install,"receiver",component.getClassName(),allowDisabled);
    }
    private static Bundle view(PendingIntentLedger.Version value){Bundle result=new Bundle();result.putString("tokenKind",value.kind());result.putString("version",value.version);result.putString("package",value.pkg);result.putInt("flags",value.flags);result.putByteArray("payload",value.payload);result.putBoolean("fenced",value.fenced);return result;}
    private static Bundle token(PendingIntentLedger.Version value,PendingIntent token){Bundle result=view(value);result.putParcelable("token",token);return result;}
    void current(ApkStore.BundleInstall install){var row=broker.apks.row(install.space,install.info.packageName);if(row==null||!"COMMITTED".equals(row.state)||!install.generation.equals(row.generation)||!install.data.getName().equals(row.dataId))throw new SecurityException("PENDING_INSTALL_STALE");}
    private void begin(String operation,ApkStore.BundleInstall install,String version,String kind,String digest){
        if(!broker.contract.accept(operation,"pending-install:"+install.generation,digest).created)throw new IllegalStateException("PENDING_ALREADY_ADMITTED_QUERY_REQUIRED");
        broker.contract.start(operation);ledger.mutation(operation,install.generation,version,kind);
    }
    private void finish(String operation,String result){broker.contract.succeed(operation,result);ledger.close(operation);}
    private void failure(String operation,Exception error,boolean effectAttempted){synchronized(broker){if(broker.contract.query(operation).state==ExecutionContract.OperationState.RUNNING)atomic(()->{
        if(effectAttempted)broker.contract.markUnknown(operation,"PENDING_SYSTEM_OUTCOME_UNCONFIRMED:"+error.getClass().getName());
        else{broker.contract.fail(operation,"PENDING_REJECTED_BEFORE_SYSTEM_MUTATION");ledger.close(operation);}
    });}}
    private void atomic(Runnable action){var db=broker.store.getWritableDatabase();db.beginTransaction();try{action.run();db.setTransactionSuccessful();}finally{db.endTransaction();}}
    private void outsideLock(){if(Thread.holdsLock(broker))throw new IllegalStateException("PENDING_SYSTEM_CALL_INSIDE_BROKER_LOCK");}
}
