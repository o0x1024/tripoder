package dev.tripoder.runtime;

import android.content.pm.ServiceInfo;
import java.util.*;

/** Own-package service declarations across all logical processes of the sealed installation. */
final class RuntimeServiceIndex {
    private final Map<String,ServiceInfo> components=new LinkedHashMap<>();
    RuntimeServiceIndex(ServiceInfo[] infos,List<ApkManifest.Component> declarations){
        for(var declared:declarations)if(declared.type.equals("service")){
            ServiceInfo selected=null;
            if(infos!=null)for(ServiceInfo candidate:infos)if(candidate.name.equals(declared.name)){
                if(selected!=null)throw new SecurityException("SERVICE_METADATA_AMBIGUOUS:"+declared.name);selected=candidate;
            }
            if(selected==null)throw new IllegalStateException("SERVICE_METADATA_MISSING:"+declared.name);
            ServiceInfo copy=new ServiceInfo(selected);copy.enabled=declared.enabled;copy.exported=declared.exported;
            if(components.putIfAbsent(copy.name,copy)!=null)throw new SecurityException("SERVICE_DECLARATION_DUPLICATE:"+copy.name);
        }
    }
    ServiceInfo get(String name){return components.get(name);}
}
