package dev.tripoder.runtime;
/** Own host state fixture; no Android Context is instantiated or used. */
public final class RuntimeEngine {
    public static volatile RuntimeEngine current;
    public final android.content.Context host=null;
}
