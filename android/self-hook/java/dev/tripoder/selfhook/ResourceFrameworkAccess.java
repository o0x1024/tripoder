package dev.tripoder.selfhook;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import android.content.res.Resources;
import android.content.res.AssetManager;

/** Uses the current boot framework's exact default-theme selection for the guest target SDK. */
public final class ResourceFrameworkAccess {
    private static Method selection;
    private static Method implementation,setImplementation;private static Constructor<?> constructor;
    private static Method apkAssets,upToDate,isSystem,isShared,isOverlay,isLoader,assetsProvider;
    private static Method diagnosticAssetLabel;
    private static synchronized String diagnosticLabel(Object asset)throws Exception{
        if(diagnosticAssetLabel==null)diagnosticAssetLabel=FixedFrameworkMembers.method(70,"getAssetPath","()Ljava/lang/String;",false);
        return (String)FixedFrameworkMembers.call(diagnosticAssetLabel,asset);
    }
    private static synchronized void assetAccess(){
        if(apkAssets!=null)return;
        try{
            Method list=FixedFrameworkMembers.method(69,"getApkAssets","()[Landroid/content/res/ApkAssets;",false);
            upToDate=FixedFrameworkMembers.method(70,"isUpToDate","()Z",false);isSystem=FixedFrameworkMembers.method(70,"isSystem","()Z",false);
            isShared=FixedFrameworkMembers.method(70,"isSharedLib","()Z",false);isOverlay=FixedFrameworkMembers.method(70,"isOverlay","()Z",false);isLoader=FixedFrameworkMembers.method(70,"isForLoader","()Z",false);
            assetsProvider=FixedFrameworkMembers.method(70,"getAssetsProvider","()Landroid/content/res/loader/AssetsProvider;",false);apkAssets=list;
        }catch(Exception error){throw new IllegalStateException("RESOURCE_ASSET_INVENTORY_UNAVAILABLE",error);}
    }
    private static Object[] inventory(Resources resources){
        return inventory(resources.getAssets());
    }
    private static Object[] inventory(AssetManager manager){
        assetAccess();
        synchronized(manager){
            Object[] actual=(Object[])FixedFrameworkMembers.call(apkAssets,manager);
            if(actual==null||actual.length==0||actual.length>256)throw new SecurityException("RESOURCE_ASSET_INVENTORY_INVALID");
            Object[] copy=actual.clone();for(Object item:copy)if(item==null)throw new SecurityException("RESOURCE_ASSET_ENTRY_MISSING");return copy;
        }
    }
    private static int flags(Object asset){return (Boolean.TRUE.equals(FixedFrameworkMembers.call(isSystem,asset))?1:0)|(Boolean.TRUE.equals(FixedFrameworkMembers.call(isShared,asset))?2:0)|(Boolean.TRUE.equals(FixedFrameworkMembers.call(isOverlay,asset))?4:0)|(Boolean.TRUE.equals(FixedFrameworkMembers.call(isLoader,asset))?8:0)|(FixedFrameworkMembers.call(assetsProvider,asset)!=null?16:0);}
    /** Frozen BEFORE guest code; neither Resources nor AssetManager identity is the proof. */
    public static final class AssetSnapshot {
        private final Object[] entries;private final int[] flags;
        private final String[] diagnosticLabels;
        private AssetSnapshot(Object[] frozen){
            entries=frozen;flags=new int[entries.length];
            for(int i=0;i<entries.length;i++){flags[i]=ResourceFrameworkAccess.flags(entries[i]);if(!Boolean.TRUE.equals(FixedFrameworkMembers.call(upToDate,entries[i])))throw new SecurityException("RESOURCE_BASELINE_STALE");}
            String[] labels=new String[entries.length];try{for(int i=0;i<entries.length;i++)labels[i]=diagnosticLabel(entries[i]);}catch(Exception unavailable){labels=null;}diagnosticLabels=labels;
        }
        public void requireEquivalent(Resources resources){
            Object[] actual=inventory(resources);
            if(!matches(actual))throw new SecurityException("PACKAGE_CONTEXT_RESOURCE_ORIGIN_DENIED");
        }
        private boolean matches(Object[] actual){
            if(actual.length!=entries.length)return false;
            for(int i=0;i<actual.length;i++)if(actual[i]!=entries[i]||ResourceFrameworkAccess.flags(actual[i])!=flags[i]||!Boolean.TRUE.equals(FixedFrameworkMembers.call(upToDate,actual[i])))return false;
            return true;
        }
        /** Only the original layout and an explicitly admitted dependency's
         * complete precompiled layout are valid. Neither vector is re-frozen. */
        public void requireOwnedLayout(Resources resources,AssetSnapshot registeredLayout){
            Object[] actual=inventory(resources);
            if(!matches(actual)&&(registeredLayout==null||!registeredLayout.matches(actual)))throw new SecurityException("PACKAGE_CONTEXT_RESOURCE_ORIGIN_DENIED");
        }
        public java.util.Map<String,Object> relations(Resources resources){
            Object[] view=inventory(resources);java.util.List<Integer> indices=new java.util.ArrayList<>(),properties=new java.util.ArrayList<>();boolean healthy=true,equivalent=view.length==entries.length;
            for(int i=0;i<view.length;i++){
                int match=-1;for(int j=0;j<entries.length;j++)if(view[i]==entries[j]){match=j;break;}
                int actualFlags=ResourceFrameworkAccess.flags(view[i]);indices.add(match);properties.add(actualFlags);
                boolean current=Boolean.TRUE.equals(FixedFrameworkMembers.call(upToDate,view[i]));healthy&=current;
                if(match!=i||match<0||actualFlags!=flags[match])equivalent=false;
            }
            boolean baselineHealthy=true;for(Object asset:entries)baselineHealthy&=Boolean.TRUE.equals(FixedFrameworkMembers.call(upToDate,asset));
            return java.util.Map.of("baselineCount",entries.length,"viewCount",view.length,"baselineIndices",java.util.List.copyOf(indices),"flags",java.util.List.copyOf(properties),"viewUpToDate",healthy,"baselineUpToDate",baselineHealthy,"equivalent",equivalent&&healthy&&baselineHealthy);
        }
        /** Untrusted native labels are diagnostic comparisons only. No raw label
         * escapes, and none of these comparisons participates in authorization. */
        public java.util.Map<String,Object> nativeLabelRelations(Resources resources,String executionBase,String sealedBase,String hostApiBase){
            java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();result.put("labelsAreNotIdentityProof",true);result.put("baselineLabelsAvailable",diagnosticLabels!=null);
            try{
                Object[] actual=inventory(resources);java.util.List<Boolean> same=new java.util.ArrayList<>();java.util.List<String> roles=new java.util.ArrayList<>();
                for(int i=0;i<actual.length;i++){
                    String label=diagnosticLabel(actual[i]);same.add(diagnosticLabels!=null&&i<diagnosticLabels.length&&label!=null&&!label.isEmpty()&&label.equals(diagnosticLabels[i]));
                    roles.add(label==null||label.isEmpty()?"NO_NATIVE_LABEL":label.equals(executionBase)?"EXECUTION_BASE_LABEL":label.equals(sealedBase)?"SEALED_BASE_LABEL":label.equals(hostApiBase)?"HOST_API_BASE_LABEL":"OTHER_LABEL");
                }
                result.put("sameLabelAtBaselineIndex",java.util.List.copyOf(same));result.put("observedLabelRoles",java.util.List.copyOf(roles));result.put("status","OBSERVED");
            }catch(Exception unavailable){result.put("status","UNAVAILABLE:"+unavailable.getClass().getName());}
            return result;
        }
    }
    public static AssetSnapshot snapshot(Resources resources){return new AssetSnapshot(inventory(resources));}
    public static AssetManager independentSystemAssets(Resources system)throws Exception{
        AssetManager created=(AssetManager)FixedFrameworkMembers.create(FixedFrameworkMembers.constructor(69,"()V"));
        if(created==system.getAssets())throw new SecurityException("ISOLATED_SYSTEM_ASSETS_ALIASED");
        Object[] expected=inventory(system),actual=inventory(created);if(expected.length!=actual.length)throw new SecurityException("ISOLATED_SYSTEM_ASSETS_CHANGED");
        for(int i=0;i<actual.length;i++)if(actual[i]!=expected[i]||flags(actual[i])!=flags(expected[i]))throw new SecurityException("ISOLATED_SYSTEM_ASSETS_CHANGED");return created;
    }
    /** Initial FD construction only; no guest has executed and no baseline is replaced. */
    public static AssetSnapshot systemPlusLoaders(Resources resources,Resources system,int providers){
        if(resources.getAssets()==system.getAssets())throw new SecurityException("ISOLATED_SYSTEM_ASSETS_ALIASED");
        Object[] roots=inventory(system),actual=inventory(resources);
        if(providers<1||actual.length!=roots.length+providers)throw new SecurityException("ISOLATED_RESOURCE_LAYOUT_INVALID");
        for(int i=0;i<actual.length;i++){
            if(i<roots.length){if(actual[i]!=roots[i]||flags(actual[i])!=flags(roots[i]))throw new SecurityException("ISOLATED_SYSTEM_ASSET_IDENTITY");}
            else if(flags(actual[i])!=8)throw new SecurityException("ISOLATED_FD_ASSET_ROLE");
        }
        return new AssetSnapshot(actual);
    }
    static AssetSnapshot snapshot(AssetManager assets){return new AssetSnapshot(inventory(assets));}
    /** Resources owns its inflater loader separately from its AssetManager.
     * Share the exact platform implementation while constructing the package's
     * proper Resources identity, as ResourcesManager does for each loader. */
    public static synchronized Resources forLoader(Resources source,ClassLoader loader){
        java.util.Objects.requireNonNull(source);java.util.Objects.requireNonNull(loader);
        if(constructor==null)try{
            Constructor<?> selected=FixedFrameworkMembers.constructor(63,"(Ljava/lang/ClassLoader;)V");
            Method get=FixedFrameworkMembers.method(63,"getImpl","()Landroid/content/res/ResourcesImpl;",false);
            Method set=FixedFrameworkMembers.method(63,"setImpl","(Landroid/content/res/ResourcesImpl;)V",false);
            constructor=selected;implementation=get;setImplementation=set;
        }catch(Exception error){throw new IllegalStateException("RESOURCE_LOADER_CONSTRUCTION_UNAVAILABLE",error);}
        Resources result=(Resources)FixedFrameworkMembers.create(constructor,loader);
        FixedFrameworkMembers.call(setImplementation,result,FixedFrameworkMembers.call(implementation,source));
        return result;
    }
    public static synchronized int defaultTheme(int theme,int targetSdk){
        if(selection==null)try{selection=FixedFrameworkMembers.method(63,"selectDefaultTheme","(II)I",true);}catch(Exception error){throw new IllegalStateException("RESOURCE_THEME_SELECTION_UNAVAILABLE",error);}
        return (Integer)FixedFrameworkMembers.call(selection,null,theme,targetSdk);
    }
    public static synchronized boolean sameImplementation(Resources a,Resources b){
        if(a==null||b==null)return false;
        if(implementation==null)try{implementation=FixedFrameworkMembers.method(63,"getImpl","()Landroid/content/res/ResourcesImpl;",false);}catch(Exception error){throw new IllegalStateException("RESOURCE_IMPLEMENTATION_ACCESS_UNAVAILABLE",error);}
        Object actual=FixedFrameworkMembers.call(implementation,a);return actual!=null&&actual==FixedFrameworkMembers.call(implementation,b);
    }
    private ResourceFrameworkAccess(){}
}
