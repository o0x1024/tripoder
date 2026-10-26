package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.List;
import static dev.tripoder.runtime.script.ScriptContractTest.*;

final class JavaHookValuesTest {
    static void run()throws Throwable {
        Class<?>[] types={boolean.class,boolean.class,byte.class,byte.class,short.class,short.class,int.class,int.class,
            long.class,long.class,long.class,char.class,char.class,float.class,float.class,float.class,double.class,double.class,double.class,String.class,String.class,void.class};
        Object[] values={false,true,Byte.MIN_VALUE,Byte.MAX_VALUE,Short.MIN_VALUE,Short.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,
            Long.MIN_VALUE,Long.MAX_VALUE,9007199254740993L,'\ud800','中',-0.0f,Float.MIN_VALUE,Float.MAX_VALUE,-0.0d,Double.MIN_VALUE,Double.MAX_VALUE,null,"owned-😀",null};
        for(int i=0;i<types.length;i++) {
            Object decoded=JavaHookValues.decode(types[i],values[i]);Object encoded=JavaHookValues.encode(types[i],decoded);
            check(java.util.Objects.equals(encoded,values[i]),"Java boxed round trip "+i);
            if(types[i]==void.class)check(decoded==ScriptValues.UNDEFINED,"void script sentinel");
        }
        check(JavaHookValues.decode(long.class,9007199254740993L).equals(new BigInteger("9007199254740993")),"long decode never double");
        check(JavaHookValues.encode(float.class,16777217d).equals(16777216f),"float encoding boundary rounds once");
        rejects(()->JavaHookValues.encode(byte.class,128d),"HOOK_TYPE");
        rejects(()->JavaHookValues.encode(int.class,1.5d),"HOOK_TYPE");
        rejects(()->JavaHookValues.encode(long.class,BigInteger.ONE.shiftLeft(63)),"HOOK_TYPE");
        rejects(()->JavaHookValues.encode(long.class,9007199254740992d),"HOOK_TYPE");
        rejects(()->JavaHookValues.decode(float.class,Float.NaN),"HOOK_TYPE");
        rejects(()->JavaHookValues.decode(double.class,Double.POSITIVE_INFINITY),"HOOK_TYPE");
        rejects(()->JavaHookValues.encode(char.class,"😀"),"HOOK_TYPE");
        for(Class<?> invalid:new Class<?>[]{Object.class,Integer.class,int[].class,Class.class}) {
            try{JavaHookValues.requireInvocationType(invalid,false);throw new AssertionError("invocation object type accepted");}
            catch(IllegalArgumentException expected){check(true,"host type rejected");}
        }
        try{JavaHookValues.decode(int.class,1L);throw new AssertionError("wrong boxed type");}
        catch(IllegalArgumentException expected){check(true,"boxed integer kind exact");}
        Class<?>[] argumentTypes={long.class,char.class,String.class};Object[] arguments={Long.MAX_VALUE,'x',null};
        List<Object> decoded=JavaHookValues.decodeArguments(argumentTypes,arguments);arguments[0]=0L;
        check(decoded.get(0).equals(BigInteger.valueOf(Long.MAX_VALUE)),"argument snapshot detached");
        check(java.util.Arrays.equals(JavaHookValues.encodeArguments(argumentTypes,decoded),new Object[]{Long.MAX_VALUE,'x',null}),"typed arguments restore boxes");
        try{decoded.set(0,BigInteger.ZERO);throw new AssertionError("mutable decoded args");}
        catch(UnsupportedOperationException expected){check(true,"immutable argument snapshot");}
        JavaHookValues.requireType(byte[].class,false);
        byte[] rawBytes=new byte[]{0x00,0x0f,(byte)0xde,(byte)0xad,(byte)0xbe,(byte)0xef};
        Object hexDecoded=JavaHookValues.decode(byte[].class,rawBytes);
        check("000fdeadbeef".equals(hexDecoded),"byte[] decode lowercase hex");
        byte[] hexEncoded=(byte[])JavaHookValues.encode(byte[].class,"DEADBEEF");
        check(java.util.Arrays.equals(hexEncoded,new byte[]{(byte)0xde,(byte)0xad,(byte)0xbe,(byte)0xef}),"byte[] encode uppercase hex");
        check(JavaHookValues.decode(byte[].class,null)==null&&JavaHookValues.encode(byte[].class,null)==null,"byte[] null roundtrip");
        rejects(()->JavaHookValues.encode(byte[].class,"abc"),"HOOK_TYPE");
        rejects(()->JavaHookValues.encode(byte[].class,"zz"),"HOOK_TYPE");
    }
}
