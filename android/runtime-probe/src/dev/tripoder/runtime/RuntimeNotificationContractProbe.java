package dev.tripoder.runtime;

import android.app.*;
import android.content.Context;
import android.os.Bundle;

/** Test-only actual system channel observations; never included in the product APK. */
public final class RuntimeNotificationContractProbe {
    public static Bundle seed(Context host,String dataId){
        RuntimeNotificationNamespace scope=new RuntimeNotificationNamespace(dataId);String id=scope.physical("owned.same"),group=scope.physical("owned.group");
        NotificationManager manager=host.getSystemService(NotificationManager.class);manager.createNotificationChannelGroup(new NotificationChannelGroup(group,"Tripoder owned recovery check"));
        NotificationChannel channel=new NotificationChannel(id,"Tripoder owned channel",NotificationManager.IMPORTANCE_LOW);channel.setGroup(group);channel.setSound(null,null);manager.createNotificationChannel(channel);
        Bundle out=new Bundle();out.putString("id",id);out.putString("group",group);out.putBoolean("exists",manager.getNotificationChannel(id)!=null);return out;
    }
    public static Bundle inspect(Context host,String id,String group){
        if(!id.startsWith(RuntimeNotificationNamespace.ROOT)||!group.startsWith(RuntimeNotificationNamespace.ROOT))throw new SecurityException("PROBE_NOT_OWNED_CHANNEL");
        var manager=host.getSystemService(NotificationManager.class);Bundle out=new Bundle();out.putBoolean("exists",manager.getNotificationChannel(id)!=null);out.putBoolean("groupExists",manager.getNotificationChannelGroup(group)!=null);return out;
    }
    private RuntimeNotificationContractProbe(){}
}
