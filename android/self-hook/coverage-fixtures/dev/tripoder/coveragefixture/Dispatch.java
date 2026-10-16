package dev.tripoder.coveragefixture;

public class Dispatch {
    public interface Contract { int run(int value); }
    public static class Base {
        public int run(int value) { return Targets.scalar(value); }
    }
    public static final class Child extends Base implements Contract {
        @Override public int run(int value) { return super.run(value); }
    }
    // Deliberately unrelated: a conservative same-signature graph must retain it.
    public static final class Unrelated {
        public int run(int value) { return value * 2; }
    }
    public static int virtualCall(Base target, int value) { return target.run(value); }
    public static int interfaceCall(Contract target, int value) { return target.run(value); }
    public static int unrelatedCall(Unrelated target, int value) { return target.run(value); }
    public static int chained(Contract target, int value) { return interfaceCall(target, value); }
    public static Object[] wideCaller(boolean a, byte b, char c, short d, int e, long f,
                                     float g, double h, int[][] i, Object[] j) {
        return Targets.wide(a, b, c, d, e, f, g, h, i, j);
    }
    public static final class ConstructorCaller {
        public final int value;
        public ConstructorCaller(int input) { value = Targets.constructorTarget(input); }
    }
    public interface Generic<T> { T result(); }
    public static final class Bridge implements Generic<String> {
        @Override public String result() { return "bridge"; }
    }
}
