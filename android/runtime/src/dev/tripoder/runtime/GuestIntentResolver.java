package dev.tripoder.runtime;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.PatternMatcher;
import java.util.*;

/**
 * Intent resolution over the one package world the guest can see: its own manifest. Android resolves an intent by matching it against every
 * declared intent-filter; the same rule applied to the declarations of this package gives the same answer for it. Other packages are absent
 * from the guest's world, so they contribute nothing.
 */
final class GuestIntentResolver {
    private GuestIntentResolver(){}
    private static final Map<RuntimeEngine,Map<String,List<IntentFilter>>> FILTERS=Collections.synchronizedMap(new WeakHashMap<>());

    /** The filters one declared component carries. A malformed or resource-referenced attribute simply contributes nothing, as the platform parser would skip it. */
    static List<IntentFilter> filters(RuntimeEngine engine,ApkManifest.Component component){
        Map<String,List<IntentFilter>> byComponent=FILTERS.computeIfAbsent(engine,key->Collections.synchronizedMap(new HashMap<>()));
        return byComponent.computeIfAbsent(component.type+"/"+component.name,key->build(engine.filterSpecs.getOrDefault(key,List.of())));
    }
    private static List<IntentFilter> build(List<ArtifactSet.FilterSpec> specs){
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

    /** Matches in Android's order: explicit component first, then filters by priority (higher first) and declaration order. */
    static List<ResolveInfo> query(RuntimeEngine engine,String kind,Intent intent,long flags){
        List<ResolveInfo> out=new ArrayList<>();
        if(intent==null)return out;
        String pkg=intent.getPackage();ComponentName explicit=intent.getComponent();
        if(explicit!=null&&!engine.packageName.equals(explicit.getPackageName()))return out;
        if(pkg!=null&&!engine.packageName.equals(pkg))return out;
        boolean defaultOnly=(flags&PackageManager.MATCH_DEFAULT_ONLY)!=0,disabled=(flags&PackageManager.MATCH_DISABLED_COMPONENTS)!=0;
        class Candidate{final ApkManifest.Component component;final IntentFilter filter;final int match,order;Candidate(ApkManifest.Component c,IntentFilter f,int m,int o){component=c;filter=f;match=m;order=o;}}
        List<Candidate> found=new ArrayList<>();int order=0;
        for(ApkManifest.Component component:engine.manifestDeclarations){
            // An <activity-alias> is an activity under its own name and filters.
            if(!(component.type.equals(kind)||kind.equals("activity")&&component.type.equals("activity-alias")))continue;order++;
            if(explicit!=null){
                if(!explicit.getClassName().equals(component.name))continue;
                if(!disabled&&Boolean.FALSE.equals(component.enabled))continue;
                found.add(new Candidate(component,null,IntentFilter.MATCH_CATEGORY_EMPTY,order));continue;
            }
            if(!disabled&&Boolean.FALSE.equals(component.enabled))continue;
            for(IntentFilter filter:filters(engine,component)){
                if(defaultOnly&&!filter.hasCategory(Intent.CATEGORY_DEFAULT))continue;
                int match=filter.match(intent.getAction(),intent.getType(),intent.getScheme(),intent.getData(),intent.getCategories(),"TripoderResolve");
                if(match>=0){found.add(new Candidate(component,filter,match,order));break;}
            }
        }
        found.sort((a,b)->{int p=Integer.compare(b.filter==null?0:b.filter.getPriority(),a.filter==null?0:a.filter.getPriority());return p!=0?p:Integer.compare(a.order,b.order);});
        for(Candidate candidate:found){
            ResolveInfo info=new ResolveInfo();
            info.priority=candidate.filter==null?0:candidate.filter.getPriority();info.match=candidate.match;info.filter=candidate.filter;
            info.isDefault=candidate.filter!=null&&candidate.filter.hasCategory(Intent.CATEGORY_DEFAULT);
            switch(kind){
                case "activity":info.activityInfo=copyActivity(engine.packageArchive.activities,candidate.component.name,engine);break;
                case "receiver":info.activityInfo=copyActivity(engine.packageArchive.receivers,candidate.component.name,engine);break;
                case "service":info.serviceInfo=copyService(engine,candidate.component.name);break;
                default:break;
            }
            if(info.activityInfo!=null||info.serviceInfo!=null)out.add(info);
        }
        return out;
    }
    /** The component a start of this implicit intent resolves to inside the guest, or null if nothing in the guest declares it. */
    static ComponentName resolveActivity(RuntimeEngine engine,Intent intent){
        if(intent.getComponent()!=null)return intent.getComponent();
        List<ResolveInfo> matches=query(engine,"activity",intent,PackageManager.MATCH_DEFAULT_ONLY);
        if(matches.isEmpty())return null;
        ActivityInfo chosen=matches.get(0).activityInfo;
        return new ComponentName(chosen.packageName,chosen.name);
    }
    private static ActivityInfo copyActivity(ActivityInfo[] declared,String name,RuntimeEngine engine){
        if(declared==null)return null;
        for(ActivityInfo info:declared)if(info.name.equals(name)){ActivityInfo copy=new ActivityInfo(info);copy.applicationInfo=GuestPackageManager.copyApplicationInfo(engine.info,false);return copy;}
        return null;
    }
    private static ServiceInfo copyService(RuntimeEngine engine,String name){
        ServiceInfo raw=engine.serviceIndex.get(name);if(raw==null)return null;
        ServiceInfo copy=new ServiceInfo(raw);copy.applicationInfo=GuestPackageManager.copyApplicationInfo(engine.info,false);return copy;
    }
}
