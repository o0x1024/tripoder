package dev.tripoder.selfhook.coverage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only fixed-platform observations of supplied, strongly rooted methods.
 * Tokens are instantaneous diagnostic values, never durable native handles.
 * Loading the native collector is the host's responsibility; no backend switch. */
public final class MethodObservation {
    private static final long MAGIC = 0x5450434f56455231L;
    private static final int HEADER = 4, WORDS = 8;
    public static final class ObservedMethod {
        public final CallerMethodBindings.BoundMethod binding;
        public final long methodToken, ownerToken, flags, dexIndex, indices, dataToken, quickEntryToken;
        private ObservedMethod(CallerMethodBindings.BoundMethod binding, long[] words, int offset) {
            this.binding = binding;
            methodToken = words[offset]; ownerToken = words[offset + 1]; flags = words[offset + 2];
            dexIndex = words[offset + 3]; indices = words[offset + 4];
            dataToken = words[offset + 5]; quickEntryToken = words[offset + 6];
        }
    }
    public final CallerMethodBindings bindings;
    /** Collector-local ordering only. This is not a Runtime operation generation. */
    public final long observationSequence;
    public final List<ObservedMethod> methods;
    public final boolean completeLoadedInventory = false;
    public final boolean completeRuntimeCoverage = false;

    private MethodObservation(CallerMethodBindings bindings, long sequence, List<ObservedMethod> methods) {
        this.bindings = bindings;
        observationSequence = sequence;
        this.methods = Collections.unmodifiableList(methods);
    }
    private static native long[] captureNative(Method[] methods, Class<?>[] owners);

    public static MethodObservation capture(CallerMethodBindings bindings) {
        if (bindings == null) throw new IllegalArgumentException("COVERAGE_BINDINGS_REQUIRED");
        bindings.scopedProbeCallers(64);
        List<CallerMethodBindings.BoundMethod> all = ordered(bindings);
        Method[] methods = new Method[all.size()];
        Class<?>[] owners = new Class<?>[all.size()];
        for (int i = 0; i < all.size(); i++) {
            methods[i] = all.get(i).method;
            owners[i] = methods[i].getDeclaringClass();
        }
        return decode(bindings, captureNative(methods, owners));
    }

    // Package visibility permits independent payload validation tests. This does
    // not create an activation certificate; even a valid snapshot is incomplete.
    static MethodObservation decode(CallerMethodBindings bindings, long[] input) {
        bindings.scopedProbeCallers(64);
        List<CallerMethodBindings.BoundMethod> all = ordered(bindings);
        CallerCoveragePlan.require(input != null && input.length == HEADER + WORDS * all.size(), "COVERAGE_OBSERVATION_SIZE");
        long[] words = input.clone();
        CallerCoveragePlan.require(words[0] == MAGIC && words[1] == 1 && words[2] > 0
            && words[3] == all.size(), "COVERAGE_OBSERVATION_HEADER");
        Set<Long> addresses = new HashSet<>();
        List<ObservedMethod> methods = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            int at = HEADER + i * WORDS;
            CallerCoveragePlan.require(words[at] != 0 && (words[at] & 7) == 0 && addresses.add(words[at]),
                "COVERAGE_OBSERVATION_METHOD_IDENTITY");
            CallerCoveragePlan.require(words[at + 1] > 0 && words[at + 1] <= 0xffffffffL
                && words[at + 1] == words[at + 7], "COVERAGE_OBSERVATION_OWNER_IDENTITY");
            for (int j = 2; j <= 4; j++) CallerCoveragePlan.require(words[at + j] >= 0
                && words[at + j] <= 0xffffffffL, "COVERAGE_OBSERVATION_WORD");
            CallerCoveragePlan.require(words[at + 3] == all.get(i).source.index, "COVERAGE_OBSERVATION_DEX_INDEX");
            int semanticFlags = java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.PRIVATE
                | java.lang.reflect.Modifier.PROTECTED | java.lang.reflect.Modifier.STATIC
                | java.lang.reflect.Modifier.FINAL | java.lang.reflect.Modifier.NATIVE | java.lang.reflect.Modifier.ABSTRACT;
            CallerCoveragePlan.require((words[at + 2] & semanticFlags) == (all.get(i).method.getModifiers() & semanticFlags),
                "COVERAGE_OBSERVATION_METHOD_FLAGS");
            CallerCoveragePlan.require(words[at + 6] != 0, "COVERAGE_OBSERVATION_ENTRY");
            methods.add(new ObservedMethod(all.get(i), words, at));
        }
        return new MethodObservation(bindings, words[2], methods);
    }
    private static List<CallerMethodBindings.BoundMethod> ordered(CallerMethodBindings bindings) {
        List<CallerMethodBindings.BoundMethod> result = new ArrayList<>();
        result.add(bindings.target);
        result.addAll(bindings.callers);
        return result;
    }
}
