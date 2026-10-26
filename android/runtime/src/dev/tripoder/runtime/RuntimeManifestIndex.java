package dev.tripoder.runtime;

import android.content.pm.*;
import java.util.*;

/** Device-effective flags decoded only from the sealed APK, never a host installed package. */
final class RuntimeManifestIndex {
    static void includeSplits(ArtifactSet set,PackageInfo pkg){
        if(set.parts.isEmpty())throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        if(set.parts.size()<=1||pkg==null||pkg.applicationInfo==null)return;
        int n=set.parts.size()-1;String[] names=new String[n],dirs=new String[n];
        for(int i=0;i<n;i++){ArtifactSet.Part split=set.parts.get(i+1);names[i]=split.manifest.splitName;dirs[i]=split.file.toString();}
        pkg.splitNames=names.clone();pkg.applicationInfo.splitNames=names.clone();
        pkg.applicationInfo.splitSourceDirs=dirs.clone();pkg.applicationInfo.splitPublicSourceDirs=dirs.clone();
        List<ActivityInfo> activities=pkg.activities==null?new ArrayList<>():new ArrayList<>(Arrays.asList(pkg.activities));
        List<ActivityInfo> receivers=pkg.receivers==null?new ArrayList<>():new ArrayList<>(Arrays.asList(pkg.receivers));
        List<ServiceInfo> services=pkg.services==null?new ArrayList<>():new ArrayList<>(Arrays.asList(pkg.services));
        List<ProviderInfo> providers=pkg.providers==null?new ArrayList<>():new ArrayList<>(Arrays.asList(pkg.providers));
        boolean addedActivity=false,addedReceiver=false,addedService=false,addedProvider=false;
        for(int i=1;i<set.parts.size();i++){
            ArtifactSet.Part part=set.parts.get(i);
            for(ApkManifest.Component declared:part.manifest.components){
                boolean exported=Boolean.TRUE.equals(declared.exported),enabled=!Boolean.FALSE.equals(declared.enabled);
                switch(declared.type){
                    case "activity","activity-alias"->{
                        if(activities.stream().anyMatch(a->declared.name.equals(a.name)))break;
                        ActivityInfo info=new ActivityInfo();info.packageName=pkg.packageName;info.name=declared.name;info.processName=declared.process;
                        info.applicationInfo=pkg.applicationInfo;info.splitName=part.manifest.splitName;info.exported=exported;info.enabled=enabled;
                        info.permission=declared.permission.isEmpty()?null:declared.permission;info.targetActivity=declared.targetActivity.isEmpty()?null:declared.targetActivity;
                        activities.add(info);addedActivity=true;
                    }
                    case "receiver"->{
                        if(receivers.stream().anyMatch(a->declared.name.equals(a.name)))break;
                        ActivityInfo info=new ActivityInfo();info.packageName=pkg.packageName;info.name=declared.name;info.processName=declared.process;
                        info.applicationInfo=pkg.applicationInfo;info.splitName=part.manifest.splitName;info.exported=exported;info.enabled=enabled;
                        info.permission=declared.permission.isEmpty()?null:declared.permission;
                        receivers.add(info);addedReceiver=true;
                    }
                    case "service"->{
                        if(services.stream().anyMatch(s->declared.name.equals(s.name)))break;
                        ServiceInfo info=new ServiceInfo();info.packageName=pkg.packageName;info.name=declared.name;info.processName=declared.process;
                        info.applicationInfo=pkg.applicationInfo;info.splitName=part.manifest.splitName;info.exported=exported;info.enabled=enabled;
                        info.permission=declared.permission.isEmpty()?null:declared.permission;
                        services.add(info);addedService=true;
                    }
                    case "provider"->{
                        if(providers.stream().anyMatch(p->declared.name.equals(p.name)&&Objects.equals(declared.authorities,p.authority)))break;
                        ProviderInfo info=new ProviderInfo();info.packageName=pkg.packageName;info.name=declared.name;info.processName=declared.process;
                        info.applicationInfo=pkg.applicationInfo;info.splitName=part.manifest.splitName;info.exported=exported;info.enabled=enabled;
                        info.authority=declared.authorities;
                        providers.add(info);addedProvider=true;
                    }
                }
            }
        }
        if(addedActivity)pkg.activities=activities.toArray(ActivityInfo[]::new);
        if(addedReceiver)pkg.receivers=receivers.toArray(ActivityInfo[]::new);
        if(addedService)pkg.services=services.toArray(ServiceInfo[]::new);
        if(addedProvider)pkg.providers=providers.toArray(ProviderInfo[]::new);
    }
    static List<ApkManifest.Component> resolve(ArtifactSet set,PackageInfo pkg){
        if(set.parts.isEmpty())throw new IllegalStateException("RUNTIME_SPLIT_EXECUTION_NOT_YET_SUPPORTED");
        if(!set.packageName.equals(pkg.packageName)||pkg.applicationInfo==null)throw new SecurityException("MANIFEST_PACKAGE_MISMATCH");
        includeSplits(set,pkg);
        Map<String,List<ActivityInfo>> activityByName=new HashMap<>();
        if(pkg.activities!=null)for(ActivityInfo a:pkg.activities)activityByName.computeIfAbsent(a.name,k->new ArrayList<>(1)).add(a);
        Map<String,List<ActivityInfo>> receiverByName=new HashMap<>();
        if(pkg.receivers!=null)for(ActivityInfo r:pkg.receivers)receiverByName.computeIfAbsent(r.name,k->new ArrayList<>(1)).add(r);
        Map<String,List<ServiceInfo>> serviceByName=new HashMap<>();
        if(pkg.services!=null)for(ServiceInfo s:pkg.services)serviceByName.computeIfAbsent(s.name,k->new ArrayList<>(1)).add(s);
        Map<String,List<ProviderInfo>> providerByName=new HashMap<>();
        if(pkg.providers!=null)for(ProviderInfo p:pkg.providers)providerByName.computeIfAbsent(p.name,k->new ArrayList<>(1)).add(p);
        List<ApkManifest.Component> out=new ArrayList<>(set.components.size());
        for(var declared:set.components){
            List<? extends ComponentInfo> candidates=switch(declared.type){case "activity","activity-alias"->activityByName.get(declared.name);case "receiver"->receiverByName.get(declared.name);case "service"->serviceByName.get(declared.name);case "provider"->providerByName.get(declared.name);default->throw new IllegalStateException("RUNTIME_COMPONENT_SET_NOT_SUPPORTED");};
            ComponentInfo found=null;
            if(candidates!=null)for(ComponentInfo info:candidates){
                if(info instanceof ProviderInfo&&!declared.authorities.equals(((ProviderInfo)info).authority))continue;
                if(info instanceof ActivityInfo&&!Objects.equals(declared.targetActivity.isEmpty()?null:declared.targetActivity,((ActivityInfo)info).targetActivity))continue;
                if(!pkg.packageName.equals(info.packageName))throw new SecurityException("MANIFEST_COMPONENT_PACKAGE_MISMATCH");
                if(found!=null&&declared.type.equals("provider")&&(found.enabled!=info.enabled||found.exported!=info.exported||!Objects.equals(found.processName,info.processName)))throw new SecurityException("MANIFEST_COMPONENT_AMBIGUOUS:"+declared.name);
                found=info;
            }
            if(found==null)throw new IllegalStateException("MANIFEST_COMPONENT_METADATA_MISSING:"+declared.type+":"+declared.name);
            out.add(new ApkManifest.Component(declared,found.exported,found.enabled&&pkg.applicationInfo.enabled));
        }
        return Collections.unmodifiableList(out);
    }
    private RuntimeManifestIndex(){}
}
