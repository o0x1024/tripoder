package dev.tripoder.nativetlsfixture;

public final class NativeClient {
    static {System.loadLibrary("ownednativetls");}
    public static void load(){}
    public static native void probe()throws java.io.IOException;
    public static native String[] exchange(int port,byte[] ca,byte[] cert,byte[] key,String[] requests,int[] responseLengths,boolean pipelined)throws java.io.IOException;
}
