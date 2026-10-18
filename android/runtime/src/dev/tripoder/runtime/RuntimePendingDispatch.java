package dev.tripoder.runtime;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** What a fired activity/service PendingIntent does. The system started an authority-side trampoline; this makes the guest application start the component itself, from its own Context. */
final class RuntimePendingDispatch {
    private RuntimePendingDispatch(){}
    /** Validates that the fired token is still live and hands its (fill-in merged) logical intent to the guest process. Quiet for a retired token, as for a retired broadcast token. */
    static void run(Context host,Intent physical,String kind)throws Exception{
        String version=RuntimePendingIntentPayload.version(host,physical);
        if(version==null)throw new SecurityException("PENDING_DISPATCH_COMPONENT");
        RuntimeBroker broker=RuntimeAuthorityOwner.get(host);PendingIntentLedger.Version value;byte[] payload;
        synchronized(broker){
            try{value=broker.pendingIntents.get(version);}catch(SecurityException unknown){android.util.Log.i("TripoderReceiver","PENDING_VERSION_RETIRED:"+version);return;}
            var current=broker.apks.row(value.space,value.pkg);
            if(value.fenced||!value.current||!kind.equals(value.kind())||current==null||!"COMMITTED".equals(current.state)||!value.install.equals(current.generation)||!value.data.equals(current.dataId)){
                android.util.Log.i("TripoderReceiver","PENDING_VERSION_RETIRED:"+version);return;
            }
            if((value.flags&RuntimePendingIntentPolicy.ONE_SHOT)!=0)broker.pendingIntents.consume(version);
            payload=RuntimeBroadcastIntent.encodeStart(RuntimePendingIntentPayload.delivered(value.logical(),physical,kind));
        }
        var install=broker.apks.get(value.space,value.pkg);
        ApkManifest.Component declared;
        try{declared=RuntimeComponents.declared(install,kind,value.component());}catch(IllegalArgumentException notThatType){declared=RuntimeComponents.declared(install,"activity-alias",value.component());}
        // Waits for the guest Application (cold start included); the trampoline stays alive meanwhile.
        var slot=broker.processes.ensure(value.space,value.pkg,declared.process);
        Bundle request=new Bundle();request.putString("kind",kind);request.putByteArray("payload",payload);
        RuntimeWire.call(slot.peer,RuntimeWire.DISPATCH_PENDING,request);
        android.util.Log.i("TripoderReceiver","PENDING_DISPATCHED:kind="+kind+":version="+version+":component="+value.component());
    }
}
