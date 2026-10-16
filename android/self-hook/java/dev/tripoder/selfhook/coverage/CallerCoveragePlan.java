package dev.tripoder.selfhook.coverage;

import dev.tripoder.selfhook.dex.DexCallGraph;
import dev.tripoder.selfhook.dex.DexCallerClosure;
import dev.tripoder.selfhook.dex.DexCode;
import dev.tripoder.selfhook.dex.DexFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable DEX analysis, deliberately not a runtime activation certificate. */
public final class CallerCoveragePlan {
    public enum Gap {
        DYNAMIC_DISPATCH_CANDIDATES,
        REFLECTION,
        METHOD_HANDLE,
        INVOKE_CUSTOM,
        CLASS_LOADING,
        EXTERNAL_METHOD_REFERENCE,
        NATIVE_DECLARATION,
        CONSTRUCTOR_OR_CLASS_INITIALIZER_CALLER,
        NON_EXECUTABLE_CALLER,
        DEX_TO_RUNTIME_IDENTITY,
        LOADED_BOOT_AND_APPLICATION_CODE,
        JIT_AOT_INLINE_AND_DIRECT_CALLS,
        FUTURE_CLASS_AND_CODE_PUBLICATION,
        IN_FLIGHT_FRAMES,
        ATOMIC_INSTALL_AND_INVALIDATION
    }

    public static final class Site {
        public final DexFile.MethodRef caller;
        public final DexFile.MethodRef referencedMethod;
        public final int codeUnit, opcode;
        public final Gap gap;
        private Site(DexFile.MethodRef caller, DexCode.Call call, Gap gap) {
            this.caller = caller;
            referencedMethod = call.target;
            codeUnit = call.codeUnit;
            opcode = call.opcode;
            this.gap = gap;
        }
    }

    public final DexFile.Definition target;
    public final List<DexFile.Definition> candidates;
    public final List<DexFile.MethodRef> exactStaticAncestors;
    public final List<String> dexDigests;
    public final List<DexFile.MethodRef> nativeDeclarations;
    public final List<Site> sites;
    public final Set<Gap> gaps;
    public final String analysisDigest;
    public final boolean completeRuntimeCoverage = false;

