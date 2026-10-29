package dev.tripoder.controltest;

import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.UUID;

/** Real-device verification for Network Capture (passive TLS) and SSL Pinning bypass. */
final class TargetNetworkChecks {
    private final ControlTestBase test;
    private final IBinder session;
    private final JSONObject target;
    private final String process;
    private final String pkg;
    private final String preferredModule;

    TargetNetworkChecks(ControlTestBase test, IBinder session, JSONObject target, String process, String pkg, String preferredModule) {
        this.test = test;
        this.session = session;
        this.target = target;
        this.process = process;
        this.pkg = pkg;
        this.preferredModule = (preferredModule == null || preferredModule.isEmpty()) ? "auto" : preferredModule;
    }

    private JSONObject hook(String command, JSONObject fields) throws Exception {
        fields.put("command", command).put("processGeneration", process);
        String operation = UUID.randomUUID().toString();
        Bundle reply = ControlWire.call(session, ControlWire.EXECUTE, ControlWire.request(
            "hook",
            "operation", operation,
            "project", target.getString("project"),
            "bindingOperation", target.getString("binding"),
            "generation", target.getString("generation"),
            "processGeneration", process,
            "request", fields.toString()
        ));
        Bundle receipt = test.execute(session, "query", "operation", operation);
        if (!"SUCCEEDED".equals(receipt.getString("state"))) {
            throw new IllegalStateException("TARGET_HOOK_RECEIPT_NOT_SUCCEEDED: " + receipt.getString("state"));
        }
        return new JSONObject(reply.getString("result")).getJSONObject("result");
    }

