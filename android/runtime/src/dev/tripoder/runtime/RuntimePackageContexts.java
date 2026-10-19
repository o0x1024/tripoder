package dev.tripoder.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import dalvik.system.PathClassLoader;

/** A new context for this immutable installation; resources-only requests expose no app DEX. */
final class RuntimePackageContexts {
    private final RuntimeEngine engine;
    private ClassLoader resourcesLoader;
    RuntimePackageContexts(RuntimeEngine engine){this.engine=engine;}
    Context create(String packageName,int flags){
        if(!engine.packageName.equals(packageName))throw new UnsupportedOperationException("CROSS_PACKAGE_CONTEXT_UNSUPPORTED");
        if((flags&~(Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY))!=0)throw new UnsupportedOperationException("PACKAGE_CONTEXT_FLAGS_UNSUPPORTED:"+flags);
        ClassLoader selected=null;
        if((flags&Context.CONTEXT_INCLUDE_CODE)==0)synchronized(this){
            if(resourcesLoader==null)resourcesLoader=new PathClassLoader("",engine.info.nativeLibraryDir,ClassLoader.getSystemClassLoader());
            selected=resourcesLoader;
        }
        return new GuestContext(new ThemedBase(engine),engine,engine.resources,engine.host,selected);
    }
    /** Fixed API37 ContextImpl default theme rules; no host/Activity theme is copied. */
    private static final class ThemedBase extends ContextWrapper {
        private final RuntimeEngine engine;
        private Resources.Theme theme;
        private int themeResource;
        ThemedBase(RuntimeEngine engine){super(engine.host);this.engine=engine;}
        @Override public Resources getResources(){return engine.resources;}
        @Override public ApplicationInfo getApplicationInfo(){return engine.applicationInfo;}
        @Override public synchronized Resources.Theme getTheme(){
            if(theme==null){if(themeResource==0){int sdk=engine.info.targetSdkVersion;themeResource=sdk<11?android.R.style.Theme:sdk<14?android.R.style.Theme_Holo:sdk<24?android.R.style.Theme_DeviceDefault:android.R.style.Theme_DeviceDefault_Light_DarkActionBar;}theme=engine.resources.newTheme();theme.applyStyle(themeResource,true);}return theme;
        }
        @Override public synchronized void setTheme(int resource){if(themeResource!=resource){themeResource=resource;if(theme==null)getTheme();else theme.applyStyle(resource,true);}}
    }
}
