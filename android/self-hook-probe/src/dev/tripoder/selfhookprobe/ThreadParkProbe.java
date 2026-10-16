package dev.tripoder.selfhookprobe;

import org.json.JSONObject;

/** Native thread parking only. No target code or ART metadata changes in this stage. */
final class ThreadParkProbe {
    private static native String runNative();
    static JSONObject run() throws Exception { return new JSONObject(runNative()); }
}
