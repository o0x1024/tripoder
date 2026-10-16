package dev.tripoder.runtime;

import android.app.*;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stable data incarnation is retained on upgrade and replaced by a fresh install. */
final class RuntimeNotificationNamespace {
    static final String ROOT="tripoder.channels.v1.";
    private final String prefix;
    RuntimeNotificationNamespace(String dataId){prefix=ROOT+incarnation(dataId)+".";}
    static String incarnation(String value){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("NOTIFICATION_INCARCATION_INVALID");return value;}
    String physical(String id){
        if(id==null||id.isEmpty())throw new IllegalArgumentException("NOTIFICATION_ID_REQUIRED");
        byte[] bytes=id.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>512||!id.equals(new String(bytes,StandardCharsets.UTF_8)))throw new IllegalArgumentException("NOTIFICATION_ID_UNSUPPORTED");
        return prefix+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    boolean owns(String id){return id!=null&&id.startsWith(prefix);}
    String logical(String id){
        if(!owns(id))throw new SecurityException("NOTIFICATION_CHANNEL_SCOPE_MISMATCH");
        String logical=new String(Base64.getUrlDecoder().decode(id.substring(prefix.length())),StandardCharsets.UTF_8);
        if(!physical(logical).equals(id))throw new SecurityException("NOTIFICATION_CHANNEL_ENCODING_INVALID");return logical;
    }
    private static String scope(String id){
        if(id==null||!id.startsWith(ROOT))return null;
        int end=id.indexOf('.',ROOT.length());if(end<0)throw new IllegalStateException("NOTIFICATION_RESOURCE_SCOPE_CORRUPT");
        return incarnation(id.substring(ROOT.length(),end));
    }
    /** Orphan channels are durable system resources; absence of catalog ownership permits GC. */
    static void recover(Context host,Set<String> retained){
        NotificationManager manager=Objects.requireNonNull(host.getSystemService(NotificationManager.class));
        for(NotificationChannelGroup group:manager.getNotificationChannelGroups()){String owner=scope(group.getId());if(owner!=null&&!retained.contains(owner))manager.deleteNotificationChannelGroup(group.getId());}
        for(NotificationChannel channel:manager.getNotificationChannels()){String owner=scope(channel.getId());if(owner!=null&&!retained.contains(owner))manager.deleteNotificationChannel(channel.getId());}
        for(NotificationChannelGroup group:manager.getNotificationChannelGroups()){String owner=scope(group.getId());if(owner!=null&&!retained.contains(owner))throw new IllegalStateException("NOTIFICATION_GROUP_GC_UNCONFIRMED");}
        for(NotificationChannel channel:manager.getNotificationChannels()){String owner=scope(channel.getId());if(owner!=null&&!retained.contains(owner))throw new IllegalStateException("NOTIFICATION_CHANNEL_GC_UNCONFIRMED");}
    }
}
