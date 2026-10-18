package dev.tripoder.runtime;

import android.app.*;
import android.content.Context;
import android.graphics.drawable.Icon;
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
        Uri sound=value.getSound();boolean ownResource=false;
        if(sound!=null&&!sound.equals(Settings.System.DEFAULT_NOTIFICATION_URI)&&!sound.equals(Settings.System.DEFAULT_RINGTONE_URI)&&!sound.equals(Settings.System.DEFAULT_ALARM_ALERT_URI)){
            // The platform notification service cannot open a resource of the guest APK, so the channel falls back to the default notification sound instead of failing the application's startup. Any other URI would make the service read data elsewhere and stays rejected.
            if(!"android.resource".equals(sound.getScheme())||!engine.packageName.equals(sound.getAuthority()))throw new UnsupportedOperationException("NOTIFICATION_SOUND_URI_UNSUPPORTED");
            ownResource=true;
        }
        NotificationChannel out=copy(value,NotificationChannel.CREATOR);var scope=scope();
        if(ownResource)out.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,out.getAudioAttributes());
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
    /** Posts a guest notification as this host. SystemUI resolves icons and resources by the posting package, which for a guest is not installed, so everything it would look up is
     *  rendered here (icons become bitmaps) and everything addressed by name goes through the guest's own channel/tag namespaces. Content that only exists as guest-package
     *  resources (custom RemoteViews) cannot be shown and is left out. PendingIntent tokens are already host-issued and pass unchanged. */
    void post(String tag,int id,Notification guest){
        if(guest==null)throw new NullPointerException("notification");
        Context guestContext=engine.application!=null?engine.application:engine.host;
        Icon small=render(guest.getSmallIcon(),guestContext,96);
        if(small==null)throw new IllegalArgumentException("Invalid notification (no valid small icon): "+guest);
        String channel=guest.getChannelId();NotificationManager platform=platform();
        String physicalChannel;
        if(channel==null){physicalChannel=scope().physical("miscellaneous");if(platform.getNotificationChannel(physicalChannel)==null)platform.createNotificationChannel(new NotificationChannel(physicalChannel,"Notifications",NotificationManager.IMPORTANCE_DEFAULT));}
        else physicalChannel=scope().physical(channel);
        Bundle x=guest.extras==null?new Bundle():guest.extras;
        Notification.Builder b=new Notification.Builder(engine.host,physicalChannel).setSmallIcon(small);
        CharSequence title=x.getCharSequence(Notification.EXTRA_TITLE),text=x.getCharSequence(Notification.EXTRA_TEXT);
        if(title!=null)b.setContentTitle(title);if(text!=null)b.setContentText(text);
        CharSequence sub=x.getCharSequence(Notification.EXTRA_SUB_TEXT),info=x.getCharSequence(Notification.EXTRA_INFO_TEXT);
        if(sub!=null)b.setSubText(sub);if(info!=null)b.setContentInfo(info);if(guest.tickerText!=null)b.setTicker(guest.tickerText);
        Icon large=render(guest.getLargeIcon(),guestContext,256);if(large!=null)b.setLargeIcon(large);
        if(guest.when!=0)b.setWhen(guest.when);b.setShowWhen(x.getBoolean(Notification.EXTRA_SHOW_WHEN,guest.when!=0));
        b.setUsesChronometer(x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        if(guest.number>0)b.setNumber(guest.number);if(guest.color!=0)b.setColor(guest.color);if(guest.category!=null)b.setCategory(guest.category);
        b.setVisibility(guest.visibility);b.setOngoing((guest.flags&Notification.FLAG_ONGOING_EVENT)!=0).setAutoCancel((guest.flags&Notification.FLAG_AUTO_CANCEL)!=0).setOnlyAlertOnce((guest.flags&Notification.FLAG_ONLY_ALERT_ONCE)!=0);
        if(guest.getGroup()!=null)b.setGroup(guest.getGroup());b.setGroupSummary((guest.flags&Notification.FLAG_GROUP_SUMMARY)!=0);if(guest.getSortKey()!=null)b.setSortKey(guest.getSortKey());
        if(x.containsKey(Notification.EXTRA_PROGRESS_MAX))b.setProgress(x.getInt(Notification.EXTRA_PROGRESS_MAX),x.getInt(Notification.EXTRA_PROGRESS),x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
        if(guest.contentIntent!=null)b.setContentIntent(guest.contentIntent);if(guest.deleteIntent!=null)b.setDeleteIntent(guest.deleteIntent);
        if(guest.fullScreenIntent!=null)b.setFullScreenIntent(guest.fullScreenIntent,(guest.flags&Notification.FLAG_HIGH_PRIORITY)!=0);
        if(guest.actions!=null)for(Notification.Action action:guest.actions){
            if(action==null||action.actionIntent==null||action.title==null)continue;
            Icon icon=render(action.getIcon(),guestContext,96);b.addAction(new Notification.Action.Builder(icon,action.title,action.actionIntent).build());
        }
        String template=x.getString(Notification.EXTRA_TEMPLATE);
        if("android.app.Notification$BigTextStyle".equals(template)){CharSequence big=x.getCharSequence(Notification.EXTRA_BIG_TEXT);if(big!=null)b.setStyle(new Notification.BigTextStyle().bigText(big));}
        else if("android.app.Notification$InboxStyle".equals(template)){CharSequence[] lines=x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);if(lines!=null){Notification.InboxStyle inbox=new Notification.InboxStyle();for(CharSequence line:lines)inbox.addLine(line);b.setStyle(inbox);}}
        else if("android.app.Notification$BigPictureStyle".equals(template)){android.graphics.Bitmap picture=x.getParcelable(Notification.EXTRA_PICTURE,android.graphics.Bitmap.class);if(picture!=null)b.setStyle(new Notification.BigPictureStyle().bigPicture(picture));}
        platform.notify(new RuntimeNotificationCancellation(engine.storage.dataId).tag(tag),id,b.build());
    }
    /** An icon that SystemUI could not resolve (guest package resources) becomes a bitmap the system can draw as is. */
    private static Icon render(Icon icon,Context guestContext,int limit){
        if(icon==null)return null;
        if(icon.getType()==Icon.TYPE_BITMAP||icon.getType()==Icon.TYPE_ADAPTIVE_BITMAP||icon.getType()==Icon.TYPE_URI)return icon;
        android.graphics.drawable.Drawable drawable=icon.loadDrawable(guestContext);if(drawable==null)return null;
        int width=Math.max(1,Math.min(limit,drawable.getIntrinsicWidth()>0?drawable.getIntrinsicWidth():limit)),height=Math.max(1,Math.min(limit,drawable.getIntrinsicHeight()>0?drawable.getIntrinsicHeight():limit));
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);drawable.setBounds(0,0,width,height);drawable.draw(canvas);
        return Icon.createWithBitmap(bitmap);
    }
    void cancel(String tag,int id){new RuntimeNotificationCancellation(engine.storage.dataId).cancel(platform(),tag,id);}
    void cancelAll(){new RuntimeNotificationCancellation(engine.storage.dataId).cancelAll(platform());}
}
