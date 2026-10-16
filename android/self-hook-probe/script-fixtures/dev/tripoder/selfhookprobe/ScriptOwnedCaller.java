package dev.tripoder.selfhookprobe;

/** The only declared managed callers in this fixture's accepted coverage. */
public final class ScriptOwnedCaller {
    private ScriptOwnedCaller(){}
    public static int callScriptInt(int value){return ScriptOwnedFixture.scriptIntOwned(value);}
    public static long callScriptLong(long value){return ScriptOwnedFixture.scriptLongOwned(value);}
    public static float callScriptFloat(float value){return ScriptOwnedFixture.scriptFloatOwned(value);}
    public static int callScriptFailure(int value){return ScriptOwnedFixture.scriptFailureOwned(value);}
}
