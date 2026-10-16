package dev.tripoder.provideridentity;

import android.content.*;
import android.content.pm.*;
import org.json.*;

final class ActivityInfoChecks {
    static void inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        PackageManager pm=context.getPackageManager();String pkg=context.getPackageName();ComponentName alias=new ComponentName(pkg,pkg+".IdentityAlias"),disabled=new ComponentName(pkg,pkg+".DisabledActivity");
        ActivityInfo plain=pm.getActivityInfo(alias,0);
        check(checks,"activity-query-alias-retains-declared-identity",plain.name.equals(alias.getClassName())&&plain.targetActivity.equals(pkg+".IdentityActivity")&&plain.packageName.equals(pkg)&&!plain.exported);
        check(checks,"activity-query-plain-omits-metadata",plain.metaData==null&&plain.applicationInfo.metaData==null);
        ActivityInfo info=pm.getActivityInfo(alias,PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA));
        check(checks,"activity-query-original-metadata-and-logical-source","alias-metadata".equals(info.metaData.getString("owned.alias"))&&"owned-installation".equals(info.applicationInfo.metaData.getString("owned.label"))&&info.applicationInfo.sourceDir.equals(context.getApplicationInfo().sourceDir)&&info.applicationInfo.uid==android.os.Process.myUid());
        boolean absent=false;try{pm.getActivityInfo(new ComponentName(pkg,pkg+".NoSuchActivity"),0);}catch(PackageManager.NameNotFoundException expected){absent=true;}check(checks,"activity-query-unknown-declaration-is-missing",absent);
        absent=false;try{pm.getActivityInfo(disabled,0);}catch(PackageManager.NameNotFoundException expected){absent=true;}check(checks,"activity-query-disabled-excluded-by-default",absent);
        int flags=PackageManager.GET_META_DATA|PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE;
        ActivityInfo blocked=pm.getActivityInfo(disabled,flags);
        check(checks,"activity-query-disabled-other-process-metadata",!blocked.enabled&&blocked.directBootAware&&blocked.processName.equals(pkg+":metadata")&&"disabled-metadata".equals(blocked.metaData.getString("owned.disabled"))&&blocked.theme!=0&&(blocked.configChanges&ActivityInfo.CONFIG_ORIENTATION)!=0&&(blocked.configChanges&ActivityInfo.CONFIG_SCREEN_SIZE)!=0);
        ActivityInfo automatic=pm.getActivityInfo(disabled,PackageManager.ComponentInfoFlags.of(flags|PackageManager.MATCH_DIRECT_BOOT_AUTO|PackageManager.MATCH_DIRECT_BOOT_UNAWARE));
        check(checks,"activity-query-auto-with-platform-expanded-direct-flags",automatic.name.equals(disabled.getClassName())&&automatic.directBootAware&&"disabled-metadata".equals(automatic.metaData.getString("owned.disabled")));
        check(checks,"activity-query-auto-public-facade-on-unlocked-device",pm.getActivityInfo(alias,PackageManager.MATCH_DIRECT_BOOT_AUTO).name.equals(alias.getClassName()));
        absent=false;try{pm.getActivityInfo(disabled,PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_UNAWARE);}catch(PackageManager.NameNotFoundException expected){absent=true;}check(checks,"activity-query-direct-boot-filter-preserved",absent);
        info.metaData.putString("owned.alias","tampered");info.applicationInfo.metaData.putString("owned.label","tampered");info.applicationInfo.sourceDir="/tampered";info.targetActivity="tampered";
        ActivityInfo again=pm.getActivityInfo(alias,PackageManager.GET_META_DATA);
        check(checks,"activity-query-defensive-component-and-application-copy","alias-metadata".equals(again.metaData.getString("owned.alias"))&&"owned-installation".equals(again.applicationInfo.metaData.getString("owned.label"))&&!again.applicationInfo.sourceDir.equals("/tampered")&&again.targetActivity.equals(pkg+".IdentityActivity"));
        if(!baseline){boolean unsupported=false;try{pm.getActivityInfo(alias,PackageManager.ComponentInfoFlags.of(1L<<40));}catch(UnsupportedOperationException expected){unsupported=expected.getMessage().contains("ACTIVITY_INFO_FLAGS_UNSUPPORTED");}check(checks,"activity-query-unknown-long-flag-rejected",unsupported);}
    }
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private ActivityInfoChecks(){}
}
