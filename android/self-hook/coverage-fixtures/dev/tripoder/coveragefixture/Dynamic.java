package dev.tripoder.coveragefixture;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.function.IntUnaryOperator;

public final class Dynamic {
    public static Object reflection(Method method, int value) throws Exception {
        return method.invoke(null, value);
    }
    public static int handle(MethodHandle handle, int value) throws Throwable {
        return (int) handle.invokeExact(value);
    }
    public static IntUnaryOperator custom() { return Targets::scalar; }
    public static Class<?> future(String name, ClassLoader loader) throws Exception {
        return Class.forName(name, false, loader);
    }
}
