package dev.tripoder.hooklab;

public final class AgentBridge {
    public static android.app.Application componentApplication;
    public static boolean ownsActivity(Object value) { return guestLoader != null && value != null && value.getClass().getClassLoader() == guestLoader; }
    public static android.app.Application application() { return componentApplication; }
    public static String observedIdentity = "{}";
    public static ClassLoader guestLoader;
    public static ClassLoader loader() { return guestLoader; }
    public static String identity() { return observedIdentity; }
    // Replaced by the embedded agent. This method never emulates successful Hooks.
    public static String dispatch(String command) { return "{\"ok\":false,\"error\":\"agent-not-ready\"}"; }
}
