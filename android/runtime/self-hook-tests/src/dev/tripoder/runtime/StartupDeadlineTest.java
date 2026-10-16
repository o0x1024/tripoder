package dev.tripoder.runtime;

public final class StartupDeadlineTest {
    public static void main(String[] ignored){
        long reserved=1000,deadline=RuntimeStartupDeadline.after(reserved);
        if(RuntimeStartupDeadline.remaining(deadline,reserved)!=30000)throw new AssertionError();
        // Hook construction consumes 14 seconds; nested provider startup uses
        // the remaining 16 seconds, rather than resetting or imposing 8 seconds.
        if(RuntimeStartupDeadline.remaining(deadline,15000)!=16000)throw new AssertionError();
        if(RuntimeStartupDeadline.remaining(deadline,30000)!=1000)throw new AssertionError();
        if(RuntimeStartupDeadline.remaining(deadline,31000)!=0)throw new AssertionError();
        if(RuntimeStartupDeadline.remaining(deadline,32000)!=0)throw new AssertionError();
        for(long invalid:new long[]{0,-1,RuntimeStartupDeadline.after(reserved)+1}){
            try{RuntimeStartupDeadline.remaining(invalid,reserved);throw new AssertionError();}
            catch(SecurityException expected){}
        }
        System.out.println("StartupDeadlineTest: 8 passed");
    }
}
