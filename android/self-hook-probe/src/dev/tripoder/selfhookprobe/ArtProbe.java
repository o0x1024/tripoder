package dev.tripoder.selfhookprobe;

import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Method observations under a pinned ART lock contract; no method writes or hook. */
public final class ArtProbe {
    private static native String discover();
    private static native long methodId(Method method);
    private static native Method reflected(Class<?> owner, Method method, boolean isStatic);
    private static native long reflectedArtMethod(Method method);
    private static native String readLockedMethod(Method method);
    private static native int callInt(Class<?> owner, Object receiver, Method method, int value);
    private static native long callLong(Class<?> owner, Method method);
    private static native Object callObject(Class<?> owner, Method method, Object value);
    private static native void callThrow(Class<?> owner, Method method, RuntimeException value);
    private int checks;
    private final JSONArray failures = new JSONArray();
    private void check(boolean value, String name) { checks++; if (!value) failures.put(name); }

    public static JSONObject run(String apkPath) throws Exception { return new ArtProbe().execute(apkPath); }
    static JSONObject sample(Method method) throws Exception { return new JSONObject(readLockedMethod(method)); }
    private JSONObject execute(String apkPath) throws Exception {
        JSONObject result = new JSONObject();
        result.put("scope", "read-only ART access and public JNI invocation; no hook");
        result.put("access", new JSONObject(discover()));
        Method add = ArtFixture.class.getDeclaredMethod("add", int.class);
        long token = methodId(add);
        check(token != 0 && token == methodId(add), "OPAQUE_JNI_ID_STABLE");
        check(add.equals(reflected(ArtFixture.class, add, true)), "JNI_REFLECTION_ROUND_TRIP");
        result.put("methodId", Long.toHexString(token));
        JSONObject locked = new JSONObject(readLockedMethod(add));
        result.put("lockedMethod", locked);
        check(locked.optBoolean("bound") && locked.optBoolean("threadPresent")
              && locked.optInt("suspendResumeCycles") == 10, "PINNED_ART_SUSPEND_RESUME");
        try {
            long address = reflectedArtMethod(add);
            result.put("artMethodFieldReadable", true).put("artMethodAddress", Long.toHexString(address));
        } catch (NoSuchFieldError | SecurityException error) {
            result.put("artMethodFieldReadable", false).put("artMethodFieldError", error.toString());
        }
        check(ArtFixture.add(7) == 8 && callInt(ArtFixture.class, null, add, 7) == 8, "STATIC_JNI_CALL");
        ArtFixture object = new ArtFixture(9);
        Method instance = ArtFixture.class.getDeclaredMethod("addInstance", int.class);
        check(callInt(ArtFixture.class, object, instance, 7) == 16, "INSTANCE_JNI_CALL");
        check(instance.equals(reflected(ArtFixture.class, instance, false)), "INSTANCE_REFLECTION_IDENTITY");
        check(callInt(ArtFixture.class, null, ArtFixture.class.getDeclaredMethod("monitor", int.class), 7) == 8,
            "JAVA_SYNCHRONIZED_MONITOR");
        check(callInt(ArtFixture.class, null, ArtFixture.class.getDeclaredMethod("recurse", int.class), 12) == 12,
            "JAVA_RECURSION");
        check(callLong(ArtFixture.class, ArtFixture.class.getDeclaredMethod("threadId")) == Thread.currentThread().getId(),
            "ORIGINAL_CALLING_THREAD");
        Object reference = new Object();
        check(callObject(ArtFixture.class, ArtFixture.class.getDeclaredMethod("echo", Object.class), reference) == reference,
            "JNI_REFERENCE_ACROSS_GC_REQUEST");
        RuntimeException expected = new RuntimeException("owned-art-probe");
        Throwable actual = null;
        try { callThrow(ArtFixture.class, ArtFixture.class.getDeclaredMethod("throwExact", RuntimeException.class), expected); }
        catch (RuntimeException error) { actual = error; }
        check(actual == expected, "EXACT_EXCEPTION_IDENTITY");

        ClassLoader separate = new PathClassLoader(apkPath, null);
        Class<?> other = separate.loadClass(ArtFixture.class.getName());
        Method otherAdd = other.getDeclaredMethod("add", int.class);
        check(other != ArtFixture.class && other.getClassLoader() == separate, "DISTINCT_CLASS_LOADER");
        check(methodId(otherAdd) != token && !add.equals(otherAdd), "SAME_NAME_DIFFERENT_METHOD_ID");
        check(callInt(other, null, otherAdd, 7) == 8, "SECOND_LOADER_JNI_CALL");
        check(otherAdd.equals(reflected(other, otherAdd, true)), "SECOND_LOADER_REFLECTION_ROUND_TRIP");
        result.put("checks", checks).put("failures", failures).put("observationPassed", failures.length() == 0);
        result.put("internalArtCalls", locked.optBoolean("bound")).put("methodMemoryModified", false).put("javaHook", false);
        result.put("jitAotVerified", false).put("callerSensitiveHookVerified", false);
        return result;
    }
}
