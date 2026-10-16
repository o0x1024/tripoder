package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import java.util.*;

/** One immutable installed-host snapshot, captured before entering the interpreter policy. */
final class RuntimePhysicalManifest {
    private static Context owner;
    private static String packageName,source;
    private static Map<String,Set<ComponentName>> components;
    static synchronized Map<String,Set<ComponentName>> capture(Context host){
        if(components!=null){if(host!=owner||!host.getPackageName().equals(packageName)||!host.getApplicationInfo().sourceDir.equals(source))throw new SecurityException("PHYSICAL_MANIFEST_OWNER_CHANGED");return components;}
        String pkg=host.getPackageName(),apk=host.getApplicationInfo().sourceDir;
        PackageInfo physical=host.getPackageManager().getPackageArchiveInfo(apk,PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        if(physical==null||!pkg.equals(physical.packageName))throw new SecurityException("INFRASTRUCTURE_MANIFEST_IDENTITY_MISMATCH");
        Map<String,Set<ComponentName>> result=new HashMap<>();result.put("getActivityInfo",names(physical.activities));result.put("getServiceInfo",names(physical.services));result.put("getProviderInfo",names(physical.providers));
        owner=host;packageName=pkg;source=apk;components=Map.copyOf(result);return components;
    }
    private static Set<ComponentName> names(ComponentInfo[] declarations){Set<ComponentName> result=new HashSet<>();if(declarations!=null)for(var info:declarations)result.add(new ComponentName(info.packageName,info.name));return Set.copyOf(result);}
    private RuntimePhysicalManifest(){}
}
