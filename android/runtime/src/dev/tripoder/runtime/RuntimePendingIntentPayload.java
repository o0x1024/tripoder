package dev.tripoder.runtime;

import android.content.*;
import android.net.Uri;
import android.os.*;
import java.util.*;

/** Broadcast tokens. Routing credentials are never stored in mutable extras. */
final class RuntimePendingIntentPayload {
    static final String ACTION="dev.tripoder.runtime.PENDING_BROADCAST";
    private static final String SCHEME="tripoder-pending";
    static Intent logical(Intent value){return logical(value,"broadcast");}
    static Intent logical(Intent value,String kind){
        Objects.requireNonNull(value,"PENDING_INTENT_REQUIRED");
        if(value.getComponent()==null&&value.getAction()==null)throw new UnsupportedOperationException("PENDING_BROADCAST_DESTINATION_REQUIRED");
        // Reuse the same bounded broadcast transport and reject unsupported shapes
        // before reserving a component or touching a system token.
        return RuntimeBroadcastIntent.decode(RuntimeBroadcastIntent.encodeFor(kind,value));
    }
    static String identity(String install,String data,int requestCode,int flags,Intent logical)throws Exception{
        // Intent.filterEquals ignores extras, flags and category insertion order.
        // Parcel bytes are not a canonical representation of that identity.
        StringBuilder key=new StringBuilder("pending-broadcast-filter-v1:");
        for(String value:new String[]{install,data,Integer.toString(requestCode),Integer.toString(RuntimePendingIntentPolicy.identity(flags)),logical.getAction(),logical.getDataString(),logical.getType(),logical.getIdentifier(),logical.getPackage(),logical.getComponent()==null?null:logical.getComponent().flattenToString()})part(key,value);
        var categories=logical.getCategories();key.append(categories==null?-1:categories.size()).append(':');
        if(categories!=null)for(String value:new TreeSet<>(categories))part(key,value);
        return ApkStore.textDigest(key.toString());
    }
    private static void part(StringBuilder key,String value){if(value==null)key.append("-1:");else key.append(value.length()).append(':').append(value);}
    static Intent physical(Context host,String stub,String version,Intent logical){
        requireVersion(version);
        return new Intent(ACTION+"."+version).setComponent(new ComponentName(host,stub)).setPackage(host.getPackageName())
            .setDataAndType(Uri.parse(SCHEME+"://version/"+version),"application/x-tripoder-pending")
            .setIdentifier(version).addCategory("dev.tripoder.runtime.PENDING_TOKEN")
            .setFlags(logical.getFlags()).replaceExtras(RuntimeProviderFacade.primitive(logical.getExtras()));
    }
    static String version(Context host,Intent physical){
        if(physical==null||physical.getAction()==null||!physical.getAction().startsWith(ACTION+"."))return null;
        String version=physical.getIdentifier();requireVersion(version);
        if(!(ACTION+"."+version).equals(physical.getAction())||!host.getPackageName().equals(physical.getPackage())||physical.getComponent()==null||
            !host.getPackageName().equals(physical.getComponent().getPackageName())||!(RuntimeReceiverSlots.isStub(physical.getComponent().getClassName())||RuntimePendingIntentIngress.class.getName().equals(physical.getComponent().getClassName())||RuntimePendingActivityIngress.class.getName().equals(physical.getComponent().getClassName())||RuntimePendingServiceIngress.class.getName().equals(physical.getComponent().getClassName()))||
            !Uri.parse(SCHEME+"://version/"+version).equals(physical.getData())||!"application/x-tripoder-pending".equals(physical.getType())||
            !Set.of("dev.tripoder.runtime.PENDING_TOKEN").equals(physical.getCategories())||physical.getSelector()!=null||physical.getClipData()!=null)
            throw new SecurityException("PENDING_PHYSICAL_ROUTE_CHANGED");
        return version;
    }
    static String taggedVersion(String tag){String prefix="tripoder-owner:"+ACTION+".";if(tag==null||!tag.startsWith(prefix))return null;String version=tag.substring(prefix.length());requireVersion(version);return version;}
    static Intent fillIn(Intent value){
        if(value==null)return null;
        // The admitted broadcast capability has a fixed explicit destination.
        // Unsupported routing fill-ins fail before system send, never disappear.
        Intent empty=new Intent();
        if(!value.filterEquals(empty)||value.getFlags()!=0||value.getClipData()!=null||value.getSelector()!=null||value.getSourceBounds()!=null)
            throw new UnsupportedOperationException("PENDING_FILL_IN_ROUTE_UNSUPPORTED");
        return new Intent().replaceExtras(RuntimeProviderFacade.primitive(value.getExtras()));
    }
    static Intent delivered(Intent saved,Intent physical){return delivered(saved,physical,"broadcast");}
    static Intent delivered(Intent saved,Intent physical,String kind){
        Intent logical=new Intent(saved);logical.replaceExtras(RuntimeProviderFacade.primitive(physical.getExtras()));
        // AlarmManager adds EXTRA_ALARM_COUNT to mutable tokens. Preserve this real
        // platform extra while retaining the logical filter and receiver identity.
        return logical(logical,kind);
    }
    private static void requireVersion(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new SecurityException("PENDING_VERSION_INVALID");}
    private RuntimePendingIntentPayload(){}
}
