package dev.tripoder.selfhookprobe;

import dev.tripoder.runtime.script.ScriptValues;
import dev.tripoder.runtime.script.JavaHookValues;
import java.math.BigInteger;
import java.util.*;

/** Explicit SDK scalar conversion for these four owned signatures only. */
final class ScriptFixtureCodec {
    static Object decode(String type,Object value){
        return JavaHookValues.decode(primitive(type),value);
    }
    static Object encode(String type,Object value){
        return JavaHookValues.encode(primitive(type),value);
    }
    static List<Object> decodeArguments(String type,Object[] values){ProbeFiles.require(values.length==1,"OWNED_SCRIPT_ARITY");return Collections.singletonList(decode(type,values[0]));}
    static Object[] encodeArguments(String type,List<Object> values){ProbeFiles.require(values.size()==1,"OWNED_SCRIPT_ARITY");return new Object[]{encode(type,values.get(0))};}
    private static Class<?> primitive(String type){switch(type){case "int":return int.class;case "long":return long.class;case "float":return float.class;default:throw new IllegalArgumentException("OWNED_SCRIPT_ABI_NOT_COVERED:"+type);}}
}
