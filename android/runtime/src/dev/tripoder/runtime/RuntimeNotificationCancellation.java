package dev.tripoder.runtime;

import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Cancellation never uses the physical package-wide cancelAll operation. */
final class RuntimeNotificationCancellation {
    private static final String ROOT="tripoder.notifications.v1.";
    private final String prefix;
    RuntimeNotificationCancellation(String dataId){prefix=ROOT+RuntimeNotificationNamespace.incarnation(dataId)+".";}
    String tag(String logical){
        if(logical==null)return prefix+"n";
        byte[] bytes=logical.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>512||!logical.equals(new String(bytes,StandardCharsets.UTF_8)))throw new IllegalArgumentException("NOTIFICATION_TAG_UNSUPPORTED");
        return prefix+"s"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    boolean owns(String physical){
        if(physical==null||!physical.startsWith(prefix))return false;
        if(physical.equals(prefix+"n"))return true;
        if(!physical.startsWith(prefix+"s"))throw new IllegalStateException("NOTIFICATION_TAG_ENCODING_INVALID");
        String logical=new String(Base64.getUrlDecoder().decode(physical.substring(prefix.length()+1)),StandardCharsets.UTF_8);
        if(!tag(logical).equals(physical))throw new IllegalStateException("NOTIFICATION_TAG_ENCODING_INVALID");
        return true;
    }
    String logical(String physical){
        if(!owns(physical))throw new SecurityException("NOTIFICATION_TAG_SCOPE_MISMATCH");
        return physical.equals(prefix+"n")?null:new String(Base64.getUrlDecoder().decode(physical.substring(prefix.length()+1)),StandardCharsets.UTF_8);
    }
    void cancel(NotificationManager platform,String logical,int id){platform.cancel(tag(logical),id);}
    void cancelAll(NotificationManager platform){
        // Snapshot semantics: concurrently posted notifications can remain, just as a later post
        // can follow a platform cancelAll. No unrelated tag or foreground service is addressed.
        for(StatusBarNotification value:platform.getActiveNotifications())if(owns(value.getTag()))platform.cancel(value.getTag(),value.getId());
    }
}
