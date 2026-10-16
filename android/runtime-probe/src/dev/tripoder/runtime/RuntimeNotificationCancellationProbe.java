package dev.tripoder.runtime;

import android.app.*;
import android.content.Context;
import android.os.SystemClock;
import java.util.*;
import org.json.*;

/** Real posted system records exercise production cancellation; absent from product builds. */
public final class RuntimeNotificationCancellationProbe {
    public static JSONObject run(Context host)throws Exception{
        NotificationManager manager=Objects.requireNonNull(host.getSystemService(NotificationManager.class));
        if(!manager.areNotificationsEnabled())throw new IllegalStateException("OWNED_NOTIFICATION_TEST_PERMISSION_REQUIRED");
        RuntimeNotificationCancellation first=new RuntimeNotificationCancellation(UUID.randomUUID().toString()),second=new RuntimeNotificationCancellation(UUID.randomUUID().toString());
        String channel="tripoder.test.cancel."+UUID.randomUUID(),outside="tripoder.test.control."+UUID.randomUUID();
        Set<String> all=new HashSet<>(Arrays.asList(first.tag(null),first.tag(""),first.tag("中文/owned"),second.tag("中文/owned"),outside));JSONArray checks=new JSONArray();
        manager.createNotificationChannel(new NotificationChannel(channel,"Tripoder notification cancellation check",NotificationManager.IMPORTANCE_LOW));
        try{
            for(String tag:all)manager.notify(tag,7,new Notification.Builder(host,channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Tripoder owned cancellation test").setContentText("Temporary self-authored verification record").build());
            check(checks,"notification-five-real-platform-records-visible",await(manager,all,all));
            first.cancel(manager,"missing",7);check(checks,"notification-missing-cancel-preserves-five-records",snapshot(manager,all).equals(all));
            first.cancel(manager,null,7);Set<String> afterOne=new HashSet<>(all);afterOne.remove(first.tag(null));
            check(checks,"notification-null-tag-cancel-keeps-empty-and-other-owners",await(manager,all,afterOne));
            first.cancel(manager,null,7);check(checks,"notification-repeat-cancel-is-idempotent",snapshot(manager,all).equals(afterOne));
            first.cancelAll(manager);Set<String> outsideOnly=Set.of(second.tag("中文/owned"),outside);
            check(checks,"notification-cancel-all-keeps-other-data-and-engine-record",await(manager,all,outsideOnly));
            first.cancelAll(manager);check(checks,"notification-repeat-cancel-all-preserves-other-owners",snapshot(manager,all).equals(outsideOnly));
            boolean malformed=false,longTag=false;
            try{first.tag("\ud800");}catch(IllegalArgumentException expected){malformed=true;}
            try{first.tag("x".repeat(513));}catch(IllegalArgumentException expected){longTag=true;}
            check(checks,"notification-invalid-tags-rejected-before-system-call",malformed&&longTag);
            return new JSONObject().put("checks",checks).put("passed",true).put("actualSystemPosts",5).put("actualOwnRecordsCanceled",3).put("otherOwnerRecordsPreserved",2);
        }finally{for(String tag:all)manager.cancel(tag,7);manager.deleteNotificationChannel(channel);}
    }
    private static Set<String> snapshot(NotificationManager manager,Set<String> selected){Set<String> result=new HashSet<>();for(var value:manager.getActiveNotifications())if(value.getId()==7&&selected.contains(value.getTag()))result.add(value.getTag());return result;}
    private static boolean await(NotificationManager manager,Set<String> selected,Set<String> expected){long end=SystemClock.elapsedRealtime()+5000;do{if(snapshot(manager,selected).equals(expected))return true;SystemClock.sleep(30);}while(SystemClock.elapsedRealtime()<end);return false;}
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private RuntimeNotificationCancellationProbe(){}
}
