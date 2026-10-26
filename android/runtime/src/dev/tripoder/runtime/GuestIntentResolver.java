package dev.tripoder.runtime;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.PatternMatcher;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Intent resolution over the one package world the guest can see: its own manifest. Android resolves an intent by matching it against every
 * declared intent-filter; the same rule applied to the declarations of this package gives the same answer for it. Other packages are absent
 * from the guest's world, so they contribute nothing.
 */
final class GuestIntentResolver {
    private GuestIntentResolver(){}
    private static final Map<RuntimeEngine,Map<String,List<IntentFilter>>> FILTERS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<RuntimeEngine,Index> INDEXES=Collections.synchronizedMap(new WeakHashMap<>());

    private static final class IndexedComponent {
        final ApkManifest.Component component;final int order;final List<IntentFilter> filters;
        IndexedComponent(ApkManifest.Component component,int order,List<IntentFilter> filters){this.component=component;this.order=order;this.filters=filters;}
    }
    private static final class Index {
        final Map<String,List<IndexedComponent>> byKind=new HashMap<>();
        final Map<String,Map<String,IndexedComponent>> explicitByKind=new HashMap<>();
        final Map<String,Map<String,List<IndexedComponent>>> byKindAndAction=new HashMap<>();
        final Map<String,ActivityInfo> activities=new HashMap<>(),receivers=new HashMap<>();
        Index(RuntimeEngine engine){this(engine.packageArchive,engine.manifestDeclarations,component->filters(engine,component));}
        Index(PackageInfo archive,List<ApkManifest.Component> declarations,Function<ApkManifest.Component,List<IntentFilter>> declaredFilters){
            for(String k:new String[]{"activity","receiver","service"}){
                byKind.put(k,new ArrayList<>());explicitByKind.put(k,new HashMap<>());byKindAndAction.put(k,new HashMap<>());
            }
            if(archive!=null){
                if(archive.activities!=null)for(ActivityInfo a:archive.activities)if(a!=null&&a.name!=null)activities.putIfAbsent(a.name,a);
                if(archive.receivers!=null)for(ActivityInfo a:archive.receivers)if(a!=null&&a.name!=null)receivers.putIfAbsent(a.name,a);
            }
            int order=0;
            for(ApkManifest.Component component:declarations){
                String kind=component.type.equals("activity-alias")?"activity":component.type;
                List<IndexedComponent> list=byKind.get(kind);if(list==null)continue;
                order++;
                List<IntentFilter> compFilters=declaredFilters.apply(component);
                if(kind.equals("activity")){
                    // Virtual APKs have no platform privileged installation.
                    // Apply the ordinary-app Activity priority policy in both worlds.
                    List<IntentFilter> admitted=new ArrayList<>();for(IntentFilter raw:compFilters){IntentFilter filter=new IntentFilter(raw);if(filter.getPriority()>0)filter.setPriority(0);admitted.add(filter);}compFilters=admitted;
                }
                IndexedComponent indexed=new IndexedComponent(component,order,compFilters);
                list.add(indexed);
                explicitByKind.get(kind).putIfAbsent(component.name,indexed);
                Map<String,List<IndexedComponent>> actionMap=byKindAndAction.get(kind);
                Set<String> seenActions=new HashSet<>();
                for(IntentFilter f:compFilters){
                    for(int i=0;i<f.countActions();i++){
                        String act=f.getAction(i);
                        if(seenActions.add(act))actionMap.computeIfAbsent(act,k->new ArrayList<>()).add(indexed);
                    }
                }
            }
        }
    }
    private static Index index(RuntimeEngine engine){return INDEXES.computeIfAbsent(engine,Index::new);}

    /** The filters one declared component carries. A malformed or resource-referenced attribute simply contributes nothing, as the platform parser would skip it. */
    static List<IntentFilter> filters(RuntimeEngine engine,ApkManifest.Component component){
        Map<String,List<IntentFilter>> byComponent=FILTERS.computeIfAbsent(engine,key->Collections.synchronizedMap(new HashMap<>()));
        return byComponent.computeIfAbsent(component.type+"/"+component.name,key->build(engine.filterSpecs.getOrDefault(key,List.of())));
    }
    static List<IntentFilter> build(List<ArtifactSet.FilterSpec> specs){
        List<IntentFilter> result=new ArrayList<>();
        for(ArtifactSet.FilterSpec spec:specs){
            // A filter with no action matches nothing in Android; keep it out so it cannot match actionless intents by accident.
            if(spec.actions.isEmpty())continue;
            IntentFilter filter=new IntentFilter();filter.setPriority(spec.priority);
            for(String action:spec.actions)filter.addAction(action);
            for(String category:spec.categories)filter.addCategory(category);
            for(String[] row:spec.data)data(filter,row);
            result.add(filter);
        }
        return result;
    }
    private static void data(IntentFilter filter,String[] row){
        String scheme=row[0],host=row[1],port=row[2],path=row[3],prefix=row[4],pattern=row[5],advanced=row[6],suffix=row[7],mime=row[8];
        if(scheme!=null&&!scheme.startsWith("@"))filter.addDataScheme(scheme);
        if(host!=null&&!host.startsWith("@"))filter.addDataAuthority(host,port==null||port.startsWith("@")?null:port);
        if(path!=null)filter.addDataPath(path,PatternMatcher.PATTERN_LITERAL);
        if(prefix!=null)filter.addDataPath(prefix,PatternMatcher.PATTERN_PREFIX);
        if(pattern!=null)filter.addDataPath(pattern,PatternMatcher.PATTERN_SIMPLE_GLOB);
        if(advanced!=null)filter.addDataPath(advanced,PatternMatcher.PATTERN_ADVANCED_GLOB);
        if(suffix!=null)filter.addDataPath(suffix,PatternMatcher.PATTERN_SUFFIX);
        if(mime!=null&&!mime.startsWith("@"))try{filter.addDataType(mime);}catch(IntentFilter.MalformedMimeTypeException ignored){}
    }

