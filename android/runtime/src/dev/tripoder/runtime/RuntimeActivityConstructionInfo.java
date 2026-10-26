package dev.tripoder.runtime;

import android.content.pm.ActivityInfo;
import java.util.Objects;

/** The client uses Android's existing alias construction field. Server task
 * identity, physical APK ownership and the delivered Intent are unchanged. */
final class RuntimeActivityConstructionInfo {
    static ActivityInfo project(ActivityInfo source,String host,int uid,String stub,String target){
        String canonical=RuntimeActivitySlots.target(stub);
        if(source==null||source.getClass()!=ActivityInfo.class||!host.equals(source.packageName)
            ||source.applicationInfo==null||!host.equals(source.applicationInfo.packageName)||source.applicationInfo.uid!=uid
            ||!Objects.equals(source.name,stub)&&!Objects.equals(source.name,canonical)
            ||source.targetActivity!=null&&!source.targetActivity.equals(canonical)&&!source.targetActivity.equals(target))throw new SecurityException("ACTIVITY_CONSTRUCTION_PHYSICAL_INFO_MISMATCH");
        if(target==null||target.isEmpty()||RuntimeActivitySlots.owns(target))throw new SecurityException("ACTIVITY_CONSTRUCTION_GUEST_TARGET_REQUIRED");
        ActivityInfo projected=new ActivityInfo(source);projected.targetActivity=target;return projected;
    }
    private RuntimeActivityConstructionInfo(){}
}
