package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Exact Java scalar boundary; Method/Class/receiver objects never enter scripts. */
public final class JavaHookValues {
    private JavaHookValues(){}
    public static void requireType(Class<?> type,boolean result){
        if(JavaTypes.valid(type.getName(),result))return;
        throw new IllegalArgumentException("HOOK_JAVA_ABI_UNSUPPORTED:"+type.getName());
    }
    public static void requireInvocationType(Class<?> type,boolean result){
        if(!JavaTypes.invocation(type.getName(),result))throw new HookPreflightRejected("INVOKE_JAVA_ABI_UNSUPPORTED:"+type.getName());
    }
    public static Object decode(Class<?> type,Object value){
        requireType(type,true);Object decoded;
        if(type==void.class){if(value!=null)throw invalid(type);decoded=ScriptValues.UNDEFINED;}
        else if(type==String.class){if(value!=null&&!(value instanceof String))throw invalid(type);decoded=value;}
        else if(type==byte[].class){
            if(value!=null&&!(value instanceof byte[]))throw invalid(type);
            if(value==null)decoded=null;
            else{byte[] b=(byte[])value;ScriptFault.require(b.length<=4096,"HOOK_TYPE:[B");decoded=java.util.HexFormat.of().formatHex(b);}
        }
        else if(!type.isPrimitive()){decoded=JavaObjectScope.wrap(type,value);}
        else if(type==boolean.class){if(!(value instanceof Boolean))throw invalid(type);decoded=value;}
        else if(type==char.class){if(!(value instanceof Character))throw invalid(type);decoded=String.valueOf((Character)value);}
        else if(type==long.class){if(!(value instanceof Long))throw invalid(type);decoded=BigInteger.valueOf((Long)value);}
        else {
            Class<?> box=type==byte.class?Byte.class:type==short.class?Short.class:type==int.class?Integer.class:type==float.class?Float.class:Double.class;
            if(!box.isInstance(value))throw invalid(type);decoded=((Number)value).doubleValue();
        }
        return ScriptValues.checked(type.getName(),decoded);
    }
    public static Object encode(Class<?> type,Object value){
        requireType(type,true);Object checked=ScriptValues.abi(type.getName(),value);
        if(!type.isPrimitive()&&type!=String.class&&type!=byte[].class)return JavaObjectScope.unwrap(type,checked);
        if(type==void.class)return null;if(type==String.class||type==boolean.class)return checked;
        if(type==byte[].class)return checked==null?null:java.util.HexFormat.of().parseHex((String)checked);
        if(type==char.class)return ((String)checked).charAt(0);if(type==long.class)return ((BigInteger)checked).longValueExact();
        if(type==byte.class)return ((Double)checked).byteValue();if(type==short.class)return ((Double)checked).shortValue();
        if(type==int.class)return ((Double)checked).intValue();if(type==float.class)return ((Double)checked).floatValue();return checked;
    }
    public static List<Object> decodeArguments(Class<?>[] types,Object[] values){
        if(types.length!=values.length)throw new IllegalArgumentException("HOOK_JAVA_ARITY");List<Object> out=new ArrayList<>(values.length);
        for(int i=0;i<values.length;i++){requireType(types[i],false);out.add(decode(types[i],values[i]));}return java.util.Collections.unmodifiableList(out);
    }
    public static Object[] encodeArguments(Class<?>[] types,List<Object> values){
        if(types.length!=values.size())throw new IllegalArgumentException("HOOK_JAVA_ARITY");Object[] out=new Object[types.length];
        for(int i=0;i<out.length;i++){requireType(types[i],false);out[i]=encode(types[i],values.get(i));}return out;
    }
    private static IllegalArgumentException invalid(Class<?> type){return new IllegalArgumentException("HOOK_JAVA_BOXED_TYPE:"+type.getName());}
}
