package dev.tripoder.runtime;

/** Identity, cross-UID traffic and mutation boundaries must survive the fast path. */
public final class FixedPassthroughTest {
    public static void main(String[] args){
        for(String key:new String[]{"getUidRxBytes(int)","getUidStats(int,int)","getTotalStats(int)","getIfaceStats(java.lang.String,int)","setThreadStatsUid(int)"})
            if(RuntimeFixedPassthrough.trafficMethod(key))throw new AssertionError("traffic admission removed: "+key);
        if(!RuntimeFixedPassthrough.trafficMethod("setThreadStatsTag(int)"))throw new AssertionError("tagging still intercepted");
        for(String name:new String[]{"getPackageInfo","getApplicationInfo","getPackagesForUid","getInstalledPackages","getInstallerPackageName","getResourcesForApplication","setComponentEnabledSetting","getProperty","getUserId"})
            if(RuntimeFixedPassthrough.packageMethod("android.app.ApplicationPackageManager",name,1))throw new AssertionError("package boundary removed: "+name);
        if(!RuntimeFixedPassthrough.packageMethod("android.app.ApplicationPackageManager","getPackageArchiveInfo",2))throw new AssertionError("archive passthrough missing");
        if(RuntimeFixedPassthrough.packageMethod("android.app.ContextImpl","getPackageArchiveInfo",2))throw new AssertionError("foreign owner admitted");
        if(RuntimeFixedPassthrough.packageMethod("android.app.ApplicationPackageManager","getSystemAvailableFeatures",1))throw new AssertionError("non-passthrough overload admitted");
        System.out.println("fixed passthrough boundaries checked");
    }
}
