package dev.tripoder.provideridentity;

import android.app.*;
import android.content.Context;
import java.util.*;
import org.json.*;

final class NotificationChecks {
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        check(checks,"notification-manager-stable",manager!=null&&manager==context.getSystemService(Context.NOTIFICATION_SERVICE));
        manager.cancel(991);manager.cancel("",991);manager.cancel("owned-取消",991);
        check(checks,"notification-cancel-missing-id-is-idempotent",true);
        String id="owned-channel-中文/one",group="owned-group-中文/one";
        NotificationChannelGroup declaredGroup=new NotificationChannelGroup(group,"Owned channel contract");declaredGroup.setDescription("Owned group description");
        NotificationChannel declared=new NotificationChannel(id,"Owned channel",NotificationManager.IMPORTANCE_LOW);declared.setGroup(group);declared.setDescription("Owned description");declared.setShowBadge(false);declared.enableVibration(true);declared.setVibrationPattern(new long[]{0,20,30});declared.setSound(null,null);
        try{
            manager.createNotificationChannelGroup(declaredGroup);manager.createNotificationChannels(List.of(declared));
            check(checks,"notification-input-identities-unchanged",declared.getId().equals(id)&&declared.getGroup().equals(group)&&declaredGroup.getId().equals(group));
            NotificationChannel actual=manager.getNotificationChannel(id);NotificationChannelGroup actualGroup=manager.getNotificationChannelGroup(group);
            check(checks,"notification-platform-channel-fields",actual!=null&&id.equals(actual.getId())&&group.equals(actual.getGroup())&&actual.getImportance()==NotificationManager.IMPORTANCE_LOW&&!actual.canShowBadge()&&actual.shouldVibrate()&&Arrays.equals(actual.getVibrationPattern(),new long[]{0,20,30})&&actual.getSound()==null&&"Owned description".equals(actual.getDescription()));
            check(checks,"notification-group-members-logical",actualGroup!=null&&group.equals(actualGroup.getId())&&actualGroup.getChannels().size()==1&&id.equals(actualGroup.getChannels().get(0).getId()));
            check(checks,"notification-own-channel-and-group-listed",manager.getNotificationChannels().stream().anyMatch(v->id.equals(v.getId()))&&manager.getNotificationChannelGroups().stream().anyMatch(v->group.equals(v.getId())));
            actual.setDescription("changed-copy");check(checks,"notification-query-copy-does-not-update-platform","Owned description".equals(manager.getNotificationChannel(id).getDescription()));
            manager.createNotificationChannel(new NotificationChannel(id,"Owned channel renamed",NotificationManager.IMPORTANCE_HIGH));
            check(checks,"notification-recreate-preserves-system-importance",manager.getNotificationChannel(id).getImportance()==NotificationManager.IMPORTANCE_LOW);
            if(!baseline){
                check(checks,"notification-other-space-and-host-channels-excluded",manager.getNotificationChannels().size()==1&&manager.getNotificationChannelGroups().size()==1);
                boolean post=false,dnd=false;// Posting is modelled now; a notification without a small icon is rejected exactly as the platform rejects it.
                try{manager.notify(91,new Notification());}catch(IllegalArgumentException expected){post=String.valueOf(expected.getMessage()).contains("no valid small icon");}
                try{manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);}catch(UnsupportedOperationException expected){dnd=expected.getMessage().contains("NOTIFICATION_CAPABILITY_UNSUPPORTED");}
                check(checks,"notification-post-validated-and-dnd-not-implicitly-granted",post&&dnd);
            }
            return new JSONObject().put("enabled",manager.areNotificationsEnabled()).put("importance",manager.getImportance()).put("channelImportance",manager.getNotificationChannel(id).getImportance());
        }finally{
            manager.deleteNotificationChannelGroup(group);
            check(checks,"notification-group-delete-removes-member",manager.getNotificationChannelGroup(group)==null&&manager.getNotificationChannel(id)==null);
        }
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private NotificationChecks(){}
}
