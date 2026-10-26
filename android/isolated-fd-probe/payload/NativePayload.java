package dev.tripoder.isolatedfdpayload;

import org.json.JSONObject;

/** Loaded only from the isolated process's single sealed APK FD. */
public final class NativePayload {
    private static native long[] leafState();
    private static native long[] driverState();
    private static native long[] namedLeafState();
    private static native long[] namedDriverState();
    private static void check(JSONObject checks,String name,boolean value)throws Exception{checks.put(name,value);}
    private static void one(JSONObject checks,String root,boolean named)throws Exception{
        String prefix=named?"named":"plain",leaf=named?"owned_jni_named_leaf":"owned_jni_leaf",driver=named?"owned_jni_named_driver":"owned_jni_driver";
        System.load(root+"/lib"+leaf+".so");
        long[] initial=named?namedLeafState():leafState();
        check(checks,prefix+"-absolute-jni-onload-once",initial[1]==1&&initial[2]==1);
        check(checks,prefix+"-onload-findclass-exact-defining-class",initial[3]==1);
        System.loadLibrary(leaf);System.load(root+"/lib"+leaf+".so");
        long[] after=named?namedLeafState():leafState();
        check(checks,prefix+"-name-and-absolute-same-jni-instance",after[0]==initial[0]&&after[1]==1);
        System.loadLibrary(driver);
        long[] dependency=named?namedDriverState():driverState();
        check(checks,prefix+"-needed-shares-leaf-instance",dependency[0]==initial[0]);
        check(checks,prefix+"-needed-sees-initialized-vm",dependency[1]==1&&dependency[2]==1);
        check(checks,prefix+"-driver-onload-once-exact-class",dependency[3]==1&&dependency[4]==1&&dependency[5]==1);
    }
    public static String run(String root,boolean named)throws Exception{
        JSONObject checks=new JSONObject(),result=new JSONObject();
        try{one(checks,root,named);}
        catch(Throwable error){result.put("errorType",error.getClass().getName()).put("error",String.valueOf(error.getMessage()));}
        boolean passed=checks.length()==6;for(var names=checks.keys();names.hasNext();)passed&=checks.getBoolean(names.next());
        return result.put("passed",passed).put("checks",checks).put("manualDlopen",false).toString();
    }
    private NativePayload(){}
}
