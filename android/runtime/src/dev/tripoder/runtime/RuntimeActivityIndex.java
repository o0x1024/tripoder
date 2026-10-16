package dev.tripoder.runtime;

import android.content.pm.*;
import java.util.*;

/** One final platform-decoded component index shared by authority and worker. */
final class RuntimeActivityIndex {
    private RuntimeActivityIndex(){}
    static Map<String,ActivityInfo> create(ArtifactSet artifacts,PackageInfo pkg){
        Map<String,ApkManifest.Component> declared=new LinkedHashMap<>();
        for(var c:artifacts.components)if(c.type.equals("activity")||c.type.equals("activity-alias"))declared.put(c.name,c);
        Map<String,ActivityInfo> decoded=new LinkedHashMap<>();
        if(pkg.activities!=null)for(var info:pkg.activities)decoded.put(info.name,new ActivityInfo(info));
        for(var entry:decoded.entrySet()){
            var c=declared.get(entry.getKey());ActivityInfo info=entry.getValue();
            if(c==null||!pkg.packageName.equals(info.packageName)||!Objects.equals(info.targetActivity,c.targetActivity.isEmpty()?null:c.targetActivity))throw new IllegalStateException("ACTIVITY_METADATA_DISAGREEMENT:"+entry.getKey());
        }
        // GET_DISABLED_COMPONENTS is required: disabled declarations must not silently disappear.
        if(!decoded.keySet().equals(declared.keySet()))throw new IllegalStateException("ACTIVITY_METADATA_INCOMPLETE");
        return Collections.unmodifiableMap(decoded);
    }
    static ActivityInfo require(Map<String,ActivityInfo> index,String name){
        ActivityInfo info=index.get(name);if(info==null)throw new IllegalArgumentException("COMPONENT_NOT_DECLARED:activity:"+name);
        if(!info.enabled||!info.applicationInfo.enabled)throw new IllegalStateException("COMPONENT_DISABLED");
        return new ActivityInfo(info);
    }
}
