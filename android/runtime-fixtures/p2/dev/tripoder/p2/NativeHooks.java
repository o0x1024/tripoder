package dev.tripoder.p2;

import java.io.File;
import java.nio.file.Files;
import org.json.*;

final class NativeHooks {
    static {System.loadLibrary("p0fixture");}
    static native void open(String path,String audit);
    static native void close();static native void reset();static native int mapped();
    static native int count(int index);static native int add(int a,int b);static native long exact(long value);static native double fraction(double value);static native int error(int value);
    static JSONObject observe(String mode,String directory,File files)throws Exception{
        File audit=new File(files,"hook-native-audit");JSONObject out=new JSONObject();
        switch(mode){
            case "native-load":open(new File(directory,"libtripoderownedhooks.so").toString(),audit.toString());out.put("loaded",true).put("mappedCount",mapped());break;
            case "native-unload":close();out.put("closeSucceeded",true).put("mappedCount",mapped());break;
            case "native-audit":out.put("originalCount",Integer.parseInt(Files.readString(audit.toPath())));break;
            case "native-failure":reset();out.put("unexpectedReturn",error(7));break;
            case "native-calls":
                reset();Hooks.Target.addCalls.set(0);out.put("javaValue",Hooks.Target.add(2,3)).put("javaCalls",Hooks.Target.addCalls.get());
                out.put("sum",add(2,3)).put("exact",Long.toUnsignedString(exact(-2L))).put("fraction",fraction(2.25));
                out.put("sumCalls",count(0)).put("exactCalls",count(1)).put("fractionCalls",count(2));break;
            default:throw new IllegalArgumentException("OWNED_NATIVE_MODE_UNKNOWN");
        }
        return out;
    }
    private NativeHooks(){}
}
