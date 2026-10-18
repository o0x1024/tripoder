package dev.tripoder.provideridentity;

import android.content.Context;
import android.content.pm.*;
import java.util.*;
import org.json.*;

final class PackageInfoChecks {
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    static void inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        PackageManager pm=context.getPackageManager();String pkg=context.getPackageName();
        PackageInfo plain=pm.getPackageInfo(pkg,0);
        check(checks,"package-info-own-version-and-commit-times",pkg.equals(plain.packageName)&&plain.getLongVersionCode()==1&&plain.firstInstallTime>0&&plain.lastUpdateTime>=plain.firstInstallTime&&plain.applicationInfo.uid==android.os.Process.myUid());
        check(checks,"package-info-components-and-signers-flag-bound",plain.providers==null&&plain.services==null&&plain.activities==null&&plain.signatures==null&&plain.signingInfo==null);
        int flags=PackageManager.GET_PROVIDERS|PackageManager.GET_SERVICES|PackageManager.GET_ACTIVITIES|PackageManager.GET_META_DATA;
        PackageInfo selected=pm.getPackageInfo(pkg,PackageManager.PackageInfoFlags.of(flags));
        check(checks,"package-info-declared-components-present",selected.providers.length==3&&selected.services.length==2&&selected.activities.length==9);
        check(checks,"package-info-component-logical-identity",Arrays.stream(selected.providers).allMatch(p->pkg.equals(p.packageName)&&p.applicationInfo.uid==android.os.Process.myUid()&&p.applicationInfo.sourceDir.equals(context.getApplicationInfo().sourceDir)));
        check(checks,"package-info-metadata-projects-from-artifact","owned-installation".equals(selected.applicationInfo.metaData.getString("owned.label"))&&Arrays.stream(selected.providers).anyMatch(p->p.metaData!=null&&"first".equals(p.metaData.getString("identity.label"))));
        PackageInfo disabled=pm.getPackageInfo(pkg,PackageManager.GET_SERVICES|PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        check(checks,"package-info-disabled-component-explicitly-included",disabled.services.length==3&&Arrays.stream(disabled.services).anyMatch(s->!s.enabled&&s.directBootAware));
        PackageInfo signed=pm.getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_SIGNATURES);
        check(checks,"package-info-original-apk-signing-identity",signed.signingInfo!=null&&signed.signingInfo.getApkContentsSigners().length==1&&signed.signatures.length==1&&signed.signatures[0].equals(signed.signingInfo.getApkContentsSigners()[0]));
        check(checks,"package-info-versioned-current-query",pm.getPackageInfo(new VersionedPackage(pkg,1),0).getLongVersionCode()==1);
        check(checks,"package-info-ordinary-package-version-selector",pm.getPackageInfo(new VersionedPackage(pkg,2),0).getLongVersionCode()==1&&pm.getPackageInfo(new VersionedPackage(pkg,PackageManager.VERSION_CODE_HIGHEST),0).getLongVersionCode()==1);
        if(!baseline){
            selected.providers[0].authority="tampered";selected.providers[0].applicationInfo.dataDir="/tampered";selected.applicationInfo.metaData.putString("owned.label","tampered");
            PackageInfo again=pm.getPackageInfo(pkg,flags);check(checks,"package-info-defensive-owned-archive",!again.providers[0].authority.equals("tampered")&&!again.providers[0].applicationInfo.dataDir.equals("/tampered")&&"owned-installation".equals(again.applicationInfo.metaData.getString("owned.label")));
            boolean unknown=false;try{pm.getPackageInfo(pkg,PackageManager.PackageInfoFlags.of(1L<<40));}catch(UnsupportedOperationException expected){unknown=expected.getMessage().contains("PACKAGE_INFO_FLAGS_UNSUPPORTED:0x10000000000");}check(checks,"package-info-unknown-long-flags-rejected",unknown);
            boolean other=false;try{pm.getPackageInfo("dev.tripoder.not-in-this-world",0);}catch(PackageManager.NameNotFoundException expected){other=true;}check(checks,"package-info-other-package-not-exposed",other);
        }
    }
    private PackageInfoChecks(){}
}
