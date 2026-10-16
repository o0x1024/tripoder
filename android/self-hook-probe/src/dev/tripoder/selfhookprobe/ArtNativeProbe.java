package dev.tripoder.selfhookprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact native boot-method binding with original managed frame retained. */
public final class ArtNativeProbe {
    public static native int registeredAdd(int value);
    private static native boolean calibrate(Method method);
    private static native boolean prepare(Field field, Object value);
    private static native boolean toggle(boolean active);
    private static native String report();
    private int checks;
    private final JSONArray failures = new JSONArray();
    private void check(boolean value, String name) { checks++; if (!value) failures.put(name); }
    private static String failure(Field field, Object receiver) {
        try { field.get(receiver); return "NONE"; }
        catch (Throwable error) { return error.getClass().getName(); }
    }
    public static JSONObject run(String compilation) throws Exception { return new ArtNativeProbe().execute(compilation); }
    private static int hotRead(Field field, Object receiver, int expected) throws Exception {
        int failures = 0;
        for (int i = 0; i < 10000; i++) if (!field.get(receiver).equals(expected)) failures++;
        return failures;
    }
    private JSONObject execute(String compilation) throws Exception {
        if (!"jit".equals(compilation) && !"aot".equals(compilation))
            throw new IllegalArgumentException("EXPLICIT_ART_COMPILATION_REQUIRED");
        JSONObject result = new JSONObject();
        boolean active = false;
        check(registeredAdd(7) == 8 && calibrate(ArtNativeProbe.class.getDeclaredMethod("registeredAdd", int.class)),
            "ART_NATIVE_LAYOUT_CALIBRATION");
        if (failures.length() > 0) return finish(result);
        ReflectionFixture object = new ReflectionFixture();
        Field value = ReflectionFixture.class.getDeclaredField("value");
        Field secret = ReflectionFixture.class.getDeclaredField("secret");
        check(value.get(object).equals(17), "NATIVE_FIELD_BASELINE");
        String denied = failure(secret, object), nullReceiver = failure(value, null), wrongReceiver = failure(value, new Object());
        String privateNull = failure(secret, null), privateWrong = failure(secret, new Object());
        check(denied.equals("java.lang.IllegalAccessException"), "BASELINE_PRIVATE_ACCESS_DENIED");
        check(prepare(value, Integer.valueOf(117)), "PREPARE_EXACT_NATIVE_BINDING");
        if (failures.length() > 0) return finish(result);
        try {
            active = toggle(true);
            check(active, "REGISTER_NATIVE_FIELD_GET");
            if (!active) return finish(result);
            check(value.get(object).equals(117) && object.value == 17, "REAL_BOOT_NATIVE_REPLACEMENT");
            check(failure(secret, object).equals(denied), "ORIGINAL_CALLER_ACCESS_FRAME");
            check(failure(value, null).equals(nullReceiver), "ORIGINAL_NULL_EXCEPTION_ORDER");
            check(failure(value, new Object()).equals(wrongReceiver), "ORIGINAL_TYPE_EXCEPTION_ORDER");
            check(failure(secret, null).equals(privateNull), "PRIVATE_NULL_EXCEPTION_ORDER");
            check(failure(secret, new Object()).equals(privateWrong), "PRIVATE_TYPE_EXCEPTION_ORDER");
            secret.setAccessible(true);
            check(secret.get(object).equals(23), "ACCESS_OVERRIDE_UNCHANGED");
            secret.setAccessible(false);
            check(failure(secret, object).equals(denied), "ACCESS_OVERRIDE_REVOKED");
            System.gc();
            check(value.get(object).equals(117), "GLOBAL_ROOTS_AFTER_GC_REQUEST");
            Method hot = ArtNativeProbe.class.getDeclaredMethod("hotRead", Field.class, Object.class, int.class);
            // Enter the sampled method through reflection so a direct AOT call or
            // inlining in execute() cannot leave this method's entry unresolved.
            JSONObject before;
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            int primeRounds = 0, primeFailures = 0;
            do {
                primeFailures += (Integer) hot.invoke(null, value, object, 117);
                before = ArtProbe.sample(hot);
                primeRounds++;
                if (compilation.equals(ArtCompilation.kind(before))) break;
                if (android.os.SystemClock.elapsedRealtime() >= deadline)
                    throw new IllegalStateException("REQUESTED_COMPILATION_NOT_OBSERVED:" + compilation);
                Thread.sleep(10);
            } while (true);
            check(primeFailures == 0, "PRIMED_HOT_CALLER");
            result.put("hotMethodBefore", before).put("requestedCompilation", compilation).put("primeRounds", primeRounds);
            long callbacksBefore = new JSONObject(report()).getLong("replacements");
            int hotFailures = 0;
            for (int i = 0; i < 100; i++) hotFailures += (Integer) hot.invoke(null, value, object, 117);
            long callbacksAfter = new JSONObject(report()).getLong("replacements");
            check(hotFailures == 0, "HOT_ACTIVE_RESULT");
            check(callbacksAfter - callbacksBefore == 1000000, "HOT_EVERY_CALL_INTERCEPTED");
            result.put("hotMethodAfter", ArtProbe.sample(hot));
            result.put("hotCalls", 1000000).put("hotFailures", hotFailures)
                .put("hotIntercepted", callbacksAfter - callbacksBefore).put("hotInvocation", "Method.invoke");
            check(toggle(false), "RESTORE_ORIGINAL_NATIVE_BINDING");
            active = false;
            check(value.get(object).equals(17), "DISABLED_FIELD_ORIGINAL");
            check(hotRead(value, object, 17) == 0, "HOT_DISABLED_ORIGINAL");

            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger errors = new AtomicInteger(), calls = new AtomicInteger();
            Thread[] workers = new Thread[4];
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(() -> {
                    while (!stop.get()) {
                        try {
                            Object read = value.get(object);
                            if (!read.equals(17) && !read.equals(117)) errors.incrementAndGet();
                            calls.incrementAndGet();
                        } catch (Throwable error) { errors.incrementAndGet(); }
                    }
                }, "native-field-" + i);
                workers[i].start();
            }
            int cycles = 0;
            try {
                for (; cycles < 20; cycles++) {
                    active = toggle(true);
                    check(active, "CONCURRENT_BIND");
                    if (!active) break;
                    Thread.sleep(2);
                    boolean disabled = toggle(false);
                    check(disabled, "CONCURRENT_RESTORE");
                    if (!disabled) break;
                    active = false;
                    Thread.sleep(2);
                }
            } finally {
                stop.set(true);
                for (Thread worker : workers) worker.join();
            }
            check(cycles == 20 && calls.get() > 0 && errors.get() == 0, "NATIVE_FIELD_CONCURRENT_VALUES");
            check(value.get(object).equals(17), "NATIVE_FIELD_FINAL_ORIGINAL");
            result.put("cycles", cycles).put("workerCalls", calls.get()).put("workerErrors", errors.get());
        } finally {
            if (active) check(toggle(false), "FINAL_RESTORE");
        }
        return finish(result);
    }
    private JSONObject finish(JSONObject result) throws Exception {
        return result.put("checks", checks).put("failures", failures).put("passed", failures.length() == 0)
            .put("binding", new JSONObject(report())).put("nativeMethodHook", true).put("genericJavaHook", false);
    }
}
