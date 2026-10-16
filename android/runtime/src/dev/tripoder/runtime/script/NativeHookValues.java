package dev.tripoder.runtime.script;
import dev.tripoder.selfhook.NativeHook;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** Exact approved Native scalar values. Bits/addresses/handles never enter the
 * interpreter; only its existing Number/BigInt/undefined value domain does. */
public final class NativeHookValues {
    private NativeHookValues(){}
    public static int type(String type,boolean result){
        switch(type){
            case "int32":return NativeHook.I32;case "uint32":return NativeHook.U32;
            case "int64":return NativeHook.I64;case "uint64":return NativeHook.U64;
            case "float":return NativeHook.F32;case "double":return NativeHook.F64;
            case "void":if(result)return NativeHook.VOID;break;
            default:break;
        }throw new ScriptFault("HOOK_NATIVE_ABI_UNSUPPORTED:"+type);
    }
    public static Object decode(String type,long bits){
        int code=type(type,true);Object value;
        if(code==NativeHook.I32||code==NativeHook.U32||code==NativeHook.F32)ScriptFault.require((bits>>>32)==0,"HOOK_NATIVE_NONCANONICAL_32");
        switch(code){
            case NativeHook.VOID:ScriptFault.require(bits==0,"HOOK_NATIVE_VOID_BITS");value=ScriptValues.UNDEFINED;break;
            case NativeHook.I32:value=(double)(int)bits;break;
            case NativeHook.U32:value=(double)bits;break;
            case NativeHook.I64:value=BigInteger.valueOf(bits);break;
            case NativeHook.U64:value=bits>=0?BigInteger.valueOf(bits):BigInteger.valueOf(bits&Long.MAX_VALUE).setBit(63);break;
            case NativeHook.F32:value=(double)Float.intBitsToFloat((int)bits);break;
            case NativeHook.F64:value=Double.longBitsToDouble(bits);break;
            default:throw new AssertionError(code);
        }return ScriptValues.checked(type,value);
    }
    public static long encode(String type,Object value){
        int code=type(type,true);Object checked=ScriptValues.abi(type,value);
        switch(code){
            case NativeHook.VOID:return 0;
            case NativeHook.I32:return ((Double)checked).intValue()&0xffffffffL;
            case NativeHook.U32:return ((Double)checked).longValue();
            case NativeHook.I64:case NativeHook.U64:return ((BigInteger)checked).longValue();
            case NativeHook.F32:return Float.floatToRawIntBits(((Double)checked).floatValue())&0xffffffffL;
            case NativeHook.F64:return Double.doubleToRawLongBits((Double)checked);
            default:throw new AssertionError(code);
        }
    }
    public static List<Object> decodeArguments(List<String> types,long[] values){
        ScriptFault.require(types!=null&&values!=null&&types.size()==values.length&&values.length<=16,"HOOK_NATIVE_ARITY");
        List<Object> result=new ArrayList<>(values.length);
        for(int i=0;i<values.length;i++){type(types.get(i),false);result.add(decode(types.get(i),values[i]));}return Collections.unmodifiableList(result);
    }
    public static long[] encodeArguments(List<String> types,List<Object> values){
        ScriptFault.require(types!=null&&values!=null&&types.size()==values.size()&&types.size()<=16,"HOOK_NATIVE_ARITY");
        long[] result=new long[values.size()];for(int i=0;i<result.length;i++){type(types.get(i),false);result[i]=encode(types.get(i),values.get(i));}return result;
    }
}