    public CallerCoveragePlan(List<DexFile> files, String targetKey) {
        require(files != null && !files.isEmpty() && files.size() <= 64, "COVERAGE_DEX_SET");
        require(targetKey != null, "COVERAGE_TARGET");
        List<DexFile> snapshot = new ArrayList<>(files);
        Map<String, DexFile.Definition> definitions = new LinkedHashMap<>();
        for (DexFile file : snapshot) {
            require(file != null, "COVERAGE_NULL_DEX");
            for (DexFile.Definition definition : file.definitions) {
                require(definitions.put(definition.method.key(), definition) == null, "COVERAGE_DUPLICATE_DEFINITION");
                require(definitions.size() <= 250_000, "COVERAGE_DEFINITION_BUDGET");
            }
        }
        target = definitions.get(targetKey);
        require(target != null && !target.method.name.startsWith("<")
            && (target.flags & (0x100 | 0x400)) == 0, "COVERAGE_MANAGED_TARGET_REQUIRED");

        // DexCallerClosure owns the work cap and now counts actual expansion.
        // A second signature-only estimate would reject exact static calls.
        DexCallerClosure graph = new DexCallerClosure(snapshot);
        ArrayList<String> digests = new ArrayList<>(graph.dexDigests);
        Collections.sort(digests);
        dexDigests = Collections.unmodifiableList(digests);
        ArrayList<DexFile.Definition> selected = new ArrayList<>();
        for (DexFile.MethodRef method : graph.candidates(targetKey)) selected.add(definitions.get(method.key()));
        selected.sort(Comparator.comparing(definition -> definition.method.key()));
        candidates = Collections.unmodifiableList(selected);
        List<DexFile.MethodRef> staticEdges = (target.flags & 8) != 0
            ? new ArrayList<>(new DexCallGraph(snapshot).staticAncestors(targetKey)) : new ArrayList<>();
        staticEdges.sort(Comparator.comparing(DexFile.MethodRef::key));
        exactStaticAncestors = Collections.unmodifiableList(staticEdges);

        EnumSet<Gap> missing = EnumSet.of(Gap.DEX_TO_RUNTIME_IDENTITY,
            Gap.LOADED_BOOT_AND_APPLICATION_CODE, Gap.JIT_AOT_INLINE_AND_DIRECT_CALLS,
            Gap.FUTURE_CLASS_AND_CODE_PUBLICATION, Gap.IN_FLIGHT_FRAMES,
            Gap.ATOMIC_INSTALL_AND_INVALIDATION);
        ArrayList<Site> hazards = new ArrayList<>();
        ArrayList<DexFile.MethodRef> natives = new ArrayList<>();
        // Dynamic invocation can name the target without a DEX edge. Inspect the
        // whole supplied artifact set; filtering to ancestors would hide it.
        for (DexFile.Definition definition : definitions.values()) {
            if ((definition.flags & 0x100) != 0) {
                missing.add(Gap.NATIVE_DECLARATION);
                natives.add(definition.method);
            }
            for (DexCode.Call call : definition.calls) {
                if (call.target == null) {
                    add(hazards, missing, definition.method, call,
                        call.opcode == 0xfe ? Gap.METHOD_HANDLE : Gap.INVOKE_CUSTOM);
                    continue;
                }
                String owner = call.target.owner;
                if (call.opcode == 0xfa || call.opcode == 0xfb || owner.startsWith("Ljava/lang/invoke/"))
                    add(hazards, missing, definition.method, call, Gap.METHOD_HANDLE);
                if (owner.startsWith("Ljava/lang/reflect/") || (owner.equals("Ljava/lang/Class;")
                    && (call.target.name.startsWith("getMethod") || call.target.name.startsWith("getDeclaredMethod"))))
                    add(hazards, missing, definition.method, call, Gap.REFLECTION);
                if (owner.equals("Ljava/lang/ClassLoader;") || owner.startsWith("Ldalvik/system/")
                    || (owner.equals("Ljava/lang/Class;") && call.target.name.equals("forName")))
                    add(hazards, missing, definition.method, call, Gap.CLASS_LOADING);
                if (!definitions.containsKey(call.target.key()))
                    add(hazards, missing, definition.method, call, Gap.EXTERNAL_METHOD_REFERENCE);
                if (call.opcode == 0x6e || call.opcode == 0x6f || call.opcode == 0x72
                    || call.opcode == 0x74 || call.opcode == 0x75 || call.opcode == 0x78)
                    add(hazards, missing, definition.method, call, Gap.DYNAMIC_DISPATCH_CANDIDATES);
            }
        }
        for (DexFile.Definition candidate : candidates) {
            if (candidate.method.name.startsWith("<")) missing.add(Gap.CONSTRUCTOR_OR_CLASS_INITIALIZER_CALLER);
            if ((candidate.flags & (0x100 | 0x400)) != 0) missing.add(Gap.NON_EXECUTABLE_CALLER);
        }
        hazards.sort(Comparator.comparing((Site s) -> s.caller.key()).thenComparingInt(s -> s.codeUnit)
            .thenComparing(s -> s.gap.name()));
        sites = Collections.unmodifiableList(hazards);
        natives.sort(Comparator.comparing(DexFile.MethodRef::key));
        nativeDeclarations = Collections.unmodifiableList(natives);
        gaps = Collections.unmodifiableSet(missing);
        StringBuilder identity = new StringBuilder("caller-coverage-v1\n").append(targetKey).append('\n');
        for (String digest : dexDigests) identity.append(digest).append('\n');
        for (DexFile.Definition candidate : candidates)
            identity.append(candidate.method.dexSha256).append(':').append(candidate.method.index)
                .append(':').append(candidate.method.key()).append('\n');
        analysisDigest = sha256(identity.toString());
    }

    /** No caller-supplied boolean can turn static analysis into runtime proof. */
    public void requireCompleteRuntimeCoverage() {
        throw new IllegalStateException("CALLER_RUNTIME_COVERAGE_UNPROVEN: " + gaps);
    }

    private static String signature(DexFile.MethodRef method) { return method.name + method.descriptor; }
    private static void add(List<Site> sites, Set<Gap> gaps, DexFile.MethodRef caller, DexCode.Call call, Gap gap) {
        gaps.add(gap);
        sites.add(new Site(caller, call, gap));
    }
    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
