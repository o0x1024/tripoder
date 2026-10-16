package dev.tripoder.selfhook.coverage;

import dalvik.annotation.optimization.FastNative;
import dev.tripoder.selfhook.dex.AbiSignature;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One bounded, rooted ART enumeration. No class loading/deoptimization request. */
public final class LoadedClassInventory {
    private LoadedClassInventory() { }
    private static boolean attempted, bound;
    private static native boolean bindNative(Method fast);
    private static native boolean prepareNative(int capacity);
    @FastNative private static native int captureFast(Class<?>[] output, long[] stats);
    private static native String stageNative();

    public static final class Snapshot {
        public final long sequence, startedNanos, finishedNanos;
        public final int budget, visitedAtLeast, bootClassCount, definingLoaderCount;
        public final boolean enumerationCompleted;
        public final boolean futureClassPublicationCovered = false;
        public final boolean completeRuntimeCoverage = false;
        public final List<Class<?>> classes;
        private Snapshot(Class<?>[] classes, long[] stats, int status, long start, long finish) {
            if (stats.length != 6 || stats[0] != 1 || stats[1] <= 0 || stats[2] < 1 || stats[2] > 65536
                || stats[3] < 0 || stats[3] > stats[2] + 1 || stats[4] < 0 || stats[4] > stats[2]
                || stats[5] != status || (status != 0 && status != 1))
                throw new IllegalStateException("LOADED_CLASS_RECEIPT_INVALID");
            enumerationCompleted = status == 0;
            if (enumerationCompleted ? stats[3] != stats[4] || stats[4] == 0 : stats[4] != 0 || stats[3] <= stats[2])
                throw new IllegalStateException("LOADED_CLASS_ENUMERATION_INCOMPLETE");
            sequence = stats[1]; budget = (int)stats[2]; visitedAtLeast = (int)stats[3];
            startedNanos = start; finishedNanos = finish;
            Class<?>[] copy = Arrays.copyOf(classes, (int)stats[4]);
            Set<Class<?>> unique = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<>());
            ClassLoader bootstrapView = Class.class.getClassLoader();
            int boot = 0;
            for (Class<?> klass : copy) {
                if (klass == null || !unique.add(klass)) throw new IllegalStateException("LOADED_CLASS_IDENTITY_DUPLICATE");
                ClassLoader loader = klass.getClassLoader();
                // Android's public API exposes BootClassLoader for boot classes;
                // primitive Class objects still report null. Use the known boot
                // Class identity, not a universal "loader==null" assumption.
                if (klass.isPrimitive() || loader == bootstrapView) boot++; else loaders.add(loader);
            }
            if (enumerationCompleted && (!unique.contains(Class.class) || !unique.contains(LoadedClassInventory.class)))
                throw new IllegalStateException("LOADED_CLASS_SENTINELS_MISSING");
            bootClassCount = boot; definingLoaderCount = loaders.size();
            this.classes = Collections.unmodifiableList(Arrays.asList(copy));
        }
        /** Existing caller binding input for exactly one real defining loader. */
        public Map<String, Class<?>> forLoader(ClassLoader loader) {
            if (!enumerationCompleted) throw new IllegalStateException("LOADED_CLASS_BUDGET_EXHAUSTED");
            Map<String, Class<?>> result = new LinkedHashMap<>();
            for (Class<?> klass : classes) if (klass.getClassLoader() == loader) {
                if (result.put(AbiSignature.descriptor(klass), klass) != null)
                    throw new IllegalStateException("LOADED_CLASS_DESCRIPTOR_COLLISION");
            }
            return Collections.unmodifiableMap(result);
        }
    }

    public static synchronized Snapshot capture(int capacity) throws Exception {
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("LOADED_CLASS_BUDGET");
        if (!attempted) {
            attempted = true;
            bound = bindNative(LoadedClassInventory.class.getDeclaredMethod("captureFast", Class[].class, long[].class));
        }
        if (!bound) throw new IllegalStateException("LOADED_CLASS_BIND_REJECTED:" + stageNative());
        Class<?>[] output = new Class<?>[capacity];
        long[] stats = new long[6];
        if (!prepareNative(capacity)) throw new IllegalStateException("LOADED_CLASS_PREPARE_REJECTED:" + stageNative());
        long start = System.nanoTime();
        int status = captureFast(output, stats);
        long finish = System.nanoTime();
        if (status != 0 && status != 1) throw new IllegalStateException("LOADED_CLASS_CAPTURE_REJECTED:" + stageNative());
        return new Snapshot(output, stats, status, start, finish);
    }
}
