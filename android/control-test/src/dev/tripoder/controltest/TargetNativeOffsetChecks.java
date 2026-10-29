package dev.tripoder.controltest;

import android.os.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.UUID;

/** Real-device verification for Native Stripped SO Relative Offset Hooking. */
final class TargetNativeOffsetChecks {
    private final ControlTestBase test;
    private final IBinder session;
    private final JSONObject target;
    private final String process;
    private final String pkg;
    private final String preferredModule;

    TargetNativeOffsetChecks(ControlTestBase test, IBinder session, JSONObject target, String process, String pkg, String preferredModule) {
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
        test.report.put("nativeOffsetChecksTarget", pkg);

        // Settle runtime
        SystemClock.sleep(2000);

        JSONObject modulesResult = hook("modules", new JSONObject());
        JSONArray modules = modulesResult.getJSONArray("modules");
        test.report.put("nativeModulesCount", modules.length());

        JSONObject chosenModule = null;
        for (int i = 0; i < modules.length(); i++) {
            JSONObject mod = modules.getJSONObject(i);
            String name = mod.optString("moduleName", mod.optString("name", ""));
            String state = mod.optString("state", "");
            if ("AVAILABLE".equals(state) && name.startsWith("lib") && name.endsWith(".so")) {
                if (!"auto".equals(preferredModule) && name.equals(preferredModule)) {
                    chosenModule = mod;
                    break;
                }
                if (chosenModule == null && !name.contains("tripoder")) {
                    chosenModule = mod;
                }
            }
        }

        if (chosenModule == null) {
            test.check("native-offset-module-available", false, "No available native module found");
            return;
        }

        String modName = chosenModule.getString("moduleName");
        String buildId = chosenModule.getString("buildId");
        test.report.put("nativeOffsetChosenModule", modName);
        test.check("native-offset-module-selected", true, modName);

        // Find an export to discover a valid code RVA in this module
        JSONObject exportsResult = hook("nativeExports", new JSONObject().put("moduleName", modName).put("symbolPrefix", "").put("limit", 10));
        JSONArray exports = exportsResult.optJSONArray("exports");
        if (exports == null || exports.length() == 0) {
            // Try with 'JNI_' or 'Java_' prefix
            exportsResult = hook("nativeExports", new JSONObject().put("moduleName", modName).put("symbolPrefix", "JNI_").put("limit", 5));
            exports = exportsResult.optJSONArray("exports");
        }

        if (exports == null || exports.length() == 0) {
            test.check("native-offset-export-discovered", false, "No exports found in module " + modName);
            return;
        }

        JSONObject sampleExport = exports.getJSONObject(0);
        String exportSymbol = sampleExport.getString("symbol");
        String exportAddress = sampleExport.optString("rva", sampleExport.optString("address", "")); // e.g. "0x12a0"
        long rva = Long.parseUnsignedLong(exportAddress.startsWith("0x") || exportAddress.startsWith("0X") ? exportAddress.substring(2) : exportAddress, 16);
        test.report.put("nativeOffsetTargetExport", sampleExport);
        test.check("native-offset-rva-valid", rva > 0 && (rva & 3) == 0, exportAddress);

        String hexRva = "0x" + Long.toHexString(rva);
        String subName = "sub_" + Long.toHexString(rva);

        // Test 1: Observe via explicit offset field and unexported subroutine name
        JSONArray selectors = new JSONArray().put(
            new JSONObject()
                .put("id", "offset_target")
                .put("kind", "native")
                .put("moduleName", modName)
                .put("symbol", subName)
                .put("offset", hexRva)
                .put("argumentTypes", new JSONArray().put("uint64"))
                .put("returnType", "uint64")
                .put("buildId", buildId)
                .put("signatureSource", "confirmed")
        );

        JSONObject observed = hook("observe", new JSONObject().put("selectors", selectors));
        test.report.put("nativeOffsetObservation", observed);
        JSONArray observedSymbols = observed.getJSONObject("evidence").getJSONArray("symbols");
        test.check("native-offset-observed-count", observedSymbols.length() == 1, observedSymbols.length());
        JSONObject obsSym = observedSymbols.getJSONObject(0);
        test.check("native-offset-symbol-loaded", obsSym.getBoolean("loaded"), obsSym);
        test.check("native-offset-symbol-offset-present", hexRva.equalsIgnoreCase(obsSym.optString("offset")), obsSym.optString("offset"));

        // Test 2: Prepare with ctx.native.offset
        String sourceOffset = "import {definePlan} from '@abox/hook';\n"
            + "export default definePlan({\n"
            + "  id: 'native-offset-plan',\n"
            + "  target,\n"
            + "  params: {},\n"
            + "  setup(ctx) {\n"
            + "    ctx.native.offset('" + modName + "', '" + hexRva + "', {\n"
            + "      argumentTypes: ['uint64'],\n"
            + "      returnType: 'uint64',\n"
            + "      buildId: '" + buildId + "'\n"
            + "    }).after(({result}) => result);\n"
            + "  }\n"
            + "});";

        JSONObject prepared = hook("prepare", new JSONObject()
            .put("evidenceId", observed.getJSONObject("evidence").getString("id"))
            .put("source", sourceOffset));
        test.check("native-offset-plan-prepared", "PREPARED".equals(prepared.getString("state")), prepared);

        // Test 3: Activate hook
        JSONObject active = hook("activate", new JSONObject()
            .put("token", prepared.getString("token"))
            .put("sourceSha256", prepared.getString("sourceSha256")));
        test.check("native-offset-hook-activated", "ACTIVE".equals(active.getString("state")), active);
        String revision = active.getString("revision");

        // Verify status
        JSONObject status = hook("status", new JSONObject());
        test.check("native-offset-status-active", "ACTIVE".equals(status.getString("state")), status);

        // Test 4: Disable hook
        JSONObject disabled = hook("disable", new JSONObject().put("revision", revision));
        test.check("native-offset-hook-disabled", "DISABLED".equals(disabled.getString("state")), disabled);

        // Test 5: Prepare with ctx.native.function matching sub_ name
        String sourceSub = "import {definePlan} from '@abox/hook';\n"
            + "export default definePlan({\n"
            + "  id: 'native-sub-plan',\n"
            + "  target,\n"
            + "  params: {},\n"
            + "  setup(ctx) {\n"
            + "    ctx.native.function('" + modName + "', '" + subName + "', {\n"
            + "      argumentTypes: ['uint64'],\n"
            + "      returnType: 'uint64',\n"
            + "      buildId: '" + buildId + "'\n"
            + "    }).after(({result}) => result);\n"
            + "  }\n"
            + "});";

        // Test 5: Re-observe for sub_ plan after disable
        JSONObject observedSub = hook("observe", new JSONObject().put("selectors", selectors));
        JSONObject preparedSub = hook("prepare", new JSONObject()
            .put("evidenceId", observedSub.getJSONObject("evidence").getString("id"))
            .put("source", sourceSub));
        test.check("native-sub-name-plan-prepared", "PREPARED".equals(preparedSub.getString("state")), preparedSub);

        // Activate and disable sub_ plan
        JSONObject activeSub = hook("activate", new JSONObject()
            .put("token", preparedSub.getString("token"))
            .put("sourceSha256", preparedSub.getString("sourceSha256")));
        test.check("native-sub-name-activated", "ACTIVE".equals(activeSub.getString("state")), activeSub);
        JSONObject disabledSub = hook("disable", new JSONObject().put("revision", activeSub.getString("revision")));
        test.check("native-sub-name-disabled", "DISABLED".equals(disabledSub.getString("state")), disabledSub);

        // Test 6: Negative verification - unaligned offset rejected
        JSONArray badUnaligned = new JSONArray().put(
            new JSONObject()
                .put("id", "bad_align")
                .put("kind", "native")
                .put("moduleName", modName)
                .put("symbol", "sub_bad")
                .put("offset", "0x" + Long.toHexString(rva | 1))
                .put("argumentTypes", new JSONArray().put("uint64"))
                .put("returnType", "uint64")
                .put("buildId", buildId)
                .put("signatureSource", "confirmed")
        );
        try {
            hook("observe", new JSONObject().put("selectors", badUnaligned));
            test.check("native-offset-unaligned-rejected", false, "Unaligned offset accepted");
        } catch (Exception expected) {
            test.check("native-offset-unaligned-rejected", true, expected.getMessage());
        }

        test.report.put("nativeOffsetChecksCompleted", true);
    }
}
