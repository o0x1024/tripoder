package dev.tripoder.selfhook.dex;

/** Compiled by javac and D8 as an independent DEX-format oracle. */
public final class DexOracle {
    public static int target(int value) { return value + 1; }
    public static int caller(int value) { return target(value); }
    public static int outer(int value) { return caller(value); }
    public static byte[] cloned(byte[] value) { return value.clone(); }
    public static int payloads(int value) {
        short[] data = {0x71, 0, 0, 0x77, 0, 0};
        switch (value) {
            case 0: return data[0];
            case 100: return data[1];
            case 10000: return data[2];
            default: return data[3];
        }
    }
}
