package dev.tripoder.selfhook.dex;

import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** JVM descriptors are the ABI authority. No numeric coercion or type guessing. */
public final class AbiSignature {
    public final List<String> parameters;
    public final String result;
    public final boolean instance;
    public AbiSignature(Executable method) {
        this(descriptor(method instanceof Method?((Method)method).getReturnType():void.class), descriptors(method.getParameterTypes()), !Modifier.isStatic(method.getModifiers()));
    }
    public AbiSignature(String result, List<String> parameters, boolean instance) {
        valid(result, true);
        if (parameters == null || parameters.size() > 32) throw new IllegalArgumentException("ABI_PARAMETER_BUDGET");
        ArrayList<String> copy = new ArrayList<>(parameters);
        for (String type : copy) valid(type, false);
        this.parameters = Collections.unmodifiableList(copy); this.result = result; this.instance = instance;
    }
    public List<String> helperParameters() {
        ArrayList<String> all = new ArrayList<>();
        if (instance) all.add("Ljava/lang/Object;");
        for (String type : parameters) all.add(reference(type) ? "Ljava/lang/Object;" : type);
        return all;
    }
    public static int words(String type) { return type.equals("J") || type.equals("D") ? 2 : 1; }
    public static boolean reference(String type) { return type.startsWith("L") || type.startsWith("["); }
    public static String descriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == short.class) return "S";
        if (type == char.class) return "C";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("ABI_TYPE");
    }
    private static List<String> descriptors(Class<?>[] types) {
        ArrayList<String> result = new ArrayList<>();
        for (Class<?> type : types) result.add(descriptor(type)); return result;
    }
    static void valid(String value, boolean allowVoid) {
        if (value == null || value.isEmpty() || value.length() > 4096) throw new IllegalArgumentException("ABI_DESCRIPTOR");
        int n = 0; while (n < value.length() && value.charAt(n) == '[') n++;
        if (n > 255 || n == value.length()) throw new IllegalArgumentException("ABI_ARRAY_DESCRIPTOR");
        String base = value.substring(n);
        if (base.length() == 1 && "ZBSCIJFD".contains(base)) return;
        if (n == 0 && allowVoid && base.equals("V")) return;
        if (base.startsWith("L") && base.endsWith(";") && base.length() > 2) {
            String name = base.substring(1, base.length() - 1);
            if (!name.startsWith("/") && !name.endsWith("/") && !name.contains("//")
                && name.indexOf(';') < 0 && name.indexOf('[') < 0 && name.indexOf('.') < 0 && name.indexOf('\0') < 0) return;
        }
        throw new IllegalArgumentException("ABI_DESCRIPTOR");
    }
}
