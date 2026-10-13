package dev.tripoder.fixture;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

// This APK has no dependency on the host or on Frida. It can run on its own.
public final class GuestProbe {
    static { System.loadLibrary("guestfixture"); }
    public static String greet(String name) { return "guest-original:" + name; }
    public static native int nativeDelay(int base);
    public static native String nativeBuildId();
    public static int incrementCounter(String directory) throws Exception {
        File file = new File(directory, "counter.txt");
        int previous = file.exists() ? Integer.parseInt(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : 0;
        int next = Math.addExact(previous, 1);
        Files.write(file.toPath(), Integer.toString(next).getBytes(StandardCharsets.UTF_8));
        return next;
    }
}
