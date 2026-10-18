package dev.tripoder.selfhook;

/** Acceptance APK only: execute the exact fixed-platform handler on its real ActivityThread. */
public final class PackageReplacementChecks {
    public static void dispatch(String physicalPackage)throws Exception{
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())throw new IllegalStateException("PACKAGE_REPLACEMENT_MAIN_REQUIRED");
        var method=FixedFrameworkMembers.method(1,"handleDispatchPackageBroadcast","(I[Ljava/lang/String;)V",false);
        Object thread=PhysicalApplication.thread();
        FixedFrameworkMembers.call(method,thread,3,new String[0]);
        FixedFrameworkMembers.call(method,thread,3,new String[]{physicalPackage});
    }
    private PackageReplacementChecks(){}
}
