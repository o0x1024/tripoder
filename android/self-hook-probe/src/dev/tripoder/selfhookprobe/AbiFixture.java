package dev.tripoder.selfhookprobe;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Only owned code is used for ABI and moving-reference experiments. */
public class AbiFixture {
    public static final AtomicInteger calls=new AtomicInteger();
    public static final AtomicInteger initializations=new AtomicInteger();
    public static volatile CountDownLatch entered,release;
    public static boolean bool(boolean x){calls.incrementAndGet();return x;}
    public static byte tiny(byte x){calls.incrementAndGet();return x;}
    public static short small(short x){calls.incrementAndGet();return x;}
    public static char character(char x){calls.incrementAndGet();return x;}
    public static int integer(int x){calls.incrementAndGet();return x;}
    public static long wide(long x){calls.incrementAndGet();return x;}
    public static float single(float x){calls.incrementAndGet();return x;}
    public static double decimal(double x){calls.incrementAndGet();return x;}
    public static Object reference(Object x){calls.incrementAndGet();return x;}
    public static int[] array(int[] x){calls.incrementAndGet();return x;}
    public static void nothing(Object x){calls.incrementAndGet();}
    public long instance(long x,Object token){calls.incrementAndGet();if(token!=this)throw new IllegalArgumentException("RECEIVER_IDENTITY");return x;}
    public synchronized Object monitor(Object x){calls.incrementAndGet();if(!Thread.holdsLock(this))throw new IllegalStateException("ORIGINAL_MONITOR");return x;}
    public static synchronized long monitorStatic(long x){calls.incrementAndGet();if(!Thread.holdsLock(AbiFixture.class))throw new IllegalStateException("ORIGINAL_STATIC_MONITOR");return x;}
    public static Object[] mixed(int a,long b,double c,Object d,float e,long f,double g,Object h,int i,long j,double k,float l,Object m,int n,long o,double p,float q,Object r){
        calls.incrementAndGet();return new Object[]{a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r};
    }
    public static Object failure(Object marker){calls.incrementAndGet();throw (RuntimeException)marker;}
    public static long recursive(int depth){calls.incrementAndGet();return depth==0?0:depth+recursive(depth-1);}
    public Object holding(Object value)throws InterruptedException{
        calls.incrementAndGet();entered.countDown();release.await();return value;
    }
}
