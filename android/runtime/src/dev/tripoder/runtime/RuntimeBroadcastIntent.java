package dev.tripoder.runtime;

import android.content.*;
import android.os.*;
import java.security.MessageDigest;
import java.util.*;

/** Only platform Intent/filter parcels and bounded primitive extras cross the authority. */
final class RuntimeBroadcastIntent {
    /** Everything about an Intent except its extras. The authority validates only this: extras may carry the sender's own Parcelables, whose classes exist only in the guest process, and are never interpreted outside it. */
    static void envelope(Intent original){
        if(original==null||original.getClipData()!=null||original.getSelector()!=null||original.getSourceBounds()!=null||original.getIdentifier()!=null||(original.getFlags()&~Intent.FLAG_RECEIVER_FOREGROUND)!=0)throw new IllegalArgumentException("BROADCAST_INTENT_UNSUPPORTED");
        if(original.getAction()!=null&&(original.getAction().length()>512||RuntimeSystemBroadcasts.action(original.getAction())))throw new UnsupportedOperationException("SYSTEM_BROADCAST_POLICY_UNSUPPORTED");
        if(original.getDataString()!=null&&original.getDataString().length()>4096)throw new IllegalArgumentException("BROADCAST_URI_TOO_LONG");
    }
    static byte[] encode(Intent original){
        envelope(original);
        Intent copy=new Intent(original);copy.replaceExtras(RuntimeProviderFacade.primitive(original.getExtras()));
        Parcel p=Parcel.obtain();try{copy.writeToParcel(p,0);if(p.dataSize()>48000)throw new IllegalArgumentException("BROADCAST_INTENT_TOO_LARGE");
            try{return p.marshall();}catch(RuntimeException binder){throw new BinderPayload(binder);}}finally{p.recycle();}
    }
    /** Top-level Binder extras cannot be stored as bytes; they travel beside the payload and are put back on delivery. Returns them (or null) and removes them from the intent. */
    static Bundle detachBinders(Intent intent){
        Bundle extras=intent.getExtras();if(extras==null)return null;Bundle out=null;
        for(String key:new ArrayList<>(extras.keySet())){Object value=extras.get(key);if(value instanceof IBinder){if(out==null)out=new Bundle();out.putBinder(key,(IBinder)value);intent.removeExtra(key);}}
        return out;
    }
    static Intent attachBinders(Intent logical,Bundle reply){Bundle binders=reply==null?null:reply.getBundle("binders");if(binders!=null)logical.putExtras(binders);return logical;}
    /** A broadcast payload is stored as bytes by the authority and a Binder cannot be stored; the sender is told apart from a malformed request. */
    static final class BinderPayload extends RuntimeException{BinderPayload(Throwable cause){super("BROADCAST_BINDER_EXTRA_UNSUPPORTED",cause);}}
    /** The encoding for an activity/service start: any action and any task flags are legitimate there, so only the shapes the platform parcel cannot carry safely are refused. */
    static byte[] encodeStart(Intent original){
        if(original==null||original.getSelector()!=null||original.getSourceBounds()!=null)throw new IllegalArgumentException("START_INTENT_UNSUPPORTED");
        if(original.getAction()!=null&&original.getAction().length()>512)throw new IllegalArgumentException("START_ACTION_TOO_LONG");
        if(original.getDataString()!=null&&original.getDataString().length()>4096)throw new IllegalArgumentException("START_URI_TOO_LONG");
        Intent copy=new Intent(original);copy.replaceExtras(RuntimeProviderFacade.primitive(original.getExtras()));
        Parcel p=Parcel.obtain();try{copy.writeToParcel(p,0);if(p.dataSize()>48000)throw new IllegalArgumentException("START_INTENT_TOO_LARGE");return p.marshall();}finally{p.recycle();}
    }
    static byte[] encodeFor(String kind,Intent original){return kind.equals("broadcast")?encode(original):encodeStart(original);}
    static Intent decode(byte[] bytes){if(bytes==null||bytes.length>48000)throw new SecurityException("BROADCAST_PAYLOAD_INVALID");Parcel p=Parcel.obtain();try{p.unmarshall(bytes,0,bytes.length);p.setDataPosition(0);Intent intent=Intent.CREATOR.createFromParcel(p);RuntimeEngine guest=RuntimeEngine.current;if(guest!=null&&guest.loader!=null)RuntimeGuestPayload.prepare(intent,guest.guestLoader());if(p.dataAvail()!=0)throw new SecurityException("BROADCAST_PAYLOAD_TRAILING_DATA");return intent;}finally{p.recycle();}}
    static String digest(byte[] value)throws Exception{return ArtifactSet.hex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static final Set<String> UNDELIVERED=Collections.synchronizedSet(new HashSet<>());
    static byte[] filter(IntentFilter filter){
        if(filter==null)throw new IllegalArgumentException("RECEIVER_FILTER_REQUIRED");
        // Priority only orders receivers of an ordered broadcast, and ordered delivery is limited to one target (ORDERED_BROADCAST_SINGLE_TARGET_REQUIRED), so accepting it cannot change any delivery. It stays in the parcel.
        if(filter.countActions()>32||filter.countCategories()>32)throw new IllegalArgumentException("RECEIVER_FILTER_UNSUPPORTED:actions="+filter.countActions()+";categories="+filter.countCategories()+";priority="+filter.getPriority());
        // Android accepts actionless filters, including data/categories. They
        // have no action-index entry and cannot receive an implicit broadcast.
        for(int i=0;i<filter.countActions();i++){String action=filter.getAction(i);if(action.length()>512)throw new IllegalArgumentException("RECEIVER_ACTION_TOO_LONG");
            // A registration only declares interest. A platform action with no physical observer here is simply never delivered, as for any app the system does not send it to; failing the registration would kill apps that merely listen.
            if(action.startsWith("android.")&&!RuntimeSystemBroadcasts.ACTIONS.contains(action)&&UNDELIVERED.add(action))android.util.Log.w("TripoderBroadcast","receiver registered for a platform action this runtime does not deliver: "+action);}
        // Exact data/category constraints survive the platform parcel and physical
        // observer filter. They narrow matching and grant no additional visibility.
        Parcel p=Parcel.obtain();try{filter.writeToParcel(p,0);if(p.dataSize()>16000)throw new IllegalArgumentException("RECEIVER_FILTER_TOO_LARGE");return p.marshall();}finally{p.recycle();}
    }
    static IntentFilter filter(byte[] bytes){Parcel p=Parcel.obtain();try{p.unmarshall(bytes,0,bytes.length);p.setDataPosition(0);IntentFilter filter=IntentFilter.CREATOR.createFromParcel(p);if(p.dataAvail()!=0)throw new SecurityException("RECEIVER_FILTER_TRAILING_DATA");filter(filter);return filter;}finally{p.recycle();}}
    static boolean matches(IntentFilter filter,Intent intent){return filter.countActions()>0&&filter.match(intent.getAction(),intent.getType(),intent.getScheme(),intent.getData(),intent.getCategories(),"TripoderRuntime")>=0;}
    static String ticket(Intent physical){
        Bundle extras=physical.getExtras();if(extras==null||extras.size()!=1||!extras.containsKey("tripoder.delivery")||physical.getData()!=null||physical.getType()!=null||physical.getCategories()!=null||physical.getClipData()!=null||physical.getSelector()!=null)throw new SecurityException("BROADCAST_ENVELOPE_INVALID");
        String ticket=RuntimeWire.required(extras,"tripoder.delivery");if(!ticket.matches("[a-f0-9]{64}"))throw new SecurityException("BROADCAST_TICKET_INVALID");return ticket;
    }
    private RuntimeBroadcastIntent(){}
}
