package dev.tripoder.runtime.script;

import java.util.Set;

/** Query failure cannot make a runtime mutation uncertain. Shared worker/authority classification. */
public final class HookCommands {
    private static final Set<String> QUERIES=Set.of("status","modules","events","dexSearch","dexInspect","nativeExports","uiDump","diagnostics","networkTraffic","networkMessages","networkInspect","networkStatus","probeStatus","classLoaders","dexList","codeArtifacts","runtimeEvents","classInspect");
    public static boolean readOnly(String command){return QUERIES.contains(command);}
    private HookCommands(){}
}
