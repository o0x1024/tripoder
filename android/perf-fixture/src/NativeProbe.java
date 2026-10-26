package dev.tripoder.perffixture;

/** Times the safe-copy primitives a path-policy layer can use; run natively only. */
final class NativeProbe {
    static native String run(String path);
}
