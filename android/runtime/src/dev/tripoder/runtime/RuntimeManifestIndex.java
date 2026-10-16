package dev.tripoder.runtime;

import android.content.pm.*;
import java.util.*;

/** Device-effective flags decoded only from the sealed APK, never a host installed package. */
final class RuntimeManifestIndex {
    static List<ApkManifest.Component> resolve(ArtifactSet set,PackageInfo pkg){
        if(set.parts.size()!=1)throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        if(!set.packageName.equals(pkg.packageName)||pkg.applicationInfo==null)throw new SecurityException("MANIFEST_PACKAGE_MISMATCH");
        List<ApkManifest.Component> out=new ArrayList<>();
        for(var declared:set.components){
            ComponentInfo[] infos=switch(declared.type){case "activity","activity-alias"->pkg.activities;case "receiver"->pkg.receivers;case "service"->pkg.services;case "provider"->pkg.providers;default->throw new IllegalStateException("RUNTIME_COMPONENT_SET_NOT_SUPPORTED");};
            ComponentInfo found=null;
            if(infos!=null)for(ComponentInfo info:infos){
                if(!declared.name.equals(info.name))continue;
                if(info instanceof ProviderInfo&&!declared.authorities.equals(((ProviderInfo)info).authority))continue;
                if(info instanceof ActivityInfo&&!Objects.equals(declared.targetActivity.isEmpty()?null:declared.targetActivity,((ActivityInfo)info).targetActivity))continue;
                if(!pkg.packageName.equals(info.packageName))throw new SecurityException("MANIFEST_COMPONENT_PACKAGE_MISMATCH");
                if(found!=null&&(found.enabled!=info.enabled||found.exported!=info.exported||!Objects.equals(found.processName,info.processName)))throw new SecurityException("MANIFEST_COMPONENT_AMBIGUOUS:"+declared.name);
                found=info;
            }
            if(found==null)throw new IllegalStateException("MANIFEST_COMPONENT_METADATA_MISSING:"+declared.type+":"+declared.name);
            out.add(new ApkManifest.Component(declared,found.exported,found.enabled&&pkg.applicationInfo.enabled));
        }
        return Collections.unmodifiableList(out);
    }
    private RuntimeManifestIndex(){}
}
