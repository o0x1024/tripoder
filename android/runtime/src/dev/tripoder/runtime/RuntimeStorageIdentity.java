package dev.tripoder.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * The platform validates the {@code callingPackage} of {@code IStorageManager.getVolumeList(...)} against the real UID,
 * which is the host package's UID. A guest that asks {@code Environment.getExternalStorageDirectory()} therefore has to
 * present the host package — the identity the platform already knows this process by — or the answer is
 * {@code SecurityException: callingPackage does not match UID} raised by system_server.
 *
 * <p>Two boundaries carry that projection, because the platform reaches this service two ways:
 * <ul>
 *   <li>{@link #prime()} replaces the {@code IStorageManager} the framework caches on {@code StorageManager}. The
 *       framework resolves it from boot-image code, where a {@code ServiceManager} self-hook is not guaranteed to run
 *       ({@code preexistingCompiledFramesCovered:false}), so waiting for that lookup projects nothing at all — measured:
 *       netease died inside {@code Application.onCreate}'s startup task with zero {@code ServiceManager} hits.</li>
 *   <li>{@link #wrap(IBinder)} normalises the same call at the Binder boundary for guests that ask for the service
 *       themselves.</li>
 * </ul>
 *
 * <p>Both are projections of one fact, not a guess: the argument is computed by the platform from what it believes the
 * caller is, and the only package the platform accepts next to uid 10542 is the host package. Nothing here ever fails a
 * guest — every failure is a note and the untouched platform object stays in place.
 */
final class RuntimeStorageIdentity {
    static final String SERVICE = "mount";
    private static final String DESCRIPTOR = "android.os.storage.IStorageManager";
    private static final String MANAGER = "android.os.storage.StorageManager";
    private static final String TAG = "TripoderStorageIdentity";

    private static volatile Wrapper cached;
    private static int reported;

    private RuntimeStorageIdentity() {}

    /** @return a wrapper for the platform "mount" Binder, or {@code real} itself when there is nothing to normalise. */
    static IBinder wrap(IBinder real) {
        RuntimeEngine engine = RuntimeEngine.current;
        if (real == null || engine == null || engine.packageName.equals(engine.host.getPackageName())) return real;
        Wrapper current = cached;
        if (current != null && current.real == real) return current;
        int code = constant("TRANSACTION_getVolumeList");
        if (code < 0) return real;
        Method volumeList = volumeList(engine);
        if (volumeList == null) return real;
        Wrapper created = new Wrapper(real, code, volumeList.getParameterTypes(), engine.packageName, engine.host.getPackageName());
        cached = created;
        return created;
    }

    /**
     * Push the projection into the object the framework already holds.
     *
     * <p>Runs before any guest factory, initializer or {@code Application}: a guest whose {@code onCreate} waits on a
     * startup task that asks a service "who is calling" dies inside that wait, so a projection installed afterwards is
     * installed too late (measured on netease, which crashed 17 ms after the post-onCreate placement was reached).
     *
     * <p>The replacement is a dynamic proxy over the real interface, not a hand-rolled Binder: the platform renames and
     * reorders AIDL transactions, and a proxy needs neither the transaction code nor the parcel shape — it rewrites one
     * argument of one Java method and delegates everything else unchanged.
     */
    static void prime() {
        RuntimeEngine engine = RuntimeEngine.current;
        if (engine == null) return;
        String guest = engine.packageName, host = engine.host.getPackageName();
        if (guest.equals(host)) return;
        StringBuilder statics = new StringBuilder();
        try {
            Class<?> manager = Class.forName(MANAGER);
            Object delegate = delegate(engine);
            Method volumeList = volumeList(engine);
            Object projection = projection(delegate, volumeList, guest, host);
            int injected = 0;
            for (Field field : manager.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (statics.length() < 480) {
                    statics.append(statics.length() == 0 ? "" : ",")
                        .append(field.getName()).append(':').append(field.getType().getSimpleName());
                }
                if (!field.getType().getName().equals(DESCRIPTOR)) continue;
                try {
                    field.setAccessible(true);
                    field.set(null, projection);
                    injected++;
                } catch (Throwable refused) {
                    RuntimeFailThrough.note("storage-identity", "prime: " + field.getName() + " refused: " + refused);
                }
            }
            Log.i(TAG, "prime guest=" + guest + " injected=" + injected + " statics=" + statics);
        } catch (Throwable error) {
            RuntimeFailThrough.note("storage-identity", "prime failed: " + error + " statics=" + statics);
        }
    }

    /** The platform's own mount interface object, asked through the real "mount" Binder. */
    private static Object delegate(RuntimeEngine engine) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, SERVICE);
        while (binder instanceof Wrapper) binder = ((Wrapper) binder).real;
        return Class.forName(DESCRIPTOR + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Object projection(final Object delegate, final Method volumeList, final String guest, final String host) {
        if (delegate == null || volumeList == null) throw new IllegalStateException("mount interface unavailable");
        final Class<?> service = volumeList.getDeclaringClass();
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() != service || !method.getName().equals(volumeList.getName())
                || args == null || args.length != volumeList.getParameterCount()) {
                return invoke(delegate, method, args);
            }
            Object[] rewritten = args.clone();
            boolean replaced = false;
            for (int index = 0; index < rewritten.length; index++) {
                if (!guest.equals(rewritten[index])) continue;
                rewritten[index] = host;
                replaced = true;
            }
            if (reported < 4) {
                reported++;
                Log.i(TAG, "getVolumeList callingPackage=" + guest + (replaced ? " -> " + host : " (unchanged)")
                    + " parameters=" + rewritten.length + " thread=" + Thread.currentThread().getName());
            }
            return invoke(delegate, method, rewritten);
        };
        return Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service}, handler);
    }

    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }

    /**
     * The single {@code getVolumeList} overload carrying one String (the calling package) and ints everywhere else, or
     * {@code null} when the platform shape is not recognised. Read from the interface instead of assumed: the argument
     * to rewrite must be identified by the platform's own signature, never by a remembered position.
     */
    private static Method volumeList(RuntimeEngine engine) {
        try {
            Method found = null;
            for (Method method : Class.forName(DESCRIPTOR).getMethods()) {
                if (!"getVolumeList".equals(method.getName())) continue;
                int strings = 0;
                boolean supported = true;
                for (Class<?> type : method.getParameterTypes()) {
                    if (type == String.class) strings++;
                    else if (type != int.class) supported = false;
                }
                if (!supported || strings != 1) continue;
                if (found != null) {
                    RuntimeFailThrough.note("storage-identity", "getVolumeList is ambiguous");
                    return null;
                }
                found = method;
            }
            if (found == null) RuntimeFailThrough.note("storage-identity", "getVolumeList shape not found");
            return found;
        } catch (Throwable error) {
            RuntimeFailThrough.note("storage-identity", "getVolumeList shape unavailable: " + error);
            return null;
        }
    }

    private static int constant(String name) {
        try {
            Class<?> stub = Class.forName(DESCRIPTOR + "$Stub");
            Field field = stub.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable error) {
            int code = byTransactionName();
            if (code < 0) RuntimeFailThrough.note("storage-identity", name + " unavailable: " + error);
            return code;
        }
    }

    /** AIDL reports its own transaction names on API 34+; ask the platform instead of trusting a generated constant. */
    private static int byTransactionName() {
        try {
            Method lookup = Class.forName(DESCRIPTOR + "$Stub").getMethod("getTransactionName", int.class);
            for (int code = 1; code < 256; code++) {
                Object name = lookup.invoke(null, code);
                if (name instanceof String && ((String) name).endsWith(".getVolumeList")) return code;
            }
        } catch (Throwable error) {
            RuntimeFailThrough.note("storage-identity", "getTransactionName unavailable: " + error);
        }
        return -1;
    }

    private static final class Wrapper extends Binder {
        final IBinder real;
        private final int volumeListCode;
        private final Class<?>[] parameters;
        private final String guest, host;
        private int reported;

        Wrapper(IBinder real, int volumeListCode, Class<?>[] parameters, String guest, String host) {
            this.real = real;
            this.volumeListCode = volumeListCode;
            this.parameters = parameters;
            this.guest = guest;
            this.host = host;
            attachInterface(null, DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code != volumeListCode) return real.transact(code, data, reply, flags);
            Parcel out = Parcel.obtain();
            try {
                return forward(code, data, reply, flags, out);
            } catch (RuntimeException error) {
                RuntimeFailThrough.note("storage-identity", "getVolumeList parse failed, forwarded untouched: " + error);
                data.setDataPosition(0);
                return real.transact(code, data, reply, flags);
            } finally {
                out.recycle();
            }
        }

        private boolean forward(int code, Parcel data, Parcel reply, int flags, Parcel out) throws android.os.RemoteException {
            data.enforceInterface(DESCRIPTOR);
            Object[] values = new Object[parameters.length];
            for (int index = 0; index < parameters.length; index++) {
                Class<?> type = parameters[index];
                if (type == int.class) values[index] = data.readInt();
                else if (type == String.class) values[index] = data.readString();
                else throw new IllegalStateException("unsupported parameter " + type);
            }
            if (data.dataAvail() != 0) throw new IllegalStateException("trailing data " + data.dataAvail());
            String callingPackage = null;
            for (Object value : values) {
                if (value instanceof String) { callingPackage = (String) value; break; }
            }
            boolean rewrite = guest.equals(callingPackage);
            if (reported < 4) {
                reported++;
                Log.i(TAG, "binder getVolumeList callingPackage=" + callingPackage + (rewrite ? " -> " + host : " (unchanged)")
                    + " parameters=" + parameters.length + " thread=" + Thread.currentThread().getName());
            }
            out.writeInterfaceToken(DESCRIPTOR);
            for (Object value : values) {
                if (value instanceof String) out.writeString(rewrite ? host : (String) value);
                else out.writeInt((Integer) value);
            }
            return real.transact(code, out, reply, flags);
        }

        @Override public boolean pingBinder() { return real.pingBinder(); }
        @Override public boolean isBinderAlive() { return real.isBinderAlive(); }
    }
}
