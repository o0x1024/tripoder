package dev.tripoder.runtime;

import java.util.Set;

/** Only boundaries whose existing callback unconditionally calls the same original.
 * Admission, identity conversion and denial routes must never enter this list. */
final class RuntimeFixedPassthrough {
    private static final Set<String> PACKAGE=Set.of("buildRequestPermissionsIntent","getPermissionControllerPackageName",
        "getPackageArchiveInfo","getSystemSharedLibraryNames","getSharedLibraries","getAllPermissionGroups",
        "getPermissionGroupInfo","queryPermissionsByGroup","getPermissionInfo","isSafeMode",
        "getInstantAppCookieMaxBytes","getInstantAppCookie");
    private static final Set<String> TRAFFIC=Set.of("getMobileRxBytes()","getMobileTxBytes()","getMobileRxPackets()","getMobileTxPackets()",
        "getTotalRxBytes()","getTotalTxBytes()","getTotalRxPackets()","getTotalTxPackets()","getRxBytes(java.lang.String)","getTxBytes(java.lang.String)","getRxPackets(java.lang.String)","getTxPackets(java.lang.String)",
        "setThreadStatsTag(int)","getThreadStatsTag()","clearThreadStatsTag()","getAndSetThreadStatsTag(int)","getThreadStatsUid()",
        "tagSocket(java.net.Socket)","untagSocket(java.net.Socket)","tagDatagramSocket(java.net.DatagramSocket)","untagDatagramSocket(java.net.DatagramSocket)",
        "tagFileDescriptor(java.io.FileDescriptor)","untagFileDescriptor(java.io.FileDescriptor)","incrementOperationCount(int)","incrementOperationCount(int,int)");
    static boolean packageMethod(String owner,String name,int parameters){
        return (owner.equals("android.app.ApplicationPackageManager")||owner.equals("android.content.pm.PackageManager"))
            &&(PACKAGE.contains(name)||name.equals("getSystemAvailableFeatures")&&parameters==0);
    }
    static boolean trafficMethod(String signature){return TRAFFIC.contains(signature);}
    private RuntimeFixedPassthrough(){}
}
