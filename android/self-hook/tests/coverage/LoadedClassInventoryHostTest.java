package dev.tripoder.selfhook.coverage;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

public final class LoadedClassInventoryHostTest {
    private static int checks;
    private static Constructor<LoadedClassInventory.Snapshot> constructor;
    private static void check(boolean okay, String name) {
        if (!okay) throw new AssertionError(name);
        checks++;
    }
    private static LoadedClassInventory.Snapshot snapshot(Class<?>[] classes, long[] stats, int status) throws Exception {
        return constructor.newInstance(classes, stats, status, 11L, 12L);
    }
    private static void rejected(Class<?>[] classes, long[] stats, int status, String message) throws Exception {
        try { snapshot(classes, stats, status); throw new AssertionError("not rejected"); }
        catch (InvocationTargetException error) { check(error.getCause().getMessage().contains(message), "rejection:" + message); }
    }
    public static void main(String[] ignored) throws Exception {
        constructor = LoadedClassInventory.Snapshot.class.getDeclaredConstructor(Class[].class, long[].class, int.class, long.class, long.class);
        constructor.setAccessible(true);
        Class<?>[] original = {Class.class, LoadedClassInventory.class, LoadedClassInventoryHostTest.class, String[].class, int.class};
        long[] stats = {1, 9, 64, original.length, original.length, 0};
        LoadedClassInventory.Snapshot value = snapshot(original, stats, 0);
        check(value.enumerationCompleted && value.classes.size() == 5, "complete synthetic receipt");
        check(!value.completeRuntimeCoverage && !value.futureClassPublicationCovered, "one pass never claims future coverage");
        check(value.bootClassCount == 3 && value.definingLoaderCount == 1, "boot and actual defining loaders");
        Map<String, Class<?>> owned = value.forLoader(LoadedClassInventory.class.getClassLoader());
        check(owned.size() == 2 && owned.get("Ldev/tripoder/selfhook/coverage/LoadedClassInventory;") == LoadedClassInventory.class,
            "typed caller binding map");
        original[0] = null; stats[4] = 0;
        check(value.classes.get(0) == Class.class, "input arrays cannot mutate snapshot");
        try { value.classes.clear(); throw new AssertionError("mutable snapshot"); }
        catch (UnsupportedOperationException expected) { checks++; }
        try { owned.clear(); throw new AssertionError("mutable bindings"); }
        catch (UnsupportedOperationException expected) { checks++; }
        LoadedClassInventory.Snapshot exhausted = snapshot(new Class<?>[1], new long[]{1, 10, 1, 2, 0, 1}, 1);
        check(!exhausted.enumerationCompleted && exhausted.classes.isEmpty() && exhausted.visitedAtLeast == 2, "overflow returns no partial inventory");
        try { exhausted.forLoader(null); throw new AssertionError("partial map exposed"); }
        catch (IllegalStateException expected) { checks++; }
        rejected(new Class<?>[]{Class.class, Class.class}, new long[]{1, 11, 2, 2, 2, 0}, 0, "DUPLICATE");
        rejected(new Class<?>[]{Class.class}, new long[]{1, 12, 1, 1, 1, 0}, 0, "SENTINELS");
        rejected(new Class<?>[1], new long[]{1, 13, 1, 2, 1, 1}, 1, "INCOMPLETE");
        rejected(new Class<?>[1], new long[]{1, 14, 1, 3, 0, 1}, 1, "RECEIPT_INVALID");
        try { LoadedClassInventory.capture(0); throw new AssertionError("zero budget"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("{\"checks\":" + checks + ",\"nativeCollectorExecuted\":false,\"completeRuntimeCoverage\":false}");
    }
}
