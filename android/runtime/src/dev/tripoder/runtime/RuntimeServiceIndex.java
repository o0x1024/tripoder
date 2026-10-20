package dev.tripoder.runtime;

import android.content.pm.ServiceInfo;
import java.util.*;

/** Own-package service declarations across all logical processes of the sealed installation. */
final class RuntimeServiceIndex {
    private final Map<String,ServiceInfo> components=new LinkedHashMap<>();
    RuntimeServiceIndex(ServiceInfo[] infos,List<ApkManifest.Component> declarations){
        Map<String,List<ServiceInfo>> byName=new HashMap<>();
        if(infos!=null)for(ServiceInfo candidate:infos)byName.computeIfAbsent(candidate.name,k->new ArrayList<>(1)).add(candidate);
        for(var declared:declarations)if(declared.type.equals("service")){
            ServiceInfo selected=null;
            List<ServiceInfo> candidates=byName.get(declared.name);
            if(candidates!=null)for(ServiceInfo candidate:candidates){
                if(selected!=null&&(selected.enabled!=candidate.enabled||selected.exported!=candidate.exported||!Objects.equals(selected.processName,candidate.processName)))throw new SecurityException("SERVICE_METADATA_AMBIGUOUS:"+declared.name);
                selected=candidate;
            }
            if(selected==null)throw new IllegalStateException("SERVICE_METADATA_MISSING:"+declared.name);
            ServiceInfo copy=new ServiceInfo(selected);copy.enabled=declared.enabled;copy.exported=declared.exported;
            components.put(copy.name,copy);
        }
    }
    ServiceInfo get(String name){return components.get(name);}
}
