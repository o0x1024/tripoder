package dev.tripoder.runtime;

import android.content.IntentFilter;
import java.util.Set;

/** Copies one registration's real system filter without widening independent filters into a cross product. */
final class RuntimeReceiverFilters {
    static IntentFilter system(IntentFilter source,Set<String> actions){
        IntentFilter out=new IntentFilter();for(String action:actions)out.addAction(action);
        for(int i=0;i<source.countCategories();i++)out.addCategory(source.getCategory(i));
        for(int i=0;i<source.countDataSchemes();i++)out.addDataScheme(source.getDataScheme(i));
        for(int i=0;i<source.countDataTypes();i++)try{out.addDataType(source.getDataType(i));}catch(IntentFilter.MalformedMimeTypeException impossible){throw new IllegalArgumentException(impossible);}
        for(int i=0;i<source.countDataAuthorities();i++){var authority=source.getDataAuthority(i);out.addDataAuthority(authority.getHost(),authority.getPort()<0?null:String.valueOf(authority.getPort()));}
        for(int i=0;i<source.countDataPaths();i++){var pattern=source.getDataPath(i);out.addDataPath(pattern.getPath(),pattern.getType());}
        for(int i=0;i<source.countDataSchemeSpecificParts();i++){var pattern=source.getDataSchemeSpecificPart(i);out.addDataSchemeSpecificPart(pattern.getPath(),pattern.getType());}
        out.setPriority(source.getPriority());return out;
    }
    private RuntimeReceiverFilters(){}
}
