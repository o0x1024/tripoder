package dev.tripoder.runtime;

import android.content.*;
import android.os.*;
import java.security.MessageDigest;
import java.util.*;

/** Only platform Intent/filter parcels and bounded primitive extras cross the authority. */
final class RuntimeBroadcastIntent {
    /** Everything about an Intent except its extras. The authority validates only this: extras may carry the sender's own Parcelables, whose classes exist only in the guest process, and are never interpreted outside it. */
    /** Broadcast delivery hints. The authority routes by content, so a guest's flags are inert here; an intent shape must never kill the caller, because the platform never throws for one. */
    private static final int BROADCAST_FLAGS=Intent.FLAG_RECEIVER_FOREGROUND|Intent.FLAG_RECEIVER_REGISTERED_ONLY|Intent.FLAG_RECEIVER_NO_ABORT|Intent.FLAG_RECEIVER_REPLACE_PENDING|Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS|Intent.FLAG_INCLUDE_STOPPED_PACKAGES|Intent.FLAG_EXCLUDE_STOPPED_PACKAGES;
    /** Reduce a broadcast to the shapes the authority can carry instead of refusing it: guest receivers see exactly the reduced intent. */
    static Intent sanitize(Intent original){
        if(original==null)return null;
        Intent copy=new Intent(original);
        if(copy.getClipData()!=null)copy.setClipData(null);
        if(copy.getSelector()!=null)copy.setSelector(null);
        copy.setSourceBounds(null);copy.setIdentifier(null);
        copy.setFlags(copy.getFlags()&BROADCAST_FLAGS);
        return copy;
    }
    static void envelope(Intent original){
        if(original==null||original.getClipData()!=null||original.getSelector()!=null||original.getSourceBounds()!=null||original.getIdentifier()!=null||(original.getFlags()&~BROADCAST_FLAGS)!=0)throw new IllegalArgumentException("BROADCAST_INTENT_UNSUPPORTED");
        if(RuntimeEngine.current!=null)RuntimeSystemBroadcasts.discover(original.getAction());
        if(original.getAction()!=null&&(original.getAction().length()>512||RuntimeSystemBroadcasts.action(original.getAction())))throw new UnsupportedOperationException("SYSTEM_BROADCAST_POLICY_UNSUPPORTED");
        if(original.getDataString()!=null&&original.getDataString().length()>4096)throw new IllegalArgumentException("BROADCAST_URI_TOO_LONG");
    }
    static byte[] encode(Intent original){
        Intent reduced=sanitize(original);envelope(reduced);
        Intent copy=new Intent(reduced);copy.replaceExtras(RuntimeProviderFacade.primitive(reduced.getExtras()));
        Parcel p=Parcel.obtain();try{copy.writeToParcel(p,0);if(p.dataSize()>48000)throw new IllegalArgumentException("BROADCAST_INTENT_TOO_LARGE");
            try{return p.marshall();}catch(RuntimeException binder){throw new BinderPayload(binder);}}finally{p.recycle();}
    }
    /** Top-level Binder extras cannot be stored as bytes; they travel beside the payload and are put back on delivery. Returns them (or null) and removes them from the intent. */
    static Bundle detachBinders(Intent intent){
        Bundle extras=intent.getExtras();if(extras==null)return null;Bundle out=null;
        for(String key:new ArrayList<>(extras.keySet())){
            Object value=extras.get(key);
            if(value instanceof IBinder){if(out==null)out=new Bundle();out.putBinder(key,(IBinder)value);intent.removeExtra(key);}
            else if(value instanceof Messenger){if(out==null)out=new Bundle();out.putParcelable(key,(Messenger)value);intent.removeExtra(key);}
        }
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
    static byte[] filter(IntentFilter filter){
        if(filter==null)throw new IllegalArgumentException("RECEIVER_FILTER_REQUIRED");
        // Keep priority in the parcel; the authority orders the resolved direct
        // delivery chain before any receiver executes.
        if(filter.countActions()>32||filter.countCategories()>32)throw new IllegalArgumentException("RECEIVER_FILTER_UNSUPPORTED:actions="+filter.countActions()+";categories="+filter.countCategories()+";priority="+filter.getPriority());
        // Android accepts actionless filters, including data/categories. They
        // have no action-index entry; MIME/scheme indexes may still select them.
        for(int i=0;i<filter.countActions();i++)if(filter.getAction(i).length()>512)throw new IllegalArgumentException("RECEIVER_ACTION_TOO_LONG");
        // Exact data/category constraints survive the platform parcel and physical
        // observer filter. They narrow matching and grant no additional visibility.
        Parcel p=Parcel.obtain();try{filter.writeToParcel(p,0);if(p.dataSize()>16000)throw new IllegalArgumentException("RECEIVER_FILTER_TOO_LARGE");return p.marshall();}finally{p.recycle();}
    }
    static IntentFilter filter(byte[] bytes){Parcel p=Parcel.obtain();try{p.unmarshall(bytes,0,bytes.length);p.setDataPosition(0);IntentFilter filter=IntentFilter.CREATOR.createFromParcel(p);if(p.dataAvail()!=0)throw new SecurityException("RECEIVER_FILTER_TRAILING_DATA");filter(filter);return filter;}finally{p.recycle();}}
    static boolean matches(IntentFilter filter,Intent intent){return filter.countActions()>0&&filter.match(intent.getAction(),intent.getType(),intent.getScheme(),intent.getData(),intent.getCategories(),"TripoderRuntime")>=0;}
    /** RegisteredReceiverResolver first selects action/type/scheme index candidates.
     * IntentFilter.match alone accepts a null action and is not a resolver.
     * Explicit manifest component delivery has a separate route and skips this method.
     */
    static boolean matchesRegistered(IntentFilter filter,Intent intent){
        String action=intent.getAction(),type=intent.getType(),scheme=intent.getScheme();
        boolean candidate=scheme!=null&&filter.hasDataScheme(scheme);
        if(type!=null){
            int slash=type.indexOf('/');
            if(slash>0&&filter.countDataTypes()>0)
                candidate|=!type.substring(0,slash).equals("*")||(action!=null&&filter.hasAction(action));
        }else if(scheme==null&&action!=null){
            candidate|=filter.countDataTypes()==0&&filter.countDataSchemes()==0&&filter.hasAction(action);
        }
        return candidate&&filter.match(action,type,scheme,intent.getData(),intent.getCategories(),"TripoderRuntime")>=0;
    }
    static String ticket(Intent physical){
        Bundle extras=physical.getExtras();if(extras==null||extras.size()!=1||!extras.containsKey("tripoder.delivery")||physical.getData()!=null||physical.getType()!=null||physical.getCategories()!=null||physical.getClipData()!=null||physical.getSelector()!=null)throw new SecurityException("BROADCAST_ENVELOPE_INVALID");
        String ticket=RuntimeWire.required(extras,"tripoder.delivery");if(!ticket.matches("[a-f0-9]{64}"))throw new SecurityException("BROADCAST_TICKET_INVALID");return ticket;
    }
    private RuntimeBroadcastIntent(){}
}
