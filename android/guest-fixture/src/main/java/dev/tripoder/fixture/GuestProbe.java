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
    public static native int nativeTryKillExternal(int pid);
    public static native int nativeTryConnectExternal();

    public static final class TokenBox {
        private final String secretKey;
        private final int version;
        private final android.os.Bundle metadata;
        public TokenBox(String secretKey, int version) {
            this.secretKey = secretKey;
            this.version = version;
            this.metadata = new android.os.Bundle();
            this.metadata.putString("keyPrefix", secretKey.substring(0, Math.min(4, secretKey.length())));
            this.metadata.putInt("ver", version);
        }
        public String computeTag(String input) {
            return secretKey + "#v" + version + ":" + input;
        }
    }

    public static String inspectBundle(android.os.Bundle bundle) {
        return bundle == null ? "null" : "keys=" + bundle.size();
    }
    public static String runBundleCheck(String user, int role) {
        android.os.Bundle b = new android.os.Bundle();
        b.putString("user", user);
        b.putInt("role", role);
        return inspectBundle(b);
    }
    public static int incrementCounter(String directory) throws Exception {
        File file = new File(directory, "counter.txt");
        int previous = file.exists() ? Integer.parseInt(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : 0;
        int next = Math.addExact(previous, 1);
        Files.write(file.toPath(), Integer.toString(next).getBytes(StandardCharsets.UTF_8));
        return next;
    }
}
