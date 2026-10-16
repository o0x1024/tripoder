package dev.tripoder.selfhookprobe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed owned method experiment; no arbitrary target and no inlining claim. */
public final class ArtManagedProbe {
    private static native boolean prepare();
    private static native int callOriginal(int value);
    private static native boolean toggle(boolean active);
    private static native String report();
    private static native int deoptimizeCallers(Method[] methods);
    private int checks;
    private final JSONArray failures = new JSONArray();
    private void check(boolean value, String reason) { checks++; if (!value) failures.put(reason); }
    public static JSONObject run(String compilation, String apkPath) throws Exception { return new ArtManagedProbe().execute(compilation, apkPath); }
    private JSONObject execute(String compilation, String apkPath) throws Exception {
        JSONObject result = new JSONObject();
        Method method = ManagedFixture.class.getDeclaredMethod("compute", int.class);
        check(ManagedFixture.compute(7) == 8, "MANAGED_BASELINE");
        Method caller = ManagedCaller.class.getDeclaredMethod("call", int.class);
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        JSONObject callerBefore;
        do {
            if (((Integer) caller.invoke(null, 1)) != 0) throw new IllegalStateException("MANAGED_CALLER_BASELINE");
            callerBefore = ArtProbe.sample(caller);
            if (compilation.equals(ArtCompilation.kind(callerBefore))) break;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("MANAGED_CALLER_NOT_COMPILED");
            Thread.sleep(10);
        } while (true);
        result.put("callerBefore", callerBefore).put("requestedCompilation", compilation);
        DexCallerProbe indexed = new DexCallerProbe(apkPath);
        result.put("callerIndex", indexed.evidence);
        result.put("before", ArtProbe.sample(method));
        check(dev.tripoder.selfhook.OriginalMethodStorage.prepare(method)
            && dev.tripoder.selfhook.OriginalMethodStorage.prepare(InlineFixture.class.getDeclaredMethod("plusOne", int.class))
            && prepare(), "MANAGED_BACKUP_PREPARED");
        if (failures.length() != 0) return finish(result);
        int count = ManagedFixture.calls.get();
        check(callOriginal(7) == 8 && ManagedFixture.calls.get() == count + 1, "ORIGINAL_BACKUP_BODY_ONCE");
        check(ManagedFixture.thread == Thread.currentThread().getId(), "ORIGINAL_BACKUP_CALLER_THREAD");
        try { callOriginal(-1); check(false, "ORIGINAL_EXCEPTION_MISSING"); }
        catch (RuntimeException error) { check(error == ManagedFixture.marker, "ORIGINAL_EXCEPTION_IDENTITY"); }
        for (int i = 0; i < 8; i++) {
            byte[][] pressure = new byte[16][];
            for (int j = 0; j < pressure.length; j++) pressure[j] = new byte[65536];
            System.gc();
            check(callOriginal(i) == i + 1, "ORIGINAL_AFTER_GC_REQUEST");
        }
        boolean active = toggle(true);
        check(active, "MANAGED_ENTRY_ENABLED");
        if (!active) return finish(result);
        try {
            result.put("active", ArtProbe.sample(method));
            count = ManagedFixture.calls.get();
            check(((Integer) method.invoke(null, 7)) == 108 && ManagedFixture.calls.get() == count + 1,
                "MANAGED_REAL_REPLACEMENT_ORIGINAL_ONCE");
            check(ManagedFixture.thread == Thread.currentThread().getId(), "HOOK_CALLER_THREAD");
            try { method.invoke(null, -1); check(false, "HOOK_EXCEPTION_MISSING"); }
            catch (InvocationTargetException error) { check(error.getCause() == ManagedFixture.marker, "HOOK_EXCEPTION_IDENTITY"); }
            check(((Integer) method.invoke(null, Integer.MAX_VALUE)) == Integer.MIN_VALUE + 100, "JAVA_INT_WRAP");
            long callbacksBefore = new JSONObject(report()).getLong("callbacks");
            int errors = 0;
            for (int i = 0; i < 10000; i++) if (!method.invoke(null, i).equals(i + 101)) errors++;
            long callbacksAfter = new JSONObject(report()).getLong("callbacks");
            check(errors == 0 && callbacksAfter - callbacksBefore == 10000, "MANAGED_10000_EXACT_CALLS");
            result.put("hotCalls", 10000).put("hotIntercepted", callbacksAfter - callbacksBefore).put("hotErrors", errors);
            long directBefore = new JSONObject(report()).getLong("callbacks");
            int directErrors = (Integer) caller.invoke(null, 101);
            long directIntercepted = new JSONObject(report()).getLong("callbacks") - directBefore;
            result.put("compiledCallerBeforeDeopt", new JSONObject().put("calls", 10000)
                .put("errors", directErrors).put("intercepted", directIntercepted));
            int deoptimized = deoptimizeCallers(indexed.callers);
            result.put("callerDeoptimizedCount", deoptimized).put("callerRetainedGlobalRefs", deoptimized);
            check(deoptimized == indexed.callers.length, "INDEXED_CALLERS_SELECTIVE_DEOPT");
            result.put("callerAfterDeopt", ArtProbe.sample(caller));
            directBefore = new JSONObject(report()).getLong("callbacks");
            directErrors = (Integer) caller.invoke(null, 101);
            directIntercepted = new JSONObject(report()).getLong("callbacks") - directBefore;
            check(directErrors == 0 && directIntercepted == 10000, "DEOPT_CALLER_EVERY_CALL_INTERCEPTED");
            result.put("compiledCallerAfterDeopt", new JSONObject().put("calls", 10000)
                .put("errors", directErrors).put("intercepted", directIntercepted));
            System.gc();
            check(((Integer) method.invoke(null, 7)) == 108, "HOOK_AFTER_GC_REQUEST");
        } finally {
            check(toggle(false), "MANAGED_ENTRY_DISABLED");
        }
        result.put("after", ArtProbe.sample(method));
        check(((Integer) method.invoke(null, 7)) == 8, "MANAGED_ORIGINAL_RESTORED");
        check(callOriginal(7) == 8, "BACKUP_RETAINED_AFTER_DISABLE");
        return finish(result);
    }
    private JSONObject finish(JSONObject result) throws Exception {
        return result.put("checks", checks).put("failures", failures).put("passed", failures.length() == 0)
            .put("storage", new JSONObject(dev.tripoder.selfhook.OriginalMethodStorage.report()))
            .put("binding", new JSONObject(report())).put("ownedManagedMethod", true)
            .put("genericJavaHook", false).put("inliningVerified", false).put("gcMovementProven", false);
    }
}
