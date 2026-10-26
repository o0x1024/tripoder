package dev.tripoder.runtime;

import android.content.pm.*;
import java.util.*;

/** Component publication and authority resolution are different indexes over the sealed APK. */
final class RuntimeProviderIndex {
    private final Map<String,ProviderInfo> components=new LinkedHashMap<>(),authorities=new LinkedHashMap<>();
    RuntimeProviderIndex(ProviderInfo[] infos,List<ApkManifest.Component> declarations){
        Map<String,List<ProviderInfo>> byName=new HashMap<>();
        if(infos!=null)for(ProviderInfo candidate:infos)byName.computeIfAbsent(candidate.name,k->new ArrayList<>(1)).add(candidate);
        for(var declared:declarations)if(declared.type.equals("provider")){
            ProviderInfo selected=null;
            List<ProviderInfo> candidates=byName.get(declared.name);
            if(candidates!=null)for(ProviderInfo candidate:candidates)if(Objects.equals(candidate.authority,declared.authorities)){selected=candidate;break;}
            if(selected==null)throw new IllegalStateException("PROVIDER_METADATA_MISSING:"+declared.name);
            ProviderInfo info=new ProviderInfo(selected);info.enabled=declared.enabled;info.exported=declared.exported;
            ProviderInfo prior=components.putIfAbsent(info.name,info);
            // Cross-process/enablement collisions require their own lifecycle contract and evidence.
            if(prior!=null&&(!Objects.equals(prior.processName,info.processName)||prior.enabled!=info.enabled||prior.multiprocess!=info.multiprocess))throw new UnsupportedOperationException("PROVIDER_COMPONENT_IDENTITY_CONFLICT:"+info.name);
            // One package: the first declaration of an authority is the one Android resolves; later duplicates stay declared but unreachable by authority.
            for(String alias:RuntimeProviderBroker.aliases(info.authority))authorities.putIfAbsent(alias,info);
        }
    }
    List<ProviderInfo> publications(String process){List<ProviderInfo> out=new ArrayList<>();for(var info:components.values())if(info.enabled&&process.equals(info.processName))out.add(new ProviderInfo(info));return out;}
    List<ProviderInfo> publications(String process,android.os.Bundle states){List<ProviderInfo> out=new ArrayList<>();for(var info:components.values())if(RuntimeComponentState.enabled(states,info)&&process.equals(info.processName)){ProviderInfo copy=new ProviderInfo(info);copy.enabled=true;out.add(copy);}return out;}
    ProviderInfo component(String name,android.os.Bundle states){ProviderInfo copy=component(name);copy.enabled=RuntimeComponentState.enabled(states,copy.name,copy.enabled);return copy;}
    ProviderInfo component(String name){ProviderInfo info=components.get(name);if(info==null)throw new IllegalStateException("PROVIDER_METADATA_MISSING:"+name);return new ProviderInfo(info);}
    ProviderInfo authority(String name){ProviderInfo info=authorities.get(name);return info==null?null:new ProviderInfo(info);}
    static void requireAuthority(ProviderInfo publication,String authority){if(!RuntimeProviderBroker.aliases(publication.authority).contains(authority))throw new SecurityException("PROVIDER_AUTHORITY_NOT_PUBLISHED:"+authority);}
}
