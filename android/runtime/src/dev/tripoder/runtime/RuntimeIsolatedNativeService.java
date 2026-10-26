package dev.tripoder.runtime;

import android.os.Bundle;
import java.util.Objects;
import java.util.Set;

/** A guest {@code android:nativeService=true} service, hosted in the same real isolated process as any
 * isolated service. The platform's own native-service process path is not used: the entry point is a
 * plain C function that only fills a record of callbacks, so the host owns that record and drives it. */
final class RuntimeIsolatedNativeService {
    private static final String KIND="ISOLATED_NATIVE_SERVICE_V1";
    /** Android's documented defaults when the manifest names no property. */
    private static final String DEFAULT_LIBRARY="libmain.so",DEFAULT_FUNCTION="ANativeService_onCreate";
    static final class Plan {
        final Bundle wire;
        Plan(Bundle wire){this.wire=wire.deepCopy();}
        static boolean same(Plan left,Plan right){return left==null?right==null:right!=null&&left.wire.getString("library").equals(right.wire.getString("library"))&&left.wire.getString("function").equals(right.wire.getString("function"));}
    }
    static boolean declared(ApkManifest.Component service){return !Set.of("false","0").contains(service.attributes.getOrDefault("android:nativeService","false"));}
    private static String property(ApkManifest.Component service,String name,String fallback){
        String found=null;
        for(var child:service.declaration.children)if(child.name.equals("property")&&name.equals(child.attributes.get("android:name"))){
            if(found!=null)throw new SecurityException("ISOLATED_NATIVE_SERVICE_PROPERTY_AMBIGUOUS");
            found=child.attributes.get("android:value");if(found==null||found.isEmpty()||found.length()>256)throw new SecurityException("ISOLATED_NATIVE_SERVICE_PROPERTY_REJECTED");
        }
        return found==null?fallback:found;
    }
    /** Admission, before any isolated process exists. The named library must be one the system already
     * publishes for this exact installation; nothing else can supply it. */
    static Plan capture(ApkManifest.Component service,RuntimePublishedNativeCode.Plan nativeCode){
        if(!declared(service))return null;
        String library=property(service,"android.app.PROPERTY_NATIVE_SERVICE_LIBRARY_NAME",DEFAULT_LIBRARY),function=property(service,"android.app.PROPERTY_NATIVE_SERVICE_FUNCTION_NAME",DEFAULT_FUNCTION);
        if(!library.matches("lib[A-Za-z0-9_.+\\-]+\\.so")||!function.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))throw new SecurityException("ISOLATED_NATIVE_SERVICE_NAME_REJECTED");
        if(nativeCode==null||!Objects.requireNonNull(nativeCode.wire.getStringArrayList("names")).contains(library))throw new UnsupportedOperationException("ISOLATED_NATIVE_SERVICE_LIBRARY_NOT_PUBLISHED");
        Bundle wire=new Bundle();wire.putString("kind",KIND);wire.putString("library",library);wire.putString("function",function);return new Plan(wire);
    }
    static String library(Bundle wire){return field(wire,"library","lib[A-Za-z0-9_.+\\-]+\\.so");}
    static String function(Bundle wire){return field(wire,"function","[A-Za-z_][A-Za-z0-9_]{0,127}");}
    private static String field(Bundle wire,String key,String pattern){
        String value=wire==null||!KIND.equals(wire.getString("kind"))?null:wire.getString(key);
        if(value==null||!value.matches(pattern))throw new SecurityException("ISOLATED_NATIVE_SERVICE_WIRE_REJECTED");return value;
    }
    private RuntimeIsolatedNativeService(){}
}
