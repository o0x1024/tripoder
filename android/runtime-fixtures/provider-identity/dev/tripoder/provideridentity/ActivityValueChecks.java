package dev.tripoder.provideridentity;

import android.content.Intent;
import android.os.Bundle;
import org.json.*;

final class ActivityValueChecks {
    static Intent payload(Intent intent){
        Bundle b=new Bundle();b.putParcelable("nestedIntent",new Intent("owned.nested.action").setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("step","nested-forward").setComponent(new android.content.ComponentName("dev.tripoder.provideridentity","dev.tripoder.provideridentity.FlagTopActivity")).putExtra("inner",new int[]{3,5}));b.putStringArrayList("stringList",new java.util.ArrayList<>(java.util.Arrays.asList("first",null,"中文")));b.putIntegerArrayList("integerList",new java.util.ArrayList<>(java.util.Arrays.asList(-1,null,Integer.MAX_VALUE)));b.putString("nullable",null);b.putLong("long",Long.MIN_VALUE);b.putDouble("double",Double.POSITIVE_INFINITY);b.putFloat("float",-0.0f);b.putByte("byte",(byte)-12);b.putShort("short",(short)30000);b.putChar("char",'中');b.putBoolean("bool",true);
        b.putByteArray("bytes",new byte[]{-128,0,127});b.putShortArray("shorts",new short[]{-32768,32767});b.putIntArray("ints",new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE});b.putLongArray("longs",new long[]{Long.MIN_VALUE,Long.MAX_VALUE});b.putCharArray("chars",new char[]{'中','\ud800'});b.putBooleanArray("bools",new boolean[]{true,false});b.putFloatArray("floats",new float[]{-0.0f,Float.NaN});b.putDoubleArray("doubles",new double[]{Double.NEGATIVE_INFINITY,Double.NaN});b.putStringArray("strings",new String[]{"",null,"中文"});Bundle nested=new Bundle();nested.putString("s","nested");b.putBundle("nested",nested);return intent.putExtra("values",b);
    }
    static void verify(Intent intent,JSONArray checks)throws Exception{
        Bundle b=intent.getBundleExtra("values");check(checks,"activity-extras-null-and-scalar-types",b!=null&&b.containsKey("nullable")&&b.get("nullable")==null&&b.getLong("long")==Long.MIN_VALUE&&b.getDouble("double")==Double.POSITIVE_INFINITY&&Float.floatToRawIntBits(b.getFloat("float"))==Float.floatToRawIntBits(-0.0f)&&b.get("byte") instanceof Byte&&b.getByte("byte")==-12&&b.get("short") instanceof Short&&b.getShort("short")==30000&&b.getChar("char")=='中'&&b.getBoolean("bool"));
        check(checks,"activity-extras-real-parcel-arrays",java.util.Arrays.equals(b.getByteArray("bytes"),new byte[]{-128,0,127})&&java.util.Arrays.equals(b.getShortArray("shorts"),new short[]{-32768,32767})&&java.util.Arrays.equals(b.getIntArray("ints"),new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE})&&java.util.Arrays.equals(b.getLongArray("longs"),new long[]{Long.MIN_VALUE,Long.MAX_VALUE})&&java.util.Arrays.equals(b.getCharArray("chars"),new char[]{'中','\ud800'})&&java.util.Arrays.equals(b.getBooleanArray("bools"),new boolean[]{true,false})&&java.util.Arrays.equals(b.getFloatArray("floats"),new float[]{-0.0f,Float.NaN})&&java.util.Arrays.equals(b.getDoubleArray("doubles"),new double[]{Double.NEGATIVE_INFINITY,Double.NaN})&&java.util.Arrays.equals(b.getStringArray("strings"),new String[]{"",null,"中文"}));
        check(checks,"activity-extras-nested-bundle",b.getBundle("nested").getString("s").equals("nested"));
        Intent nested=b.getParcelable("nestedIntent",Intent.class);check(checks,"activity-extras-real-platform-nested-intent",nested!=null&&"owned.nested.action".equals(nested.getAction())&&"dev.tripoder.provideridentity.FlagTopActivity".equals(nested.getComponent().getClassName())&&java.util.Arrays.equals(nested.getIntArrayExtra("inner"),new int[]{3,5}));
        check(checks,"activity-extras-real-string-and-integer-lists",b.getStringArrayList("stringList").equals(java.util.Arrays.asList("first",null,"中文"))&&b.getIntegerArrayList("integerList").equals(java.util.Arrays.asList(-1,null,Integer.MAX_VALUE)));
    }
    private static void check(JSONArray checks,String name,boolean value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value));if(!value)throw new AssertionError(name);}
    private ActivityValueChecks(){}
}
