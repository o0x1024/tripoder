package dev.tripoder.runtime;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.content.res.Resources;

/** Logical ContextImpl beneath this wrapper is guest-owned; no host facade is delegated. */
final class RuntimeIsolatedContext extends ContextWrapper {
    private final ApplicationInfo info;private final ClassLoader loader;private final Resources resources;private final Application application;
    RuntimeIsolatedContext(Context logical,ApplicationInfo info,ClassLoader loader,Resources resources,Application application){super(logical);dev.tripoder.selfhook.ApplicationAdapter.requireLogicalContext(logical);this.info=info;this.loader=loader;this.resources=resources;this.application=application;}
    private static UnsupportedOperationException denied(){return new UnsupportedOperationException("ISOLATED_CONTEXT_CAPABILITY_NOT_IMPLEMENTED");}
    @Override public String getPackageName(){return info.packageName;}
    @Override public ApplicationInfo getApplicationInfo(){return info;}
    @Override public ClassLoader getClassLoader(){return loader;}
    @Override public Resources getResources(){return resources;}
    @Override public android.content.res.AssetManager getAssets(){return resources.getAssets();}
    @Override public Context getApplicationContext(){return application;}
    @Override public String getPackageCodePath(){return info.sourceDir;}
    @Override public String getPackageResourcePath(){return info.publicSourceDir;}
    @Override public PackageManager getPackageManager(){throw denied();}
    @Override public Object getSystemService(String name){throw denied();}
    @Override public ContentResolver getContentResolver(){throw denied();}
    @Override public int checkSelfPermission(String permission){return PackageManager.PERMISSION_DENIED;}
    @Override public int checkCallingOrSelfPermission(String permission){return PackageManager.PERMISSION_DENIED;}
    @Override public int checkPermission(String permission,int pid,int uid){return PackageManager.PERMISSION_DENIED;}
    @Override public Context createDeviceProtectedStorageContext(){throw denied();}
    @Override public Context createPackageContext(String name,int flags){throw denied();}
    @Override public Context createContextForSplit(String name){throw denied();}
    @Override public void startActivity(Intent intent){throw denied();}
    @Override public ComponentName startService(Intent intent){throw denied();}
    @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){throw denied();}
    @Override public void sendBroadcast(Intent intent){throw denied();}
    @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){throw denied();}
    @Override public java.io.FileInputStream openFileInput(String name){throw denied();}
    @Override public java.io.FileOutputStream openFileOutput(String name,int mode){throw denied();}
}
