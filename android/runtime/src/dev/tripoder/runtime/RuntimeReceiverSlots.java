package dev.tripoder.runtime;

import android.content.*;

/** Factory returns the guest instance. A stub executing would violate the delivery contract. */
public class RuntimeReceiverSlots extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){throw new IllegalStateException("RECEIVER_FACTORY_REQUIRED");}
    static Class<?> type(int index){return new Class<?>[]{R0.class,R1.class,R2.class,R3.class,R4.class,R5.class,R6.class,R7.class}[index];}
    static int index(String name){for(int i=0;i<RuntimeSlot.CAPACITY;i++)if(type(i).getName().equals(name))return i;throw new SecurityException("RECEIVER_STUB_INVALID");}
    static boolean isStub(String name){for(int i=0;i<RuntimeSlot.CAPACITY;i++)if(type(i).getName().equals(name))return true;return false;}
    public static final class R0 extends RuntimeReceiverSlots{} public static final class R1 extends RuntimeReceiverSlots{}
    public static final class R2 extends RuntimeReceiverSlots{} public static final class R3 extends RuntimeReceiverSlots{}
    public static final class R4 extends RuntimeReceiverSlots{} public static final class R5 extends RuntimeReceiverSlots{}
    public static final class R6 extends RuntimeReceiverSlots{} public static final class R7 extends RuntimeReceiverSlots{}
}