    private static final class Candidate {
        final ApkManifest.Component component;final IntentFilter filter;final int match,order;
        Candidate(ApkManifest.Component c,IntentFilter f,int m,int o){component=c;filter=f;match=m;order=o;}
    }
    private static boolean visible(String packageName,Intent intent){
        if(intent==null)return false;ComponentName explicit=intent.getComponent();String pkg=intent.getPackage();
        return (explicit==null||packageName.equals(explicit.getPackageName()))&&(pkg==null||packageName.equals(pkg));
    }
    /** The same matcher serves worker queries and authority application selection. */
    private static List<Candidate> candidates(Index idx,String packageName,String kind,Intent intent,long flags,Predicate<String> enabled){
        List<Candidate> found=new ArrayList<>();
        if(!visible(packageName,intent))return found;ComponentName explicit=intent.getComponent();
        boolean defaultOnly=(flags&PackageManager.MATCH_DEFAULT_ONLY)!=0,disabled=(flags&PackageManager.MATCH_DISABLED_COMPONENTS)!=0;
        if(explicit!=null){
            Map<String,IndexedComponent> explicitMap=idx.explicitByKind.get(kind);
            IndexedComponent indexed=explicitMap==null?null:explicitMap.get(explicit.getClassName());
            if(indexed!=null&&(disabled||enabled.test(indexed.component.name))){
                found.add(new Candidate(indexed.component,null,IntentFilter.MATCH_CATEGORY_EMPTY,indexed.order));
            }
        }else{
            String action=intent.getAction();
            List<IndexedComponent> pool;
            if(action!=null){
                Map<String,List<IndexedComponent>> actionMap=idx.byKindAndAction.get(kind);
                pool=actionMap==null?List.of():actionMap.getOrDefault(action,List.of());
            }else{
                pool=idx.byKind.getOrDefault(kind,List.of());
            }
            String type=intent.getType(),scheme=intent.getScheme();
            android.net.Uri dataUri=intent.getData();
            Set<String> categories=intent.getCategories();
            for(IndexedComponent indexed:pool){
                ApkManifest.Component component=indexed.component;
                if(!disabled&&!enabled.test(component.name))continue;
                for(IntentFilter filter:indexed.filters){
                    if(defaultOnly&&!filter.hasCategory(Intent.CATEGORY_DEFAULT))continue;
                    int match=filter.match(action,type,scheme,dataUri,categories,"TripoderResolve");
                    if(match>=0){found.add(new Candidate(component,filter,match,indexed.order));break;}
                }
            }
        }
        if(found.size()>1)found.sort((a,b)->{int p=Integer.compare(b.filter==null?0:b.filter.getPriority(),a.filter==null?0:a.filter.getPriority());return p!=0?p:Integer.compare(a.order,b.order);});
        return found;
    }
    /** Matches explicit components, then filters by priority and declaration order. */
    static List<ResolveInfo> query(RuntimeEngine engine,String kind,Intent intent,long flags){
        if(!visible(engine.packageName,intent))return new ArrayList<>();
        android.os.Bundle states=RuntimeComponentState.snapshot(engine);Index idx=index(engine);
        List<Candidate> found=candidates(idx,engine.packageName,kind,intent,flags,name->enabled(engine,idx,kind,name,states));
        List<ResolveInfo> out=new ArrayList<>();
        ApplicationInfo sharedAppInfo=found.isEmpty()?null:GuestPackageManager.copyApplicationInfo(engine.info,false);
        for(Candidate candidate:found){
            ResolveInfo info=new ResolveInfo();
            info.priority=candidate.filter==null?0:candidate.filter.getPriority();info.match=candidate.match;info.filter=candidate.filter!=null&&(flags&PackageManager.GET_RESOLVED_FILTER)!=0?new IntentFilter(candidate.filter):null;
            info.isDefault=candidate.filter!=null&&candidate.filter.hasCategory(Intent.CATEGORY_DEFAULT);
            switch(kind){
                case "activity":info.activityInfo=copyActivity(idx.activities.get(candidate.component.name),sharedAppInfo);break;
                case "receiver":info.activityInfo=copyActivity(idx.receivers.get(candidate.component.name),sharedAppInfo);break;
                case "service":info.serviceInfo=copyService(engine,candidate.component.name,sharedAppInfo);break;
                default:break;
            }
            if(info.activityInfo!=null)info.activityInfo.enabled=RuntimeComponentState.enabled(states,info.activityInfo.name,info.activityInfo.enabled);
            if(info.serviceInfo!=null)info.serviceInfo.enabled=RuntimeComponentState.enabled(states,info.serviceInfo.name,info.serviceInfo.enabled);
            if(info.activityInfo!=null||info.serviceInfo!=null)out.add(info);
        }
        return out;
    }
    /** SDK application entry recipe: INFO first, otherwise LAUNCHER; TV uses LEANBACK only. */
    private static Intent launchIntent(String pkg,boolean leanback,Function<Intent,ComponentName> resolve){
        String[] categories=leanback?new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER}:new String[]{Intent.CATEGORY_INFO,Intent.CATEGORY_LAUNCHER};
        for(String category:categories){
            Intent selector=new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg);ComponentName chosen=resolve.apply(selector);
            if(chosen!=null)return new Intent(selector).setComponent(chosen).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }
    private static ComponentName first(Index idx,String pkg,Intent selector,Predicate<String> enabled){
        List<Candidate> found=candidates(idx,pkg,"activity",selector,0,enabled);return found.isEmpty()?null:new ComponentName(pkg,found.get(0).component.name);
    }
    static Intent launchIntent(RuntimeEngine engine,boolean leanback){
        android.os.Bundle states=RuntimeComponentState.snapshot(engine);Index idx=index(engine);
        return launchIntent(engine.packageName,leanback,selector->first(idx,engine.packageName,selector,name->enabled(engine,idx,"activity",name,states)));
    }
    private static Index installationIndex(ApkStore.BundleInstall install){
        Index idx=new Index(install.info,install.artifacts.components,component->build(install.artifacts.filters(component)));
        idx.activities.clear();idx.activities.putAll(install.activityIndex());return idx;
    }
    private static boolean enabled(Index idx,String name){ActivityInfo info=idx.activities.get(name);return info!=null&&info.enabled&&info.applicationInfo!=null&&info.applicationInfo.enabled;}
    static Intent launchIntent(ApkStore.BundleInstall install,boolean leanback){
        synchronized(install){Index idx=installationIndex(install);return launchIntent(install.info.packageName,leanback,selector->first(idx,install.info.packageName,selector,name->enabled(idx,name)));}
    }
    /** Request-local classification, using the same effective state and actual filter matching. */
    static Map<String,String> entryCategories(ApkStore.BundleInstall install){
        synchronized(install){
            Index idx=installationIndex(install);Map<String,String> result=new LinkedHashMap<>();
            for(String category:new String[]{Intent.CATEGORY_INFO,Intent.CATEGORY_LAUNCHER}){
                Intent selector=new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(install.info.packageName);
                for(Candidate match:candidates(idx,install.info.packageName,"activity",selector,0,name->enabled(idx,name)))result.putIfAbsent(match.component.name,category);
            }
            return Collections.unmodifiableMap(result);
        }
    }
    private static boolean enabled(RuntimeEngine engine,Index index,String kind,String name,android.os.Bundle states){
        android.content.pm.ComponentInfo raw=kind.equals("activity")?index.activities.get(name):kind.equals("receiver")?index.receivers.get(name):engine.serviceIndex.get(name);
        return raw!=null&&RuntimeComponentState.enabled(states,raw)&&engine.info.enabled;
    }
    /** The component a start of this implicit intent resolves to inside the guest, or null if nothing in the guest declares it. */
    static ComponentName resolveActivity(RuntimeEngine engine,Intent intent){
        if(intent.getComponent()!=null)return intent.getComponent();
        List<ResolveInfo> matches=query(engine,"activity",intent,PackageManager.MATCH_DEFAULT_ONLY);
        if(matches.isEmpty())return null;
        ActivityInfo chosen=matches.get(0).activityInfo;
        return new ComponentName(chosen.packageName,chosen.name);
    }
    private static ActivityInfo copyActivity(ActivityInfo declared,ApplicationInfo appInfo){
        if(declared==null)return null;
        ActivityInfo copy=new ActivityInfo(declared);copy.applicationInfo=appInfo;return copy;
    }
    private static ServiceInfo copyService(RuntimeEngine engine,String name,ApplicationInfo appInfo){
        ServiceInfo raw=engine.serviceIndex.get(name);if(raw==null)return null;
        ServiceInfo copy=new ServiceInfo(raw);copy.applicationInfo=appInfo;return copy;
    }
}
