package dev.tripoder.selfhookprobe.dynamic;
import dev.tripoder.selfhookprobe.CoverageTarget;
public final class LateCaller {
    static { CoverageTarget.initializations.incrementAndGet(); }
    public static int call(int value){return CoverageTarget.value(value);}
    public static Object local(Object value){CoverageTarget.dynamicBodies.incrementAndGet();return value;}
}
