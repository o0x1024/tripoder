package dev.tripoder.runtime;

import android.app.ActivityManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Android's synchronous cold-component entry. No reverse bind/LOAD while the caller waits. */
public final class RuntimeBootstrapProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle request){try{
        try{RuntimeRecoveryGate.require();}catch(IllegalStateException blocked){Bundle rejected=new Bundle();String key="restoreActivity".equals(method)?"rejectedActivity":"restoreService".equals(method)?"rejectedService":"restoreJob".equals(method)?"rejectedJob":"rejectedReceiver";rejected.putString(key,"ENGINE_RECOVERING_OR_FENCED");return rejected;}
        int pid=Binder.getCallingPid(),uid=Binder.getCallingUid();
        if("restoreJob".equals(method)){if(arg!=null)throw new SecurityException("JOB_BOOTSTRAP_SHAPE");return RuntimeJobBootstrap.call(getContext(),request,pid,uid);}
        boolean activity="restoreActivity".equals(method),service="restoreService".equals(method),receiver="restoreReceiver".equals(method);
        if((!activity&&!service&&!receiver)||uid!=android.os.Process.myUid())throw new SecurityException("BOOTSTRAP_CALLER_REJECTED");
        if(arg!=null||request==null||request.hasFileDescriptors())throw new SecurityException("BOOTSTRAP_SHAPE_UNSUPPORTED");
        var keys=service?java.util.Set.of("stub","peer"):java.util.Set.of("stub","peer","ticket");
        if(!keys.equals(request.keySet())||!(request.get("stub") instanceof String)||!(request.get("peer") instanceof IBinder)||(!service&&!(request.get("ticket") instanceof String)))throw new SecurityException("BOOTSTRAP_SHAPE_UNSUPPORTED");
        String stub=RuntimeWire.required(request,"stub");int index=activity?RuntimeActivitySlots.process(stub):receiver?RuntimeReceiverSlots.index(stub):RuntimeServices.processIndex(stub);
        if(index<0)throw new SecurityException("BOOTSTRAP_STUB_INVALID");
        String physical=getContext().getPackageName()+":slot"+index;boolean observed=false;
        var processes=((ActivityManager)getContext().getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
        if(processes!=null)for(var process:processes)if(process.pid==pid&&process.uid==uid&&physical.equals(process.processName))observed=true;
        if(!observed)throw new SecurityException("BOOTSTRAP_PHYSICAL_PROCESS_MISMATCH");
        IBinder peer=request.getBinder("peer");if(peer==null)throw new SecurityException("BOOTSTRAP_PEER_REQUIRED");
        RuntimeBroker broker=RuntimeAuthorityOwner.get(getContext());
        synchronized(broker){
            Bundle bootstrap;
            if(receiver){
                if(broker.broadcasts.rejectRetiredEntry(RuntimeWire.required(request,"ticket"),index)){Bundle rejected=new Bundle();rejected.putString("rejectedReceiver","BROADCAST_RESOURCE_RETIRED");return rejected;}
                Bundle delivery=broker.broadcasts.get(RuntimeWire.required(request,"ticket"));String target=delivery.getString("target");
                if(broker.store.recoveryPaused(target)){Bundle rejected=new Bundle();rejected.putString("rejectedReceiver","RECOVERY_TARGET_PAUSED_EXPLICIT_START_REQUIRED");return rejected;}
                if(broker.store.closeRequested(target)){Bundle rejected=new Bundle();rejected.putString("rejectedReceiver","PROCESS_CLOSING");return rejected;}
                var slot=broker.requiredSlot(target);
                if(!delivery.getString("state").equals("PREPARED")||!slot.activityStub.equals(RuntimeSlot.activity(index).getName())||delivery.getString("component").isEmpty())throw new SecurityException("BROADCAST_BOOTSTRAP_MISMATCH");
                bootstrap=broker.processes.claimComponent(slot,peer);
            }else if(activity){
                var ticket=broker.activities.get(RuntimeWire.required(request,"ticket"));
                if(!ticket.stub.equals(stub))throw new SecurityException("BOOTSTRAP_STUB_MISMATCH");
                if(!ticket.processStub.equals(RuntimeSlot.activity(index).getName()))throw new SecurityException("BOOTSTRAP_PROCESS_SLOT_MISMATCH");
                if(broker.activities.closedEntry(ticket.id,stub)){ActivityEntryFences.rejected(broker,ticket.id,"factory");Bundle rejected=new Bundle();rejected.putString("rejectedActivity",ActivityEntryFences.REJECTED);return rejected;}
                var existing=broker.findProcess(ticket.space,ticket.pkg,ticket.process);
                if(existing!=null&&ticket.generation.equals(existing.allocation.session.processGeneration)){
                    if(!existing.install.generation.equals(ticket.install)||!existing.activityStub.equals(ticket.processStub))throw new SecurityException("BOOTSTRAP_RESERVATION_MISMATCH");
                    bootstrap=broker.processes.claimComponent(existing,peer);bootstrap.putInt("authorityPid",android.os.Process.myPid());return bootstrap;
                }
                if(broker.store.recoveryPaused(ticket.generation)){Bundle rejected=new Bundle();rejected.putString("rejectedActivity","RECOVERY_TARGET_PAUSED_EXPLICIT_START_REQUIRED");return rejected;}
                if(broker.store.hookRecoveryBlocked(ticket.generation)){Bundle rejected=new Bundle();rejected.putString("rejectedActivity","HOOK_COMPONENT_RECOVERY_REQUIRES_EXPLICIT_START");return rejected;}
                var saved=broker.activities.restore(RuntimeWire.required(request,"ticket"),stub);var install=broker.apks.get(saved.space,saved.pkg);
                if(!saved.install.equals(install.generation))throw new SecurityException("BOOTSTRAP_INSTALL_STALE");
                var slot=broker.findProcess(saved.space,saved.pkg,saved.process);if(slot==null)slot=broker.reserve(install,saved.process,saved.processStub);if(!slot.activityStub.equals(saved.processStub)||!slot.install.generation.equals(install.generation))throw new SecurityException("BOOTSTRAP_RESERVATION_MISMATCH");bootstrap=broker.processes.claimComponent(slot,peer);broker.activities.rebind(saved,slot);
            }else{
                var pending=broker.services.forStub(stub);var existing=broker.findProcess(pending.space,pending.pkg,pending.process);
                if(broker.store.recoveryPaused(pending.generation)){Bundle rejected=new Bundle();rejected.putString("rejectedService","RECOVERY_TARGET_PAUSED_EXPLICIT_START_REQUIRED");return rejected;}
                if(existing!=null&&pending.generation.equals(existing.allocation.session.processGeneration)){
                    if(!existing.install.generation.equals(pending.install)||!existing.activityStub.equals(pending.processStub))throw new SecurityException("BOOTSTRAP_RESERVATION_MISMATCH");
                    bootstrap=broker.processes.claimComponent(existing,peer);bootstrap.putInt("authorityPid",android.os.Process.myPid());return bootstrap;
                }
                var saved=broker.services.restore(stub);var install=broker.apks.get(saved.space,saved.pkg);
                if(!saved.install.equals(install.generation))throw new SecurityException("BOOTSTRAP_INSTALL_STALE");
                var slot=broker.findProcess(saved.space,saved.pkg,saved.process);if(slot==null)slot=broker.reserve(install,saved.process,saved.processStub);if(!slot.activityStub.equals(saved.processStub)||!slot.install.generation.equals(install.generation))throw new SecurityException("BOOTSTRAP_RESERVATION_MISMATCH");bootstrap=broker.processes.claimComponent(slot,peer);broker.services.rebind(saved,slot);
            }
            bootstrap.putInt("authorityPid",android.os.Process.myPid());return bootstrap;
        }
    }catch(Exception error){throw new IllegalStateException("COMPONENT_BOOTSTRAP_FAILED:"+error.getMessage(),error);}}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw unsupported();}
    @Override public String getType(Uri u){throw unsupported();}
    @Override public Uri insert(Uri u,ContentValues v){throw unsupported();}
    @Override public int delete(Uri u,String s,String[] a){throw unsupported();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw unsupported();}
    private static UnsupportedOperationException unsupported(){return new UnsupportedOperationException("BOOTSTRAP_METHOD_UNSUPPORTED");}
}
