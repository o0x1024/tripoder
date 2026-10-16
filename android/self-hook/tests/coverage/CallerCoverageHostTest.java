package dev.tripoder.selfhook.coverage;

import dev.tripoder.selfhook.dex.DexFile;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CallerCoverageHostTest {
    private static final String PREFIX = "Ldev/tripoder/coveragefixture/";
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
    private static void rejects(Runnable operation, String reason) {
        try { operation.run(); throw new AssertionError("not rejected: " + reason); }
        catch (IllegalArgumentException | IllegalStateException error) {
            check(error.getMessage().contains(reason), "wrong rejection: " + error);
        }
    }
    private static void immutable(Runnable operation) {
        try { operation.run(); throw new AssertionError("mutable analysis output"); }
        catch (UnsupportedOperationException expected) { checks++; }
    }
    private static Set<String> keys(CallerCoveragePlan plan) {
        Set<String> keys = new HashSet<>();
        for (DexFile.Definition candidate : plan.candidates) keys.add(candidate.method.key());
        return keys;
    }
    private static Map<String, Class<?>> classes(CallerCoveragePlan plan, ClassLoader loader) throws Exception {
        Map<String, Class<?>> result = new HashMap<>();
        List<DexFile.Definition> definitions = new ArrayList<>(plan.candidates);
        definitions.add(plan.target);
        for (DexFile.Definition definition : definitions) {
            String owner = definition.method.owner;
            result.put(owner, Class.forName(owner.substring(1, owner.length() - 1).replace('/', '.'), false, loader));
        }
        return result;
    }
    private static void identityAndSignatures(List<DexFile> files, URL fixture) throws Exception {
        try (URLClassLoader first = new URLClassLoader(new URL[]{fixture}, ClassLoader.getPlatformClassLoader());
             URLClassLoader second = new URLClassLoader(new URL[]{fixture}, ClassLoader.getPlatformClassLoader())) {
            String wide = "(ZBCSIJFD[[I[Ljava/lang/Object;)[Ljava/lang/Object;";
            CallerCoveragePlan plan = new CallerCoveragePlan(files, PREFIX + "Targets;->wide" + wide);
            Map<String, Class<?>> supplied = classes(plan, first);
            CallerMethodBindings binding = new CallerMethodBindings(plan, first, supplied);
            check(binding.rejections.isEmpty(), "wide bindings");
            check(binding.target.method.getDeclaringClass().getClassLoader() == first, "target defining loader");
            observationPayload(binding);
            Method[] methods = binding.scopedProbeCallers(64);
            check(methods.length == 1 && CallerMethodBindings.descriptor(methods[0]).equals(wide), "all primitive and array descriptors");
            check(System.getProperty("tripoder.coverage.initialized") == null, "binding does not initialize target");
            methods[0] = null;
            check(binding.scopedProbeCallers(64)[0] != null, "caller array defensive copy");
            rejects(() -> binding.scopedProbeCallers(0), "COVERAGE_CALLER_BUDGET");
            rejects(binding::requireCompleteRuntimeCoverage, "CALLER_RUNTIME_COVERAGE_UNPROVEN");
            CallerMethodBindings wrong = new CallerMethodBindings(plan, first, classes(plan, second));
            check(wrong.rejections.stream().allMatch(r -> r.reason.equals("DEFINING_LOADER_MISMATCH")), "same names different defining loader rejected");
            rejects(() -> wrong.scopedProbeCallers(64), "COVERAGE_METHOD_BINDING_REJECTED");
            Map<String, Class<?>> absent = new HashMap<>(supplied);
            absent.remove(plan.candidates.get(0).method.owner);
            CallerMethodBindings incomplete = new CallerMethodBindings(plan, first, absent);
            check(incomplete.rejections.stream().anyMatch(r -> r.reason.equals("CLASS_IDENTITY_NOT_SUPPLIED")), "missing class is not implicitly loaded");
            Map<String, Class<?>> mismatched = new HashMap<>(supplied);
            mismatched.put(plan.target.method.owner, String.class);
            CallerMethodBindings misbound = new CallerMethodBindings(plan, first, mismatched);
            check(misbound.rejections.stream().anyMatch(r -> r.reason.equals("CLASS_DESCRIPTOR_MISMATCH")), "descriptor mapping cannot forge class identity");
            immutable(() -> binding.callers.clear());
            immutable(() -> incomplete.rejections.clear());
            rejects(() -> new CallerMethodBindings(plan, null, supplied), "OWNED_LOADER_BINDINGS_REQUIRED");
            supplied.clear();
            check(binding.scopedProbeCallers(64).length == 1, "external map mutation cannot change binding");

            CallerCoveragePlan constructor = new CallerCoveragePlan(files, PREFIX + "Targets;->constructorTarget(I)I");
            check(constructor.gaps.contains(CallerCoveragePlan.Gap.CONSTRUCTOR_OR_CLASS_INITIALIZER_CALLER), "constructor cannot be silently omitted");
            CallerMethodBindings unsupported = new CallerMethodBindings(constructor, first, classes(constructor, first));
            check(unsupported.rejections.stream().anyMatch(r -> r.reason.equals("INITIALIZER_CALLER_UNSUPPORTED")), "constructor Method array rejection");
            rejects(() -> unsupported.scopedProbeCallers(64), "COVERAGE_METHOD_BINDING_REJECTED");

            CallerCoveragePlan bridge = new CallerCoveragePlan(files, PREFIX + "Dispatch$Bridge;->result()Ljava/lang/Object;");
            CallerMethodBindings bridges = new CallerMethodBindings(bridge, first, classes(bridge, first));
            check(bridges.target != null && bridges.target.method.isBridge()
                && bridges.target.method.getReturnType() == Object.class, "return type selects bridge identity");
            CallerCoveragePlan concrete = new CallerCoveragePlan(files, PREFIX + "Dispatch$Bridge;->result()Ljava/lang/String;");
            CallerMethodBindings concretes = new CallerMethodBindings(concrete, first, classes(concrete, first));
            check(concretes.target != null && !concretes.target.method.isBridge()
                && concretes.target.method.getReturnType() == String.class, "concrete covariant return identity");
        }
    }

    private static void observationPayload(CallerMethodBindings binding) {
        List<CallerMethodBindings.BoundMethod> all = new ArrayList<>();
        all.add(binding.target); all.addAll(binding.callers);
        long[] payload = new long[4 + all.size() * 8];
        payload[0] = 0x5450434f56455231L; payload[1] = 1; payload[2] = 7; payload[3] = all.size();
        for (int i = 0; i < all.size(); i++) {
            int at = 4 + i * 8;
            payload[at] = 0x1000 + 32L * i;
            payload[at + 1] = payload[at + 7] = 0x2000 + 8L * i;
            payload[at + 2] = all.get(i).method.getModifiers();
            payload[at + 3] = all.get(i).source.index;
            payload[at + 4] = 0xf0000000L; // Mutable hotness word is an observation, not semantic identity.
            payload[at + 5] = 0x3000; payload[at + 6] = 0x4000;
        }
        MethodObservation parsed = MethodObservation.decode(binding, payload);
        check(parsed.methods.size() == all.size() && parsed.observationSequence == 7, "snapshot wire parsing only");
        check(!parsed.completeLoadedInventory && !parsed.completeRuntimeCoverage, "supplied methods are never all loaded methods");
        immutable(() -> parsed.methods.clear());
        long[] wrong = payload.clone(); wrong[3]++;
        final long[] badCount = wrong;
        rejects(() -> MethodObservation.decode(binding, badCount), "OBSERVATION_HEADER");
        wrong = payload.clone(); wrong[7]++;
        final long[] badIndex = wrong;
        rejects(() -> MethodObservation.decode(binding, badIndex), "OBSERVATION_DEX_INDEX");
        wrong = payload.clone(); wrong[11]++;
        final long[] badOwner = wrong;
        rejects(() -> MethodObservation.decode(binding, badOwner), "OBSERVATION_OWNER_IDENTITY");
        wrong = payload.clone(); wrong[6] ^= java.lang.reflect.Modifier.STATIC;
        final long[] badFlags = wrong;
        rejects(() -> MethodObservation.decode(binding, badFlags), "OBSERVATION_METHOD_FLAGS");
        wrong = payload.clone(); wrong[12] = wrong[4];
        final long[] duplicate = wrong;
        rejects(() -> MethodObservation.decode(binding, duplicate), "OBSERVATION_METHOD_IDENTITY");
        wrong = payload.clone(); wrong[10] = 0;
        final long[] badEntry = wrong;
        rejects(() -> MethodObservation.decode(binding, badEntry), "OBSERVATION_ENTRY");
        rejects(() -> MethodObservation.decode(binding, null), "OBSERVATION_SIZE");
        long original = parsed.methods.get(0).methodToken;
        payload[4] = 0;
        check(parsed.methods.get(0).methodToken == original, "snapshot copied from wire array");
    }

    public static void main(String[] args) throws Exception {
        System.clearProperty("tripoder.coverage.initialized");
        DexFile first = new DexFile(Files.readAllBytes(Path.of(args[0])));
        DexFile second = new DexFile(Files.readAllBytes(Path.of(args[1])));
        List<DexFile> files = Arrays.asList(first, second);
        String target = PREFIX + "Targets;->scalar(I)I";
        CallerCoveragePlan plan = new CallerCoveragePlan(files, target);
        Set<String> candidates = keys(plan);
        check(plan.dexDigests.size() == 2, "multi DEX input");
        check(candidates.contains(PREFIX + "CrossDex;->entry(I)I")
            && candidates.contains(PREFIX + "CrossDex;->ancestor(I)I"), "cross DEX transitive ancestors");
        check(candidates.contains(PREFIX + "Dispatch;->interfaceCall(Ldev/tripoder/coveragefixture/Dispatch$Contract;I)I"), "interface candidate");
        check(candidates.contains(PREFIX + "Dispatch;->virtualCall(Ldev/tripoder/coveragefixture/Dispatch$Base;I)I"), "virtual candidate");
        check(candidates.contains(PREFIX + "Dispatch$Child;->run(I)I"), "super candidate");
        check(candidates.contains(PREFIX + "Dispatch;->unrelatedCall(Ldev/tripoder/coveragefixture/Dispatch$Unrelated;I)I"), "over approximation remains explicit");
        check(plan.exactStaticAncestors.stream().noneMatch(m -> m.name.equals("unrelatedCall")), "exact static edges distinguish over approximation");
        check(plan.gaps.containsAll(Arrays.asList(CallerCoveragePlan.Gap.REFLECTION, CallerCoveragePlan.Gap.METHOD_HANDLE,
            CallerCoveragePlan.Gap.INVOKE_CUSTOM, CallerCoveragePlan.Gap.CLASS_LOADING,
            CallerCoveragePlan.Gap.NATIVE_DECLARATION, CallerCoveragePlan.Gap.EXTERNAL_METHOD_REFERENCE)), "dynamic and external reasons retained");
        check(plan.nativeDeclarations.size() == 1 && plan.nativeDeclarations.get(0).name.equals("nativeEntry"), "native source identity retained");
        check(plan.sites.stream().anyMatch(s -> s.caller.name.equals("reflection") && s.gap == CallerCoveragePlan.Gap.REFLECTION), "unrelated reflection site audited");
        check(plan.sites.stream().anyMatch(s -> s.caller.name.equals("handle") && (s.opcode == 0xfa || s.opcode == 0xfb)), "real invoke polymorphic bytecode");
        check(plan.sites.stream().anyMatch(s -> s.caller.name.equals("custom") && (s.opcode == 0xfc || s.opcode == 0xfd)), "real invoke custom bytecode");
        check(plan.sites.stream().allMatch(s -> s.codeUnit >= 0 && s.caller.dexSha256.length() == 64), "site source provenance");
        check(!plan.completeRuntimeCoverage, "candidate closure is never runtime proof");
        immutable(() -> plan.candidates.clear());
        immutable(() -> plan.dexDigests.clear());
        immutable(() -> plan.gaps.clear());
        immutable(() -> plan.sites.clear());
        immutable(() -> plan.nativeDeclarations.clear());
        rejects(plan::requireCompleteRuntimeCoverage, "CALLER_RUNTIME_COVERAGE_UNPROVEN");
        check(new CallerCoveragePlan(Arrays.asList(second, first), target).analysisDigest.equals(plan.analysisDigest), "canonical analysis identity");
        rejects(() -> new CallerCoveragePlan(Arrays.asList(first, first), target), "DUPLICATE");
        rejects(() -> new CallerCoveragePlan(files, PREFIX + "Targets;->nativeEntry(I)I"), "MANAGED_TARGET_REQUIRED");
        rejects(() -> new CallerCoveragePlan(files, "Lmissing;->target()V"), "MANAGED_TARGET_REQUIRED");
        identityAndSignatures(files, Path.of(args[2]).toUri().toURL());
        System.out.println("{\"checks\":" + checks + ",\"dexFiles\":2,\"candidateCount\":" + plan.candidates.size()
            + ",\"unresolvedSites\":" + plan.sites.size() + ",\"analysisDigest\":\"" + plan.analysisDigest
            + "\",\"completeRuntimeCoverage\":false,\"productActivation\":\"rejected\"}");
    }
}
