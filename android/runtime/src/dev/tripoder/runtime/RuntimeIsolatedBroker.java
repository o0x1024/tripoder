package dev.tripoder.runtime;

import android.os.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One non-transferable endpoint per immutable isolated generation. */
final class RuntimeIsolatedBroker {
    private final RuntimeBroker broker;
    private final Map<String,Permit> permits=new ConcurrentHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService stops=Executors.newFixedThreadPool(8);
    RuntimeIsolatedBroker(RuntimeBroker broker){this.broker=broker;}
    boolean owns(String generation){return permits.containsKey(generation);}
    /** A durable final-unbind decision closes admission before AMS can kill
     * an unneeded isolated process without delivering onDestroy. This never
     * asserts request success or process death. Caller holds the broker lock. */
    void unbinding(String lease){
        var recorded=broker.services.get(lease);if(recorded.isolatedInstance.isEmpty())return;
        Permit p=permits.get(recorded.generation);if(p==null||p.slot.closing)return;
        if(!p.lease.equals(lease))throw new SecurityException("ISOLATED_UNBIND_LEASE_MISMATCH");
        try(var rows=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM service_calls WHERE lease=? AND kind='bind' AND state='APPLIED' LIMIT 1",new String[]{lease})){if(!rows.moveToFirst())return;}
        try(var rows=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM service_calls b WHERE b.lease=? AND b.kind='bind' AND (b.state='PREPARED' OR (b.state='APPLIED' AND NOT EXISTS (SELECT 1 FROM service_calls u WHERE u.lease=b.lease AND u.source=b.source AND u.connection=b.connection AND u.kind='unbind' AND u.state='PREPARED'))) LIMIT 1",new String[]{lease})){if(rows.moveToFirst())return;}
        p.slot.closing=true;
    }
    void rejectedBeforeBootstrap(String operation,String source){
        synchronized(broker){
            String lease;
            try(var rows=broker.store.getReadableDatabase().rawQuery("SELECT lease FROM service_calls WHERE operation=? AND source=? AND kind='bind' AND state='REJECTED'",new String[]{operation,source})){if(!rows.moveToFirst())return;lease=rows.getString(0);}
            var recorded=broker.services.get(lease);if(recorded.isolatedInstance.isEmpty())return;Permit p=permits.get(recorded.generation);if(p==null||p.attached)return;
            try(var rows=broker.store.getReadableDatabase().rawQuery("SELECT 1 FROM service_calls WHERE lease=? AND kind='bind' AND state IN ('PREPARED','APPLIED') LIMIT 1",new String[]{lease})){if(rows.moveToFirst())return;}
            // Atomically revoke the unconsumed per-instance capability. Even
            // a late Android delivery can construct only the host stub, never
            // this guest generation. This is not a physical death assertion.
            p.slot.closing=true;broker.retireIsolatedBeforeBootstrap(p.generation);broker.services.retireIsolatedDead(lease);permits.remove(p.generation,p);p.slot.stopDispatch.observedDead();
        }
    }
    Bundle reserve(ApkStore.BundleInstall install,String process,String component,String instance)throws Exception{
        return reserve(install,process,component,instance,RuntimePublishedNativeCode.capture(broker.host,install),RuntimeIsolatedLibraries.capture(broker.host,install,null),null);
    }
    Bundle reserve(ApkStore.BundleInstall install,String process,String component,String instance,RuntimePublishedNativeCode.Plan nativeCode,RuntimeIsolatedLibraries.Plan libraries,RuntimeIsolatedNativeService.Plan nativeService)throws Exception{
        RuntimeIsolatedCode.preflight(install);
        if(!install.artifacts.nativeFiles.isEmpty()&&nativeCode==null)throw new SecurityException("ISOLATED_NATIVE_PUBLICATION_REQUIRED");
        if(nativeCode!=null)nativeCode.require(install);
        synchronized(broker){
            var existing=broker.services.find(install.space,install.info.packageName,component,instance);
            if(existing!=null){Permit p=permits.get(existing.generation);if(p==null||p.slot.closing||!existing.install.equals(install.generation)||!java.util.Set.of("RESERVED","CLAIMED","LIVE").contains(existing.state))throw new IllegalStateException("ISOLATED_INSTANCE_CLOSING");if(p.nativeCode==null?nativeCode!=null:!p.nativeCode.same(nativeCode))throw new SecurityException("ISOLATED_NATIVE_PUBLICATION_CHANGED");if(!RuntimeIsolatedLibraries.Plan.same(p.libraries,libraries))throw new SecurityException("ISOLATED_PLATFORM_LIBRARIES_CHANGED");if(!RuntimeIsolatedNativeService.Plan.same(p.nativeService,nativeService))throw new SecurityException("ISOLATED_NATIVE_SERVICE_CHANGED");return p.wire();}
            String id=UUID.randomUUID().toString();var db=broker.store.getWritableDatabase();db.beginTransaction();RuntimeBroker.Slot slot=null;
            try{slot=broker.reserveIsolated(install,process,id);broker.services.insertIsolated(id,slot,component,instance);Permit p=new Permit(id,slot,nativeCode,libraries,nativeService);permits.put(slot.allocation.session.processGeneration,p);db.setTransactionSuccessful();main.postDelayed(()->p.expire(),RuntimeStartupDeadline.remaining(slot.startupDeadlineElapsed,SystemClock.elapsedRealtime()));return p.wire();}
            catch(Exception error){if(slot!=null)broker.dead(slot.allocation.session.processGeneration);throw error;}
            finally{db.endTransaction();}
        }
    }
    ProcessStopDispatch stop(String generation,boolean permanent){
        Permit p=permits.get(generation);if(p==null)throw new IllegalStateException("ISOLATED_SESSION_MISSING");IBinder peer;
        synchronized(broker){
            if(permanent)broker.store.requestClose(generation);if(broker.store.isDead(generation))return null;p.slot.closing=true;
            // The bootstrap capability was never consumed, so this generation has never held guest code or FDs and can no
            // longer receive them. This is the same retirement authority recovery applies; it is not a physical death claim
            // about the host stub, which Android reclaims once nothing binds it.
            if(!p.attached){broker.retireIsolatedBeforeBootstrap(generation);broker.services.retireIsolatedDead(p.lease);permits.remove(generation,p);p.slot.stopDispatch.observedDead();return p.slot.stopDispatch;}
            peer=p.slot.peer;
        }
        // Attached but not yet registered: no guest code runs before REGISTER, which now answers closing and ends the process.
        // Only the real Binder death of its bootstrap peer completes this dispatch; a hung process stays UNCONFIRMED.
        if(peer==null)return p.slot.stopDispatch;
        p.slot.stopDispatch.request(stops,()->{try{Bundle request=new Bundle();request.putString("generation",generation);RuntimeWire.call(peer,RuntimeWire.STOP,request);}catch(Exception error){android.util.Log.w("TripoderIsolated","STOP_UNCONFIRMED",error);}});return p.slot.stopDispatch;
    }
    private final class Permit extends RuntimeWire.Endpoint {
        final String lease,generation;final RuntimeBroker.Slot slot;final RuntimePublishedNativeCode.Plan nativeCode;final RuntimeIsolatedLibraries.Plan libraries;final RuntimeIsolatedNativeService.Plan nativeService;private boolean attached,registered;private IBinder pendingPeer;private int pid,uid;
        Permit(String lease,RuntimeBroker.Slot slot,RuntimePublishedNativeCode.Plan nativeCode,RuntimeIsolatedLibraries.Plan libraries,RuntimeIsolatedNativeService.Plan nativeService){this.lease=lease;this.slot=slot;this.nativeCode=nativeCode;this.libraries=libraries;this.nativeService=nativeService;generation=slot.allocation.session.processGeneration;}
        Bundle wire(){Bundle out=broker.services.get(lease).wire();out.putBoolean("isolated",true);out.putString("isolatedPhysicalInstance","i_"+lease.replace("-",""));out.putBinder("isolatedAuthority",this);return out;}
        void expire(){boolean stop=false;synchronized(broker){var state=broker.contract.getSession(generation).state;if(state!=ExecutionContract.ProcessState.RUNNING&&state!=ExecutionContract.ProcessState.DEAD){slot.closing=true;stop=registered;}}if(stop)stop(generation,false);}
        @Override protected Bundle handle(int code,Bundle input,int callerPid,int callerUid)throws Exception{
            if(!generation.equals(input.getString("generation"))||!lease.equals(input.getString("lease")))throw new SecurityException("ISOLATED_CAPABILITY_SCOPE_MISMATCH");
            if(code==RuntimeWire.ISOLATED_BOOTSTRAP){
                synchronized(broker){
                    if(attached||slot.closing||RuntimeStartupDeadline.remaining(slot.startupDeadlineElapsed,SystemClock.elapsedRealtime())==0)throw new SecurityException("ISOLATED_BOOTSTRAP_RETIRED");
                    if(!android.os.Process.isIsolatedUid(callerUid)||callerUid/100000!=android.os.Process.myUid()/100000)throw new SecurityException("ISOLATED_KERNEL_UID_REQUIRED");
                    IBinder peer=input.getBinder("peer");if(peer==null)throw new SecurityException("ISOLATED_CONTROL_PEER_REQUIRED");
                    var current=broker.apks.get(slot.install.space,slot.install.info.packageName);if(!current.generation.equals(slot.install.generation)||!current.data.equals(slot.install.data))throw new SecurityException("ISOLATED_INSTALL_STALE");
                    var session=broker.contract.attach(slot.allocation.bootstrapToken,callerPid,callerUid);broker.store.session(session);broker.services.claimIsolated(lease,generation);
                    pid=callerPid;uid=callerUid;attached=true;pendingPeer=peer;
                    try{peer.linkToDeath(()->main.post(this::dead),0);}catch(RemoteException error){main.post(this::dead);throw error;}
                }
                if(nativeCode!=null)nativeCode.recheck(broker.host,slot.install);RuntimeIsolatedLibraries.Plan.recheck(libraries,broker.host,slot.install);
                Bundle out=RuntimeIsolatedCode.publish(slot.install,android.os.Process.myUid(),nativeCode,libraries,nativeService);out.putString("generation",generation);out.putString("lease",lease);out.putString("component",broker.services.get(lease).component);out.putString("processName",slot.allocation.session.identity.processName);out.putInt("authorityPid",android.os.Process.myPid());out.putInt("ownerUid",android.os.Process.myUid());return out;
            }
            synchronized(broker){
                if(!attached||pid!=callerPid||uid!=callerUid)throw new SecurityException("ISOLATED_CALLER_MISMATCH");
                broker.contract.authenticate(generation,callerPid,callerUid);
                if(code==RuntimeWire.ISOLATED_REGISTER){
                    if(registered)throw new SecurityException("ISOLATED_CONTROL_ALREADY_REGISTERED");registered=true;slot.peer=pendingPeer;
                    Bundle out=new Bundle();out.putBoolean("closing",slot.closing);if(slot.closing)stop(generation,false);return out;
                }
                if(!registered)throw new SecurityException("ISOLATED_CONTROL_NOT_REGISTERED");
                if(code!=RuntimeWire.ISOLATED_EVENT)throw new SecurityException("ISOLATED_CAPABILITY_OPERATION_DENIED");
                String event=RuntimeWire.required(input,"event");if(!java.util.Set.of("onCreate","onBind","onUnbind","onRebind","onDestroy").contains(event))throw new SecurityException("ISOLATED_EVENT_UNSUPPORTED");
                if(!event.equals("onDestroy")&&!event.equals("onUnbind")&&slot.closing)throw new SecurityException("ISOLATED_PROCESS_CLOSING");
                broker.services.lifecycle(lease,generation,lease,event);if(event.equals("onCreate"))broker.store.session(broker.contract.markRunning(generation,pid,uid));return new Bundle();
            }
        }
        void dead(){synchronized(broker){boolean closing=slot.closing;if(!broker.store.isDead(generation))broker.isolatedDead(generation,!closing&&!broker.services.get(lease).state.equals("DORMANT"));slot.closing=true;broker.services.retireIsolatedDead(lease);permits.remove(generation,this);}slot.stopDispatch.observedDead();}
    }
}