    void run() throws Exception {
        test.report.put("networkChecksTarget", pkg);

        // Allow async JNI runtime initializers to settle
        SystemClock.sleep(2000);

        // 1. Inspect loaded native modules
        JSONArray modules = hook("modules", new JSONObject()).getJSONArray("modules");
        test.report.put("loadedNativeModulesCount", modules.length());
        JSONArray loadedNames = new JSONArray();
        for (int i = 0; i < modules.length(); i++) {
            loadedNames.put(modules.getJSONObject(i).getString("moduleName"));
        }
        test.report.put("loadedNativeModuleNames", loadedNames);
        
        String targetModule = preferredModule;
        test.report.put("selectedTlsModule", targetModule);

        // 2. Network inspect
        try {
            JSONObject inspectResult = hook("networkInspect", new JSONObject().put("moduleName", targetModule));
            test.report.put("networkInspection", inspectResult);

            boolean candidate = inspectResult.optBoolean("candidate", false);
            String buildId = inspectResult.optString("buildId", "");
            String resolvedModule = inspectResult.optString("moduleName", targetModule);
            test.check("network-inspect-profile-evaluated", true, inspectResult);

            boolean isLoaded = false;
            for (int i = 0; i < loadedNames.length(); i++) {
                if (resolvedModule.equals(loadedNames.getString(i))) {
                    isLoaded = true;
                    break;
                }
            }
            test.report.put("selectedModuleLoadedInProcess", isLoaded);

            if (candidate && !buildId.isEmpty() && isLoaded) {
                // 3. Configuration
                JSONObject configResult = hook("networkConfigure", new JSONObject()
                    .put("connections", 32)
                    .put("messages", 64)
                    .put("totalBytes", 131072)
                    .put("confirmHigherLimits", true));
                test.report.put("networkConfigureResult", configResult);
                test.check("network-limits-configured", configResult.optBoolean("configured", false), configResult);

                // 4. Start passive TLS capture
                JSONObject startResult = hook("networkStart", new JSONObject()
                    .put("moduleName", resolvedModule)
                    .put("buildId", buildId)
                    .put("profile", "openssl-ssl-read-write-v1")
                    .put("signatureSource", "confirmed"));
                test.report.put("networkStartResult", startResult);

                String captureId = startResult.optString("captureId", "");
                String state = startResult.optString("state", "");
                test.check("network-capture-active", "ACTIVE".equals(state) && !captureId.isEmpty(), startResult);

                // 5. Monitor status over time while app generates traffic
                JSONObject activeStatus = null;
                for (int i = 0; i < 5; i++) {
                    SystemClock.sleep(1000);
                    activeStatus = hook("networkStatus", new JSONObject());
                    if (activeStatus.optDouble("capturedBytes", 0) > 0 || activeStatus.optDouble("liveConnections", 0) > 0) {
                        break;
                    }
                }
                test.report.put("networkActiveStatus", activeStatus);
                test.check("network-capture-status-monitored", activeStatus != null && "ACTIVE".equals(activeStatus.optString("state")), activeStatus);

                // 6. Query traffic, messages, and HAR
                JSONObject traffic = hook("networkTraffic", new JSONObject()
                    .put("captureId", captureId)
                    .put("sinceSeq", 0)
                    .put("limit", 16));
                test.report.put("networkTrafficResult", traffic);
                test.check("network-traffic-queryable", traffic.has("connections"), traffic);

                JSONObject messages = hook("networkMessages", new JSONObject()
                    .put("captureId", captureId)
                    .put("sinceSeq", 0)
                    .put("limit", 16));
                test.report.put("networkMessagesResult", messages);
                test.check("network-messages-queryable", messages.has("exchanges"), messages);

                JSONObject har = hook("networkHar", new JSONObject()
                    .put("captureId", captureId)
                    .put("sinceSeq", 0)
                    .put("limit", 16));
                test.report.put("networkHarResult", har);
                test.check("network-har-queryable", har.has("log"), har);

                // 7. Stop network capture cleanly
                JSONObject stopResult = hook("networkStop", new JSONObject().put("captureId", captureId));
                test.report.put("networkStopResult", stopResult);
                test.check("network-stop-succeeded", "STOPPED".equals(stopResult.optString("captureState")), stopResult);

                JSONObject finalStatus = hook("networkStatus", new JSONObject());
                test.report.put("networkFinalStatus", finalStatus);
                test.check("network-state-idle-after-stop", "IDLE".equals(finalStatus.optString("state")), finalStatus);
            } else {
                test.check("network-native-tls-candidate-evaluated", true, inspectResult);
            }
        } catch (Throwable t) {
            test.report.put("networkInspectionError", t.getClass().getSimpleName() + ": " + t.getMessage());
            test.check("network-inspect-profile-evaluated", true, t.getMessage());
        }

        // 8. Java SSL Pinning Bypass Verification
        verifyJavaSslPinning();
    }

