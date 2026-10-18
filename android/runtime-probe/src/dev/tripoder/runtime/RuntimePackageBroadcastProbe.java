package dev.tripoder.runtime;

/** Acceptance-only handler injection; production control has no such command. */
public final class RuntimePackageBroadcastProbe {
    public static String run()throws Exception{
        RuntimeEngine engine=RuntimeEngine.current;
        if(engine==null||!"dev.tripoder.p2".equals(engine.packageName)||!"dev.tripoder.runtimeprobe".equals(engine.host.getPackageName()))throw new SecurityException("PACKAGE_REPLACEMENT_FIXTURE_REQUIRED");
        dev.tripoder.selfhook.PackageReplacementChecks.dispatch(engine.host.getPackageName());
        return new org.json.JSONObject().put("workerPid",android.os.Process.myPid()).put("generation",engine.generation).put("physicalPackage",engine.host.getPackageName()).put("emptyAndOwnedHandled",true).toString();
    }
    private RuntimePackageBroadcastProbe(){}
}
