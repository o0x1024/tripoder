package dev.tripoder.selfhookprobe;

import org.json.JSONObject;

final class NoncooperativeProbe {
    private static native String runNative(String fixture, String directory);
    static JSONObject run(String fixture, String directory) throws Exception {
        return new JSONObject(runNative(fixture, directory));
    }
}
