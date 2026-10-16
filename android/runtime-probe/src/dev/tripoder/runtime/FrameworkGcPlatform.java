package dev.tripoder.runtime;

/** Test APK only, using the production fixed-platform admission before loading its core. */
public final class FrameworkGcPlatform {
    public static void prepare(){RuntimePlatform.requireValidated();System.loadLibrary("tripoderselfhook");}
    private FrameworkGcPlatform(){}
}
