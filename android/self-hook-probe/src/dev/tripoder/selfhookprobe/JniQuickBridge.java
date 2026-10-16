package dev.tripoder.selfhookprobe;
import java.lang.reflect.Method;
/** Actual managed destination for Native quick entries. No target JNI data writes. */
public final class JniQuickBridge {
    static int calls;
    public static long values(long a,long b,long c,long d,long e,long f,long g,long h,long i,long j,long k,long l,long m,long n,long o,long p){
        calls++;long value=0;for(long x:new long[]{a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p})value=Long.rotateLeft(value,3)^x;return value+77;
    }
    public static String text(Object receiver,String a,String b,long exact,double fraction){
        if(!(receiver instanceof JniRawTarget)||((JniRawTarget)receiver).identity!=77)throw new AssertionError("QUICK_RECEIVER");calls++;return a;
    }
    public static boolean exercise(JniRawTarget receiver)throws Exception{
        long[] v={Long.MIN_VALUE,-1,0,1,0x20000000000001L,0xfedcba9876543210L,7,11,13,17,19,23,29,31,37,Long.MAX_VALUE};
        long hash=0;for(long x:v)hash=Long.rotateLeft(hash,3)^x;long expected=hash+77;
        if(JniRawTarget.values(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15])!=expected
            ||JniRawTarget.fast(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15])!=expected
            ||JniRawTarget.critical(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15])!=expected)return false;
        String text=new String("direct");if(receiver.text(text,null,Long.MIN_VALUE,-0.0)!=text)return false;
        Class<?>[] types=new Class<?>[16];java.util.Arrays.fill(types,long.class);Object[] args=new Object[16];for(int i=0;i<16;i++)args[i]=v[i];
        for(String name:new String[]{"values","fast","critical"}){Method method=JniRawTarget.class.getDeclaredMethod(name,types);if(!method.invoke(null,args).equals(expected))return false;}
        if(JniRawTarget.class.getDeclaredMethod("text",String.class,String.class,long.class,double.class).invoke(receiver,text,null,Long.MIN_VALUE,-0.0)!=text)return false;
        System.gc();return true;
    }
}
