package dev.tripoder.selfhook;

/** Test double for the one irreversible native publication boundary. */
public final class NativeModules {
    public static int calls;
    public static String[] paths;
    public static boolean reject;
    public static void initialize(String[] values){
        calls++;paths=values.clone();
        if(reject)throw new IllegalStateException("OWNED_NATIVE_REJECTION");
    }
    public static void reset(){calls=0;paths=null;reject=false;}
}
