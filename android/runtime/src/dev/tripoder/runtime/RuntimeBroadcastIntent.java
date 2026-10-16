package dev.tripoder.runtime;

import android.content.*;
import android.os.*;
import java.security.MessageDigest;
import java.util.*;

/** Only platform Intent/filter parcels and bounded primitive extras cross the authority. */
final class RuntimeBroadcastIntent {
    static byte[] encode(Intent original){
        if(original==null||original.getClipData()!=null||original.getSelector()!=null||original.getSourceBounds()!=null||original.getIdentifier()!=null||(original.getFlags()&~Intent.FLAG_RECEIVER_FOREGROUND)!=0)throw new IllegalArgumentException("BROADCAST_INTENT_UNSUPPORTED");
        Intent copy=new Intent(original);copy.replaceExtras(RuntimeProviderFacade.primitive(original.getExtras()));
        if(copy.getAction()!=null&&(copy.getAction().length()>512||copy.getAction().startsWith("android.")))throw new UnsupportedOperationException("SYSTEM_BROADCAST_POLICY_UNSUPPORTED");
        if(copy.getDataString()!=null&&copy.getDataString().length()>4096)throw new IllegalArgumentException("BROADCAST_URI_TOO_LONG");
        Parcel p=Parcel.obtain();try{copy.writeToParcel(p,0);if(p.dataSize()>48000)throw new IllegalArgumentException("BROADCAST_INTENT_TOO_LARGE");return p.marshall();}finally{p.recycle();}
    }
    static Intent decode(byte[] bytes){if(bytes==null||bytes.length>48000)throw new SecurityException("BROADCAST_PAYLOAD_INVALID");Parcel p=Parcel.obtain();try{p.unmarshall(bytes,0,bytes.length);p.setDataPosition(0);Intent intent=Intent.CREATOR.createFromParcel(p);if(p.dataAvail()!=0)throw new SecurityException("BROADCAST_PAYLOAD_TRAILING_DATA");return intent;}finally{p.recycle();}}
    static String digest(byte[] value)throws Exception{return ArtifactSet.hex(MessageDigest.getInstance("SHA-256").digest(value));}
    static byte[] filter(IntentFilter filter){
        if(filter==null)throw new IllegalArgumentException("RECEIVER_FILTER_REQUIRED");
        if(filter.countActions()>32||filter.countCategories()>32||filter.getPriority()!=0)throw new IllegalArgumentException("RECEIVER_FILTER_UNSUPPORTED:actions="+filter.countActions()+";categories="+filter.countCategories()+";priority="+filter.getPriority());
        if(filter.countActions()==0&&(filter.countCategories()!=0||filter.countDataSchemes()!=0||filter.countDataTypes()!=0||filter.countDataAuthorities()!=0||filter.countDataPaths()!=0))throw new UnsupportedOperationException("ACTIONLESS_DATA_FILTER_UNSUPPORTED");
        for(int i=0;i<filter.countActions();i++){String action=filter.getAction(i);if(action.length()>512||action.startsWith("android.")&&!RuntimeSystemBroadcasts.ACTIONS.contains(action))throw new UnsupportedOperationException("SYSTEM_BROADCAST_POLICY_UNSUPPORTED:"+action.substring(0,Math.min(action.length(),512)));}
        RuntimeSystemBroadcasts.validate(filter);
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
