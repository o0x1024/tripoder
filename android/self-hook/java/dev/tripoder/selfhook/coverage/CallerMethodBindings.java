package dev.tripoder.selfhook.coverage;

import dev.tripoder.selfhook.dex.AbiSignature;
import dev.tripoder.selfhook.dex.DexFile;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Binds supplied Class identities without explicit class loading or initialization.
 * Reflection may resolve signature types; this is not a class-publication barrier.
 * These Java identities still need native DEX/index and compiled-code checks. */
public final class CallerMethodBindings {
    public static final class BoundMethod {
        public final DexFile.MethodRef source;
        public final Method method;
        private BoundMethod(DexFile.MethodRef source, Method method) {
            this.source = source;
            this.method = method;
        }
    }
    public static final class Rejection {
        public final DexFile.MethodRef source;
        public final String reason;
        private Rejection(DexFile.MethodRef source, String reason) {
            this.source = source;
            this.reason = reason;
        }
        @Override public String toString() { return source.key() + ":" + reason; }
    }

    public final CallerCoveragePlan plan;
    public final ClassLoader definingLoader;
    public final BoundMethod target;
    public final List<BoundMethod> callers;
    public final List<Rejection> rejections;

    public CallerMethodBindings(CallerCoveragePlan plan, ClassLoader definingLoader,
                               Map<String, Class<?>> definedClasses) {
        CallerCoveragePlan.require(plan != null && definingLoader != null && definedClasses != null,
            "COVERAGE_OWNED_LOADER_BINDINGS_REQUIRED");
        this.plan = plan;
        this.definingLoader = definingLoader;
        Map<String, Class<?>> classes = new HashMap<>(definedClasses);
        ArrayList<Rejection> failures = new ArrayList<>();
        ArrayList<BoundMethod> selected = new ArrayList<>();
        target = bind(plan.target, definingLoader, classes, failures);
        for (DexFile.Definition candidate : plan.candidates) {
            BoundMethod bound = bind(candidate, definingLoader, classes, failures);
            if (bound != null) selected.add(bound);
        }
        callers = Collections.unmodifiableList(selected);
        rejections = Collections.unmodifiableList(failures);
    }

    /** Explicitly scoped probe input. Never an activation authorization. */
    public Method[] scopedProbeCallers(int maxCallers) {
        CallerCoveragePlan.require(maxCallers >= 0 && maxCallers <= 64, "COVERAGE_CALLER_LIMIT");
        if (!rejections.isEmpty()) throw new IllegalStateException("COVERAGE_METHOD_BINDING_REJECTED: " + rejections);
        if (callers.size() > maxCallers) throw new IllegalStateException("COVERAGE_CALLER_BUDGET");
        Method[] result = new Method[callers.size()];
        for (int i = 0; i < result.length; i++) result[i] = callers.get(i).method;
        return result;
    }

    public void requireCompleteRuntimeCoverage() { plan.requireCompleteRuntimeCoverage(); }

    private static BoundMethod bind(DexFile.Definition definition, ClassLoader loader,
                                    Map<String, Class<?>> classes, List<Rejection> failures) {
        DexFile.MethodRef source = definition.method;
        if (source.name.startsWith("<")) return reject(source, "INITIALIZER_CALLER_UNSUPPORTED", failures);
        if ((definition.flags & (Modifier.NATIVE | Modifier.ABSTRACT)) != 0)
            return reject(source, "NON_EXECUTABLE_CALLER", failures);
        Class<?> owner = classes.get(source.owner);
        if (owner == null) return reject(source, "CLASS_IDENTITY_NOT_SUPPLIED", failures);
        if (!AbiSignature.descriptor(owner).equals(source.owner))
            return reject(source, "CLASS_DESCRIPTOR_MISMATCH", failures);
        if (owner.getClassLoader() != loader) return reject(source, "DEFINING_LOADER_MISMATCH", failures);
        // Descriptor includes return type; bridge methods may share name and args.
        // getDeclaredMethod(name, args) alone cannot select them deterministically.
        Method match = null;
        try {
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(source.name) || !descriptor(method).equals(source.descriptor)) continue;
                if (match != null) return reject(source, "METHOD_IDENTITY_AMBIGUOUS", failures);
                match = method;
            }
        } catch (LinkageError | SecurityException error) {
            return reject(source, "METHOD_INSPECTION_REJECTED:" + error.getClass().getName(), failures);
        }
        if (match == null) return reject(source, "METHOD_DESCRIPTOR_NOT_DEFINED", failures);
        int flags = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED | Modifier.STATIC
            | Modifier.FINAL | Modifier.NATIVE | Modifier.ABSTRACT;
        if ((match.getModifiers() & flags) != (definition.flags & flags))
            return reject(source, "METHOD_FLAGS_MISMATCH", failures);
        return new BoundMethod(source, match);
    }

    public static String descriptor(Method method) {
        StringBuilder result = new StringBuilder("(");
        for (Class<?> type : method.getParameterTypes()) result.append(AbiSignature.descriptor(type));
        return result.append(')').append(AbiSignature.descriptor(method.getReturnType())).toString();
    }
    private static BoundMethod reject(DexFile.MethodRef source, String reason, List<Rejection> failures) {
        failures.add(new Rejection(source, reason));
        return null;
    }
}
