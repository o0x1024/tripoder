package dev.tripoder.selfhookprobe;
public final class JniRawCallback {
    public static boolean throwing;
    public static final IllegalStateException FAILURE=new IllegalStateException("OWNED_JNI_CALLBACK_FAILURE");
    private static void frame(String method){
        for(StackTraceElement entry:Thread.currentThread().getStackTrace())if(entry.getClassName().equals(JniRawTarget.class.getName())&&entry.getMethodName().equals(method))return;
        throw new IllegalStateException("JNI_GENERIC_FRAME_NOT_WALKED:"+method);
    }
    public static long values(int mode,long original,long[] args){
        frame(new String[]{"values","fast","critical"}[mode]);System.gc();
        long hash=0;for(long value:args)hash=Long.rotateLeft(hash,3)^value;
        if(args.length!=16||hash!=original)throw new IllegalStateException("JNI_RAW_ARGUMENTS");
        if(throwing)throw FAILURE;return original+41;
    }
    public static String text(Object receiver,String first,String second,long exact,double fraction,String original){
        frame("text");System.gc();
        if(((JniRawTarget)receiver).identity!=77||!first.equals("α\0🚀")||exact!=0xfedcba9876543210L||!original.equals("native"))throw new IllegalStateException("JNI_REFERENCE_ROOTS");
        return original+":"+first+":"+second+":"+exact+":"+fraction;
    }
    public static boolean textMatches(String result,boolean secondNull){
        return result.equals("native:α\0🚀:"+(secondNull?"null":"β")+":"+0xfedcba9876543210L+":"+(secondNull?"-0.0":"1.25"));
    }
}
