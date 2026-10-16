package dev.tripoder.selfhookprobe.jniguest;
public final class JniSdkTarget {
    static {System.setProperty("tripoder.jni.sdk.initialized","yes");}
    public static final IllegalStateException FAILURE=new IllegalStateException("original-jni-exact");
    public static native long values(long p0,long p1,long p2,long p3,long p4,long p5,long p6,long p7,long p8,long p9,long p10,long p11,long p12,long p13,long p14,long p15,long p16,long p17,long p18,long p19,long p20,long p21,long p22,long p23,long p24,long p25,long p26,long p27,long p28,long p29,long p30,long p31);
    @dalvik.annotation.optimization.FastNative public static native long fast(long p0,long p1,long p2,long p3,long p4,long p5,long p6,long p7,long p8,long p9,long p10,long p11,long p12,long p13,long p14,long p15,long p16,long p17,long p18,long p19,long p20,long p21,long p22,long p23,long p24,long p25,long p26,long p27,long p28,long p29,long p30,long p31);
    @dalvik.annotation.optimization.CriticalNative public static native long critical(long p0,long p1,long p2,long p3,long p4,long p5,long p6,long p7,long p8,long p9,long p10,long p11,long p12,long p13,long p14,long p15,long p16,long p17,long p18,long p19,long p20,long p21,long p22,long p23,long p24,long p25,long p26,long p27,long p28,long p29,long p30,long p31);
    @dalvik.annotation.optimization.CriticalNative public static native double mixed(double d0,long p0,double d1,long p1,double d2,long p2,double d3,long p3,double d4,long p4,double d5,long p5,double d6,long p6,double d7,long p7,double d8,long p8,double d9,long p9,double d10,long p10,double d11,long p11,double d12,long p12,double d13,long p13,double d14,long p14,double d15,long p15);
    public native String text(String value,double fraction,long exact,Object[] references);
    public static native void fail();
    public static native int missing();
    public static long invoke(int mode){long[] v=new long[32];for(int i=0;i<32;i++)v[i]=Long.rotateLeft(0xfedcba9876543210L,i);
        return switch(mode){case 0->values(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15],v[16],v[17],v[18],v[19],v[20],v[21],v[22],v[23],v[24],v[25],v[26],v[27],v[28],v[29],v[30],v[31]);case 1->fast(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15],v[16],v[17],v[18],v[19],v[20],v[21],v[22],v[23],v[24],v[25],v[26],v[27],v[28],v[29],v[30],v[31]);case 2->critical(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],v[10],v[11],v[12],v[13],v[14],v[15],v[16],v[17],v[18],v[19],v[20],v[21],v[22],v[23],v[24],v[25],v[26],v[27],v[28],v[29],v[30],v[31]);default->throw new AssertionError("mode");};
    }
}
