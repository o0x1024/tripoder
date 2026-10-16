package dev.tripoder.runtime;

import android.os.*;
import java.util.*;

/** The sole control message allowed to own incoming file descriptors. */
final class RuntimeInstallRequest {
    private RuntimeInstallRequest() {}
    static void validate(Bundle input) {
        if(input==null||!"install".equals(input.get("command")))throw new SecurityException("CONTROL_FILE_DESCRIPTOR_UNSUPPORTED");
        var names=strings(input,"names");var hashes=strings(input,"sha256");
        if(names.isEmpty()||names.size()>64||names.size()!=hashes.size())throw new SecurityException("SOURCE_LIST_INVALID");
        Set<String> keys=new HashSet<>(Set.of("command","operation","space","names","sha256"));
        if(input.containsKey("expectedGeneration"))keys.add("expectedGeneration");
        for(int i=0;i<names.size();i++)keys.add("fd"+i);
        if(!keys.equals(input.keySet()))throw new SecurityException("INSTALL_FIELDS_UNSUPPORTED");
        for(String key:new String[]{"command","operation","space"})if(!(input.get(key) instanceof String))throw new SecurityException("INSTALL_FIELD_TYPE_INVALID:"+key);
        Object expected=input.get("expectedGeneration");if(expected!=null&&!(expected instanceof String))throw new SecurityException("INSTALL_FIELD_TYPE_INVALID:expectedGeneration");
        for(int i=0;i<names.size();i++){Object value=input.get("fd"+i);if(value==null||value.getClass()!=ParcelFileDescriptor.class)throw new SecurityException("SOURCE_FD_REQUIRED");}
    }
    private static ArrayList<?> strings(Bundle input,String key){
        Object value=input.get(key);if(value==null||value.getClass()!=ArrayList.class)throw new SecurityException("INSTALL_FIELD_TYPE_INVALID:"+key);
        ArrayList<?> list=(ArrayList<?>)value;for(Object item:list)if(!(item instanceof String))throw new SecurityException("INSTALL_FIELD_TYPE_INVALID:"+key);return list;
    }
    /** Close decoded protocol descriptors even if validation fails part way through the lists. */
    static void close(Bundle input){close(input,Collections.newSetFromMap(new IdentityHashMap<>()));}
    private static void close(Object value,Set<Object> seen){
        if(value==null||!seen.add(value))return;
        if(value instanceof ParcelFileDescriptor){try{((ParcelFileDescriptor)value).close();}catch(java.io.IOException error){android.util.Log.e("TripoderRuntime","Incoming descriptor close failed",error);}}
        else if(value instanceof Bundle){Bundle bundle=(Bundle)value;for(String key:bundle.keySet())close(bundle.get(key),seen);}
        else if(value instanceof ArrayList){for(Object item:(ArrayList<?>)value)close(item,seen);}
        else if(value instanceof Object[]){for(Object item:(Object[])value)close(item,seen);}
    }
}