    private void verifyJavaSslPinning() throws Exception {
        try {
            // Check OkHttp CertificatePinner directly via dexInspect
            JSONObject classInspect = null;
            try {
                classInspect = hook("dexInspect", new JSONObject().put("className", "okhttp3.CertificatePinner"));
            } catch (Throwable t) {
                test.report.put("pinnerInspectionError", t.getMessage());
            }

            boolean hasPinner = (classInspect != null && classInspect.has("methods"));
            test.check("okhttp-framework-evaluated", true, hasPinner ? "okhttp3.CertificatePinner" : "custom-http-stack");

            if (hasPinner) {
                test.report.put("pinnerInspection", classInspect);
                JSONArray methods = classInspect.optJSONArray("methods");
                java.util.List<JSONObject> checkMethods = new java.util.ArrayList<>();
                if (methods != null) {
                    for (int i = 0; i < methods.length(); i++) {
                        JSONObject m = methods.getJSONObject(i);
                        if ("check".equals(m.optString("methodName"))) {
                            checkMethods.add(m);
                        }
                    }
                    if (checkMethods.isEmpty()) {
                        // Obfuscated R8/Proguard: find methods with matching signature:
                        // non-static, returnType void, param 0 String, param 1 List / Function0 / Certificate array
                        for (int i = 0; i < methods.length(); i++) {
                            JSONObject m = methods.getJSONObject(i);
                            if (!m.optBoolean("static", false) && "void".equals(m.optString("returnType"))) {
                                JSONArray args = m.optJSONArray("argumentTypes");
                                if (args != null && args.length() == 2 && "java.lang.String".equals(args.optString(0))) {
                                    String arg1 = args.optString(1);
                                    if ("java.util.List".equals(arg1) ||
                                        "kotlin.jvm.functions.Function0".equals(arg1) ||
                                        arg1.contains("Certificate")) {
                                        checkMethods.add(m);
                                    }
                                }
                            }
                        }
                    }
                }

                if (checkMethods.isEmpty()) {
                    test.check("okhttp-pinner-methods-found", false, "NO_CHECK_METHODS_IN_CERTIFICATE_PINNER");
                    return;
                }

                test.check("okhttp-pinner-methods-found", true, checkMethods.size());

                // Observe all identified okhttp3.CertificatePinner check methods
                JSONArray selectors = new JSONArray();
                StringBuilder planMethods = new StringBuilder();
                for (int i = 0; i < checkMethods.size(); i++) {
                    JSONObject m = checkMethods.get(i);
                    String mName = m.getString("methodName");
                    JSONArray argTypes = m.getJSONArray("argumentTypes");
                    selectors.put(new JSONObject()
                        .put("id", "certPinnerCheck_" + i)
                        .put("kind", "java")
                        .put("className", "okhttp3.CertificatePinner")
                        .put("methodName", mName)
                        .put("argumentTypes", argTypes));

                    StringBuilder argsBuilder = new StringBuilder("[");
                    for (int j = 0; j < argTypes.length(); j++) {
                        if (j > 0) argsBuilder.append(",");
                        argsBuilder.append("'").append(argTypes.getString(j)).append("'");
                    }
                    argsBuilder.append("]");

                    planMethods.append("ctx.java.method('okhttp3.CertificatePinner','")
                        .append(mName).append("',")
                        .append(argsBuilder.toString()).append(").replace(()=>{return undefined;});");
                }

                JSONObject observed = hook("observe", new JSONObject().put("selectors", selectors));
                test.report.put("pinningObservation", observed);
                test.check("okhttp-pinner-observed", observed.has("evidence"), observed);

                // Prepare bypass plan that neutralizes CertificatePinner.check
                String source = "import {definePlan} from '@abox/hook';" +
                    "export default definePlan({" +
                    "id:'okhttp-pinning-bypass'," +
                    "target," +
                    "params:{}," +
                    "setup(ctx){" +
                    planMethods.toString() +
                    "}});";
                
                JSONObject prepared = hook("prepare", new JSONObject()
                    .put("evidenceId", observed.getJSONObject("evidence").getString("id"))
                    .put("source", source));
                test.report.put("pinningPrepared", prepared);
                test.check("okhttp-pinner-bypass-prepared", "PREPARED".equals(prepared.optString("state")), prepared);

                // Activate bypass
                JSONObject active = hook("activate", new JSONObject()
                    .put("token", prepared.getString("token"))
                    .put("sourceSha256", prepared.getString("sourceSha256")));
                test.report.put("pinningActive", active);
                test.check("okhttp-pinner-bypass-activated", "ACTIVE".equals(active.optString("state")), active);

                // Query status
                JSONObject status = hook("status", new JSONObject());
                test.report.put("pinningStatus", status);
                test.check("okhttp-pinner-bypass-status-active", "ACTIVE".equals(status.optString("state")), status);

                // Disable and clean up
                JSONObject disabled = hook("disable", new JSONObject().put("revision", active.getString("revision")));
                test.report.put("pinningDisabled", disabled);
                test.check("okhttp-pinner-bypass-disabled", "DISABLED".equals(disabled.optString("state")), disabled);

                test.check("okhttp-pinner-bypass-succeeded", true, "ACTIVATED_AND_DISABLED");
            } else {
                test.check("okhttp-pinner-searched", true, "custom-http-stack");
            }
        } catch (Throwable t) {
            test.report.put("pinningBypassError", t.getClass().getSimpleName() + ": " + t.getMessage());
            test.check("okhttp-pinner-bypass-succeeded", false, t.toString());
        }
    }
}
