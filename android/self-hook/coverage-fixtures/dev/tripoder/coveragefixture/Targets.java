package dev.tripoder.coveragefixture;

public final class Targets {
    static { System.setProperty("tripoder.coverage.initialized", "yes"); }
    public static int scalar(int value) { return value + 1; }
    public static Object[] wide(boolean a, byte b, char c, short d, int e, long f,
                                float g, double h, int[][] i, Object[] j) {
        return j;
    }
    public static int constructorTarget(int value) { return value + 2; }
    public static native int nativeEntry(int value);
    private Targets() { }
}
