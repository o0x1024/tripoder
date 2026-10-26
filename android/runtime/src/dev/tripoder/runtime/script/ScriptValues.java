package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.*;

/** Values visible to the interpreter. No Java object, Class, Method or native address escapes. */
public final class ScriptValues {
    private ScriptValues() {}
    public enum Undefined { VALUE }
    public static final Object UNDEFINED = Undefined.VALUE;
    private static final BigInteger LOW = BigInteger.ONE.shiftLeft(127).negate();
    private static final BigInteger HIGH = BigInteger.ONE.shiftLeft(127);
    private static final Set<String> FORBIDDEN = Set.of("constructor", "prototype", "__proto__", "caller", "callee", "arguments");

    public static String key(Object value) {
        ScriptFault.require(value instanceof String || value instanceof Number, "DRAFT_PROPERTY_KEY");
        String key = value instanceof String ? (String)value : text(value);
        ScriptFault.require(!FORBIDDEN.contains(key), "DRAFT_FORBIDDEN_PROPERTY");
        return key;
    }
    public static boolean scalar(Object value) {
        return value == null || value == UNDEFINED || value instanceof String || value instanceof Boolean
            || value instanceof Double || value instanceof BigInteger;
    }
    public static Object limit(Object value) {
        if (value instanceof String) ScriptFault.require(((String)value).length() <= 8192, "DRAFT_STRING_SIZE");
        if (value instanceof Double) ScriptFault.require(Double.isFinite((Double)value), "DRAFT_NON_FINITE");
        if (value instanceof BigInteger) ScriptFault.require(((BigInteger)value).compareTo(LOW) >= 0 && ((BigInteger)value).compareTo(HIGH) < 0, "DRAFT_BIGINT_SIZE");
        if (value instanceof List) ScriptFault.require(((List<?>)value).size() <= 64, "DRAFT_ARRAY_SIZE");
        if (value instanceof Map) ScriptFault.require(((Map<?,?>)value).size() <= 64, "DRAFT_OBJECT_SIZE");
        return value;
    }
    /** Deep copy checks identity cycles and total size before publishing an immutable snapshot. */
    public static Object freeze(Object value) { return freeze(value, new IdentityHashMap<>(), new int[]{0}, 0, false); }
    public static Object freezeInvocation(Object value){return freeze(value,new IdentityHashMap<>(),new int[]{0},0,true);}
    private static Object freeze(Object value, IdentityHashMap<Object,Boolean> path, int[] count, int depth,boolean invocation) {
        ScriptFault.require(++count[0] <= 8000 && depth < 80, "DRAFT_VALUE_BUDGET");
        if (scalar(value)) return limit(value);
        if(invocation&&value instanceof JavaObjectScope.Ref)return JavaObjectScope.invocationValue(value);
        ScriptFault.require(value instanceof List || value instanceof Map, "DRAFT_HOST_OBJECT");
        ScriptFault.require(path.put(value, Boolean.TRUE) == null, "DRAFT_CYCLIC_VALUE");
        limit(value);
        try {
            if (value instanceof List) {
                List<Object> out = new ArrayList<>();
                for (Object element : (List<?>)value) out.add(freeze(element, path, count, depth + 1, invocation));
                return Collections.unmodifiableList(out);
            }
            Map<String,Object> out = new LinkedHashMap<>();
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) {
                ScriptFault.require(e.getKey() instanceof String, "DRAFT_PROPERTY_KEY");
                out.put(key(e.getKey()), freeze(e.getValue(), path, count, depth + 1, invocation));
            }
            return Collections.unmodifiableMap(out);
        } finally { path.remove(value); }
    }
    public static boolean truth(Object value) {
        if (value == null || value == UNDEFINED || Boolean.FALSE.equals(value)) return false;
        if (value instanceof Double) return (Double)value != 0 && !Double.isNaN((Double)value);
        if (value instanceof BigInteger) return ((BigInteger)value).signum() != 0;
        return !(value instanceof String) || !((String)value).isEmpty();
    }
    public static String text(Object value) {
        ScriptFault.require(scalar(value), "DRAFT_SCALAR_REQUIRED");
        if (value == UNDEFINED) return "undefined";
        if (value == null) return "null";
        if (value instanceof Double) {
            double n = (Double)value;
            if (n == 0) return "0";
            requireFinite(n);
            // Find the shortest decimal which round-trips to the same IEEE value, then use JS exponent thresholds.
            java.math.BigDecimal exact = new java.math.BigDecimal(Math.abs(n)), shortest = null;
            for (int precision = 1; precision <= 17; precision++) {
                java.math.BigDecimal candidate = exact.round(new java.math.MathContext(precision, java.math.RoundingMode.HALF_EVEN));
                if (Double.parseDouble(candidate.toString()) == Math.abs(n)) {shortest=candidate.stripTrailingZeros();break;}
            }
            ScriptFault.require(shortest != null, "DRAFT_NUMBER_FORMAT");
            int exponent = shortest.precision() - shortest.scale() - 1;
            String magnitude;
            if (exponent >= -6 && exponent < 21) magnitude = shortest.toPlainString();
            else {
                String digits = shortest.unscaledValue().toString();
                magnitude = digits.substring(0,1) + (digits.length()==1?"":"."+digits.substring(1)) + "e" + (exponent>=0?"+":"") + exponent;
            }
            return n < 0 ? "-" + magnitude : magnitude;
        }
        return value.toString();
    }
    public static double number(Object value) {
        ScriptFault.require(scalar(value), "DRAFT_SCALAR_REQUIRED");
        if (value == null) return 0;
        if (value instanceof Boolean) return (Boolean)value ? 1 : 0;
        if (value instanceof Double) return (Double)value;
        if (value instanceof BigInteger) return ((BigInteger)value).doubleValue();
        if (value instanceof String) {
            String s = trim((String)value);
            if (s.isEmpty()) return 0;
            if (s.equals("Infinity")||s.equals("+Infinity")) return Double.POSITIVE_INFINITY;
            if (s.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
            if (s.matches("0[xX][0-9a-fA-F]+")) return new BigInteger(s.substring(2),16).doubleValue();
            if (s.matches("0[bB][01]+")) return new BigInteger(s.substring(2),2).doubleValue();
            if (s.matches("0[oO][0-7]+")) return new BigInteger(s.substring(2),8).doubleValue();
            // Do not let Java accept suffixes or hexadecimal floating-point literals.
            if (!s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) return Double.NaN;
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }
    static String trim(String value) {
        return value.replaceAll("^[\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+|[\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+$", "");
    }
    private static void requireFinite(double n) { ScriptFault.require(Double.isFinite(n), "DRAFT_NON_FINITE"); }
    public static BigInteger bigint(Object value) {
        BigInteger result;
        if (value instanceof BigInteger) result = (BigInteger)value;
        else if (value instanceof Boolean) result = (Boolean)value ? BigInteger.ONE : BigInteger.ZERO;
        else if (value instanceof Double) {
            double n = (Double)value;
            ScriptFault.require(n == Math.rint(n) && Math.abs(n) <= 9007199254740991d, "DRAFT_BIGINT_EXACT_INTEGER");
            result = BigInteger.valueOf((long)n);
        } else {
            ScriptFault.require(value instanceof String && ((String)value).length() <= 40 && ((String)value).matches("-?[0-9]+"), "DRAFT_BIGINT_EXACT_INTEGER");
            result = new BigInteger((String)value);
        }
        return (BigInteger)limit(result);
    }
    public static Object abi(String type, Object value) {
        boolean valid;
        switch (type) {
            case "void": valid = value == UNDEFINED; break;
            case "boolean": valid = value instanceof Boolean; break;
            case "java.lang.String": valid = value == null || value instanceof String; break;
            case "[B": case "byte[]": valid = value == null || (value instanceof String && ((String)value).length() <= 8192 && (((String)value).length() & 1) == 0 && ((String)value).matches("[0-9a-fA-F]*")); break;
            case "char": valid = value instanceof String && ((String)value).length() == 1; break;
            case "long": case "int64": valid = integerRange(value, 63, true); break;
            case "uint64": valid = integerRange(value, 64, false); break;
            case "float": valid = value instanceof Double && Double.isFinite((Double)value) && Float.isFinite(((Double)value).floatValue()); break;
            case "double": valid = value instanceof Double && Double.isFinite((Double)value); break;
            case "byte": valid = numberRange(value, -128, 127); break;
            case "short": valid = numberRange(value, -32768, 32767); break;
            case "int": case "int32": valid = numberRange(value, -2147483648d, 2147483647d); break;
            case "uint32": valid = numberRange(value, 0, 4294967295d); break;
            default: if(JavaTypes.reference(type))return JavaObjectScope.checked(type,value);throw new ScriptFault("HOOK_ABI_UNSUPPORTED:" + type);
        }
        ScriptFault.require(valid, "HOOK_TYPE:" + type);
        // Conversion is at the ABI boundary, preserving IEEE negative zero and float rounding.
        return type.equals("float") ? (double)((Double)value).floatValue() : value;
    }
    /** Validate a script value without prematurely applying ABI rounding to the handler's snapshot. */
    public static Object checked(String type,Object value){abi(type,value);return value;}
    private static boolean integerRange(Object value, int bits, boolean signed) {
        if (!(value instanceof BigInteger)) return false;
        BigInteger n = (BigInteger)value, bound = BigInteger.ONE.shiftLeft(bits);
        return n.compareTo(signed ? bound.negate() : BigInteger.ZERO) >= 0 && n.compareTo(bound) < 0;
    }
    private static boolean numberRange(Object value, double low, double high) {
        return value instanceof Double && (Double)value == Math.rint((Double)value) && (Double)value >= low && (Double)value <= high;
    }
}
