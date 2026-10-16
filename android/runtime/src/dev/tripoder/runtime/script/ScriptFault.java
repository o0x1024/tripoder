package dev.tripoder.runtime.script;

/** A script/contract failure, never a wrapper for the guest's original Throwable. */
public final class ScriptFault extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public ScriptFault(String code) { super(code); }
    public static void require(boolean condition, String code) {
        if (!condition) throw new ScriptFault(code);
    }
}
