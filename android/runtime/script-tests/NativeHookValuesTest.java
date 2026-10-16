package dev.tripoder.runtime.script;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import static dev.tripoder.runtime.script.ScriptContractTest.*;
final class NativeHookValuesTest {
    static void run()throws Throwable {
        String[] types={"int32","int32","uint32","uint32","int64","int64","uint64","uint64","float","float","float","double","double","double","void"};
        long[] bits={0x80000000L,0xffffffffL,0,0xffffffffL,Long.MIN_VALUE,Long.MAX_VALUE,Long.MIN_VALUE,-1L,0x80000000L,1,0x7f7fffffL,Long.MIN_VALUE,1,0x7fefffffffffffffL,0};
        for(int i=0;i<types.length;i++)check(NativeHookValues.encode(types[i],NativeHookValues.decode(types[i],bits[i]))==bits[i],"Native raw round trip "+i);
        check(NativeHookValues.decode("uint64",-1L).equals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)),"uint64 never decoded through signed or double");
        check(NativeHookValues.encode("uint64",BigInteger.ONE.shiftLeft(63))==Long.MIN_VALUE,"uint64 sign bit exact");
        check(NativeHookValues.encode("float",16777217d)==Float.floatToRawIntBits(16777216f),"Native float rounds only at encoding");
        check(Double.doubleToRawLongBits((Double)NativeHookValues.decode("float",0x80000000L))==Long.MIN_VALUE,"float negative zero");
        rejects(()->NativeHookValues.decode("int32",-1L),"HOOK_NATIVE_NONCANONICAL");
        rejects(()->NativeHookValues.decode("uint32",0x100000000L),"HOOK_NATIVE_NONCANONICAL");
        rejects(()->NativeHookValues.decode("void",1),"HOOK_NATIVE_VOID_BITS");
        rejects(()->NativeHookValues.decode("float",0x7fc01234L),"HOOK_TYPE");
        rejects(()->NativeHookValues.decode("double",0x7ff0000000000000L),"HOOK_TYPE");
        rejects(()->NativeHookValues.encode("int32",2147483648d),"HOOK_TYPE");
        rejects(()->NativeHookValues.encode("int32",1.5d),"HOOK_TYPE");
        rejects(()->NativeHookValues.encode("uint32",-1d),"HOOK_TYPE");
        rejects(()->NativeHookValues.encode("int64",9007199254740992d),"HOOK_TYPE");
        rejects(()->NativeHookValues.encode("int64",BigInteger.ONE.shiftLeft(63)),"HOOK_TYPE");
        rejects(()->NativeHookValues.encode("uint64",BigInteger.ONE.negate()),"HOOK_TYPE");
        rejects(()->NativeHookValues.encode("uint64",BigInteger.ONE.shiftLeft(64)),"HOOK_TYPE");
        rejects(()->NativeHookValues.encode("float",Double.MAX_VALUE),"HOOK_TYPE");
        rejects(()->NativeHookValues.type("pointer",true),"HOOK_NATIVE_ABI_UNSUPPORTED");
        rejects(()->NativeHookValues.type("void",false),"HOOK_NATIVE_ABI_UNSUPPORTED");
        rejects(()->NativeHookValues.decodeArguments(List.of("uint64"),new long[0]),"HOOK_NATIVE_ARITY");
        long[] raw={-1L,0x80000000L};List<String> arguments=List.of("uint64","float");
        List<Object> decoded=NativeHookValues.decodeArguments(arguments,raw);raw[0]=0;
        check(decoded.get(0).equals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)),"Native arguments detached");
        check(Arrays.equals(NativeHookValues.encodeArguments(arguments,decoded),new long[]{-1L,0x80000000L}),"Native argument ABI round trip");
        try{decoded.set(0,BigInteger.ZERO);throw new AssertionError("Native argument snapshot mutable");}catch(UnsupportedOperationException expected){check(true,"Native args immutable");}
    }
}
