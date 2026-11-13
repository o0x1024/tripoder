package dev.tripoder.runtime;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Minimal experiment for the toast surface.
 *
 * <p>A guest runs as the host uid while calling itself by its own package name, so the physical
 * notification service refuses its toasts ({@code Package X is not owned by uid N}) and any app
 * that does not catch that SecurityException dies on the main thread. The experiment answers two
 * questions in the same process: (1) does a guest-context toast really throw, and (2) can the
 * container answer {@code enqueueToast} itself by replacing the cached interface that
 * {@code Toast.show()} uses, while delegating every other call to the physical service.
 *
 * <p>Every step is caught: the probe must never be able to fail a guest.
 */
final class RuntimeToastProbe {
    private static final String TAG = "TripoderToastProbe";
    private static final boolean PROBE = false;

    private RuntimeToastProbe() {}

    static void run(Context context) {
        if (!PROBE) return;
        if (context == null) {
            Log.w(TAG, "SKIP=NO_CONTEXT");
            return;
        }
        // v82: the swap path is refuted (v81), so the experiment is now only about which identity the
        // toast presents to the physical service.
        Log.i(TAG, "IDENTITY opPackage=" + context.getOpPackageName()
                + " package=" + context.getPackageName() + " uid=" + android.os.Process.myUid());
        attempt(context, "PHYSICAL");
    }

    private static void attempt(Context context, String phase) {
        try {
            Toast.makeText(context, "tripoder toast probe " + phase, Toast.LENGTH_SHORT).show();
            Log.i(TAG, phase + "=OK");
        } catch (Throwable error) {
            Log.w(TAG, phase + "=THREW " + error);
        }
    }

    /**
     * Points the cached notification interface at our answering proxy.
     *
     * <p>Both {@code Toast} and {@code NotificationManager} keep their own static cache of
     * {@code INotificationManager}, so every owner has to be visited: the first experiment swapped
     * only {@code NotificationManager.sService}, reported success, and the toast still threw.
     */
    private static boolean swap() {
        boolean swapped = false;
        for (String owner : new String[] {"android.widget.Toast", "android.app.NotificationManager"}) {
            try {
                Class<?> type = Class.forName(owner);
                Field target = null;
                for (Field candidate : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                            && candidate.getType().getName().endsWith("INotificationManager")) {
                        Log.i(TAG, "CANDIDATE " + owner + "." + candidate.getName() + ":" + candidate.getType().getName());
                        if (target == null) target = candidate;
                    }
                }
                if (target == null) {
                    refuse("SWAP=NO_FIELD " + owner);
                    continue;
                }
                target.setAccessible(true);
                Object physical = target.get(null);
                if (physical == null) {
                    refuse("SWAP=FIELD_EMPTY " + owner + "." + target.getName());
                    continue;
                }
                Class<?> service = target.getType();
                Object proxy = Proxy.newProxyInstance(
                        service.getClassLoader(), new Class<?>[] {service}, new Answer(physical));
                target.set(null, proxy);
                Log.i(TAG, "SWAP=OK " + owner + "." + target.getName() + " interface=" + service.getName());
                swapped = true;
            } catch (Throwable error) {
                refuse("SWAP=THREW " + owner + " " + error);
            }
        }
        return swapped;
    }

    private static boolean refuse(String line) {
        Log.w(TAG, line);
        return false;
    }

    /** Answers toast enqueues; everything else is the physical service's business. */
    private static final class Answer implements InvocationHandler {
        private final Object physical;

        Answer(Object physical) {
            this.physical = physical;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (name.contains("enqueueToast")) {
                Log.i(TAG, "ANSWERED=" + name + "/" + (arguments == null ? 0 : arguments.length));
                return placeholder(method.getReturnType());
            }
            try {
                return method.invoke(physical, arguments);
            } catch (InvocationTargetException error) {
                throw error.getTargetException();
            }
        }

        private static Object placeholder(Class<?> type) {
            if (type == void.class || !type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == char.class) return (char) 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            return 0;
        }
    }
}
