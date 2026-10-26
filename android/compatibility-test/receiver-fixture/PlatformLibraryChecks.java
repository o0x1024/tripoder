package dev.tripoder.closurefixture;

import android.app.Activity;
import java.lang.reflect.Field;

/** Pinned Xiaomi device libraries; initialize only the inspected constant-only version class. */
final class PlatformLibraryChecks {
    static void run(Activity activity,ResourceOriginChecks.Check check)throws Exception{
        ClassLoader guest=activity.getClassLoader();
        Class<?> apache=Class.forName("org.apache.commons.logging.LogFactory",false,guest);
        Class<?> cloud=Class.forName("com.xiaomi.micloudsdk.os.MiCloudSdkVersion",false,guest);
        Class<?> system=Class.forName("miui.yellowpage.YellowPage",false,guest);
        check.test("dynamic-system-apk-and-builtin-have-separate-real-defining-loaders",apache.getClassLoader()!=null&&cloud.getClassLoader()!=null&&system.getClassLoader()!=null&&apache.getClassLoader()!=cloud.getClassLoader()&&cloud.getClassLoader()!=system.getClassLoader()&&cloud.getClassLoader()!=guest&&system.getClassLoader()!=guest);
        Field version=cloud.getDeclaredField("version");version.setAccessible(true);
        check.test("dynamic-micloud-sdk-executes-inspected-constant-initializer",version.getInt(null)==42);
        check.test("application-resolves-same-dependency-class-identity",Class.forName(cloud.getName(),false,guest)==cloud&&Class.forName(system.getName(),false,guest)==system);
        Object owned=system.getConstructor().newInstance();var setter=system.getDeclaredMethod("setContent",String.class);setter.setAccessible(true);setter.invoke(owned,"owned-library-contract");
        check.test("dynamic-system-sdk-executes-owned-pure-data-roundtrip",system.getMethod("getContent").invoke(owned).equals("owned-library-contract"));
        check.test("declared-platform-library-resources-are-projected",java.util.Arrays.stream(activity.getApplicationInfo().sharedLibraryFiles).anyMatch(path->path.endsWith("/RtMiCloudSDK.apk"))&&java.util.Arrays.stream(activity.getApplicationInfo().sharedLibraryFiles).anyMatch(path->path.endsWith("/miuisystem.apk")));
        try(var settings=activity.getContentResolver().acquireContentProviderClient("settings")){
            Class<?> access=Class.forName("dev.tripoder.selfhook.PackageFrameworkAccess",false,settings.getLocalContentProvider().getClass().getClassLoader());
            String[] physical=(String[])access.getMethod("systemLibraries").invoke(null);
            String[] first=activity.getPackageManager().getSystemSharedLibraryNames();
            check.test("system-library-capability-names-match-real-physical-endpoint",java.util.Arrays.equals(physical,first)&&java.util.Arrays.equals(physical,activity.getApplicationContext().getPackageManager().getSystemSharedLibraryNames()));
            if(first!=null&&first.length>0)first[0]="owned-mutated-capability-name";
            String[] second=activity.getPackageManager().getSystemSharedLibraryNames();
            check.test("system-library-capability-result-mutation-cannot-change-device-table",java.util.Arrays.equals(physical,second)&&(first==null||first!=second));
        }
    }
    private PlatformLibraryChecks(){}
}
