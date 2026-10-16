package dev.tripoder.selfhookprobe;

import dev.tripoder.selfhook.dex.DexCallGraph;
import dev.tripoder.selfhook.dex.DexFile;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single-APK static caller proof, bound to the probe's actual defining loader. */
final class DexCallerProbe {
    final Method[] callers;
    final JSONObject evidence;
    DexCallerProbe(String apkPath) throws Exception {
        byte[] bytes;
        try (ZipFile apk = new ZipFile(apkPath)) {
            ZipEntry entry = apk.getEntry("classes.dex");
            if (entry == null || entry.getSize() <= 0 || entry.getSize() > DexFile.MAX_BYTES || apk.getEntry("classes2.dex") != null)
                throw new IllegalStateException("CALLER_PROBE_REQUIRES_SINGLE_DEX");
            try (InputStream input = apk.getInputStream(entry)) {
                bytes = input.readNBytes(DexFile.MAX_BYTES + 1);
                if (bytes.length != entry.getSize()) throw new IllegalStateException("CALLER_DEX_SIZE");
            }
        }
        DexFile dex = new DexFile(bytes);
        DexCallGraph graph = new DexCallGraph(Collections.singletonList(dex));
        String target = "Ldev/tripoder/selfhookprobe/InlineFixture;->plusOne(I)I";
        List<DexFile.MethodRef> found = graph.staticAncestors(target);
        List<Method> resolved = new ArrayList<>(); JSONArray names = new JSONArray();
        ClassLoader loader = InlineFixture.class.getClassLoader();
        for (DexFile.MethodRef method : found) {
            // The discovery API returns exact DEX identity, not a global name.
            // This probe accepts only its already exercised int(int) callers.
            if (!method.descriptor.equals("(I)I") || method.name.startsWith("<"))
                throw new IllegalStateException("CALLER_PROBE_SIGNATURE_UNSUPPORTED:" + method.key());
            String name = method.owner.substring(1, method.owner.length() - 1).replace('/', '.');
            Class<?> owner = Class.forName(name, false, loader);
            if (owner.getClassLoader() != loader) throw new IllegalStateException("CALLER_LOADER_IDENTITY");
            Method reflected = owner.getDeclaredMethod(method.name, int.class);
            if (reflected.getReturnType() != int.class) throw new IllegalStateException("CALLER_RETURN_TYPE");
            resolved.add(reflected);
            names.put(new JSONObject().put("method", method.key()).put("dexIndex", method.index).put("dexSha256", method.dexSha256));
        }
        if (resolved.isEmpty()) throw new IllegalStateException("CALLER_GRAPH_EMPTY");
        callers = resolved.toArray(new Method[0]);
        evidence = new JSONObject().put("dexSha256", dex.sha256).put("definitions", graph.definitionCount())
            .put("target", target).put("callers", names).put("unresolvedDispatchSites", graph.unresolvedDispatchSites)
            .put("scope", "exact static edges in the owned single-DEX loader").put("completeRuntimeCoverage", false);
    }
}
