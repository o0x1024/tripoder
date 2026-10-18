package dev.tripoder.runtime;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import java.util.List;

/** Which package presents the platform's runtime-permission dialog. The authority process is unhooked, so its own PackageManager answers for the platform; guests only ask. */
final class RuntimePermissionController {
    private RuntimePermissionController(){}
    private static volatile String cached;
    static Bundle query(RuntimeBroker broker,Bundle input,int pid,int uid)throws Exception{
        synchronized(broker){broker.contract.authenticate(RuntimeWire.required(input,"generation"),pid,uid);}
        String value=cached;
        if(value==null){
            PackageManager manager=broker.host.getPackageManager();
            List<ResolveInfo> found=manager.queryIntentActivities(new Intent("android.content.pm.action.REQUEST_PERMISSIONS"),PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY));
            for(ResolveInfo info:found)if(info.activityInfo!=null&&!broker.host.getPackageName().equals(info.activityInfo.packageName)){value=info.activityInfo.packageName;break;}
            // The two names the platform has used; presence is checked by the system when the dialog starts.
            if(value==null)value="com.google.android.permissioncontroller";
            cached=value;
        }
        Bundle out=new Bundle();out.putString("package",value);return out;
    }
}
