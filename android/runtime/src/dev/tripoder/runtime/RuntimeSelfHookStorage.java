package dev.tripoder.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;

/** The fixed libc path and IO policy share one native process owner. */
final class RuntimeSelfHookStorage {
    private RuntimeSelfHookStorage(){}
    private static native void installNative(byte[][] paths);
    static void install()throws Exception{
        JSONObject spec=new JSONObject(RuntimeBridge.nativeStorageSpec());
        java.util.Set<String> fields=new java.util.HashSet<>();spec.keys().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("data","dataCanonical","artifacts","artifactsCanonical","package")))
            throw new IllegalArgumentException("NATIVE_STORAGE_SPEC_FIELDS");
        String[] keys={"data","dataCanonical","artifacts","artifactsCanonical","package"};
        byte[][] values=new byte[keys.length][];
        for(int i=0;i<keys.length;i++){
            String value=spec.getString(keys[i]);
            if(value.isEmpty()||value.indexOf('\0')>=0)throw new IllegalArgumentException("NATIVE_STORAGE_SPEC_VALUE");
            values[i]=value.getBytes(StandardCharsets.UTF_8);
        }
        installNative(values);
    }
}
