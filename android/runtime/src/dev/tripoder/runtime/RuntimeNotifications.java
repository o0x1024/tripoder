package dev.tripoder.runtime;

import android.app.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.util.*;

/** Real system channel/group operations scoped to one logical data incarnation. */
final class RuntimeNotifications {
    private final RuntimeEngine engine;
    private volatile NotificationManager facade;
    RuntimeNotifications(RuntimeEngine engine){this.engine=engine;}
    synchronized NotificationManager facade(){if(facade==null){facade=RuntimeBridge.createNotificationManager(engine.host);if(facade==null||facade==platform())throw new SecurityException("NOTIFICATION_FACADE_IDENTITY_INVALID");}return facade;}
    boolean owns(NotificationManager manager){return manager!=null&&manager==facade;}
    NotificationManager platform(){return Objects.requireNonNull(engine.host.getSystemService(NotificationManager.class),"PLATFORM_NOTIFICATION_UNAVAILABLE");}
    private RuntimeNotificationNamespace scope(){return new RuntimeNotificationNamespace(engine.storage.dataId);}
    private static <T extends Parcelable>T copy(T value,Parcelable.Creator<T> creator){Parcel p=Parcel.obtain();try{value.writeToParcel(p,0);if(p.hasFileDescriptors()||p.dataSize()>32768)throw new IllegalArgumentException("NOTIFICATION_PAYLOAD_UNSUPPORTED");p.setDataPosition(0);return creator.createFromParcel(p);}finally{p.recycle();}}
    private static void text(CharSequence value){if(value!=null&&(!(value instanceof String)||value.length()>1000))throw new IllegalArgumentException("NOTIFICATION_TEXT_UNSUPPORTED");}
    private static void channelKind(NotificationChannel value){
        if(value.getParentChannelId()!=null||value.getConversationId()!=null)throw new UnsupportedOperationException("NOTIFICATION_CONVERSATION_UNSUPPORTED");
    }
    private NotificationChannel toSystem(NotificationChannel value){
        Objects.requireNonNull(value);channelKind(value);text(value.getName());text(value.getDescription());
        Uri sound=value.getSound();if(sound!=null&&!sound.equals(Settings.System.DEFAULT_NOTIFICATION_URI)&&!sound.equals(Settings.System.DEFAULT_RINGTONE_URI)&&!sound.equals(Settings.System.DEFAULT_ALARM_ALERT_URI))throw new UnsupportedOperationException("NOTIFICATION_SOUND_URI_UNSUPPORTED");
        NotificationChannel out=copy(value,NotificationChannel.CREATOR);var scope=scope();
        RuntimeBridge.notificationChannelIdentity(out,scope.physical(value.getId()),value.getGroup()==null?null:scope.physical(value.getGroup()));return out;
    }
    private NotificationChannel toGuest(NotificationChannel value){
        if(value==null)return null;channelKind(value);NotificationChannel out=copy(value,NotificationChannel.CREATOR);var scope=scope();
        RuntimeBridge.notificationChannelIdentity(out,scope.logical(value.getId()),value.getGroup()==null?null:scope.logical(value.getGroup()));return out;
    }
    void createChannels(List<NotificationChannel> values){
        if(values==null||values.size()>64)throw new IllegalArgumentException("NOTIFICATION_BATCH_LIMIT");List<NotificationChannel> mapped=new ArrayList<>();for(NotificationChannel value:values)mapped.add(toSystem(value));platform().createNotificationChannels(mapped);
    }
    void createGroups(List<NotificationChannelGroup> values){
        if(values==null||values.size()>64)throw new IllegalArgumentException("NOTIFICATION_BATCH_LIMIT");List<NotificationChannelGroup> mapped=new ArrayList<>();var scope=scope();
        for(NotificationChannelGroup value:values){Objects.requireNonNull(value);text(value.getName());text(value.getDescription());if(!value.getChannels().isEmpty())throw new UnsupportedOperationException("NOTIFICATION_GROUP_INPUT_CHANNELS_UNSUPPORTED");NotificationChannelGroup out=copy(value,NotificationChannelGroup.CREATOR);RuntimeBridge.notificationGroupIdentity(out,scope.physical(value.getId()),new ArrayList<>());mapped.add(out);}platform().createNotificationChannelGroups(mapped);
    }
    NotificationChannel channel(String id,String conversation){if(conversation!=null)throw new UnsupportedOperationException("NOTIFICATION_CONVERSATION_UNSUPPORTED");return toGuest(platform().getNotificationChannel(scope().physical(id)));}
    List<NotificationChannel> channels(){List<NotificationChannel> result=new ArrayList<>();var scope=scope();for(NotificationChannel value:platform().getNotificationChannels())if(scope.owns(value.getId()))result.add(toGuest(value));return result;}
    private NotificationChannelGroup toGuest(NotificationChannelGroup value){
        if(value==null)return null;NotificationChannelGroup out=copy(value,NotificationChannelGroup.CREATOR);List<NotificationChannel> members=new ArrayList<>();for(NotificationChannel channel:value.getChannels())members.add(toGuest(channel));RuntimeBridge.notificationGroupIdentity(out,scope().logical(value.getId()),members);return out;
    }
    NotificationChannelGroup group(String id){return toGuest(platform().getNotificationChannelGroup(scope().physical(id)));}
    List<NotificationChannelGroup> groups(){List<NotificationChannelGroup> result=new ArrayList<>();var scope=scope();for(NotificationChannelGroup value:platform().getNotificationChannelGroups())if(scope.owns(value.getId()))result.add(toGuest(value));return result;}
    void deleteChannel(String id){platform().deleteNotificationChannel(scope().physical(id));}
    void deleteGroup(String id){platform().deleteNotificationChannelGroup(scope().physical(id));}
    void cancel(String tag,int id){new RuntimeNotificationCancellation(engine.storage.dataId).cancel(platform(),tag,id);}
    void cancelAll(){new RuntimeNotificationCancellation(engine.storage.dataId).cancelAll(platform());}
}
