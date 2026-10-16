package dev.tripoder.coveragefixture;

public final class CrossDex {
    public static int entry(int value) { return Targets.scalar(value); }
    public static int ancestor(int value) { return entry(value); }
}
