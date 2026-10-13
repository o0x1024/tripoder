package dev.tripoder.hooklab;

import android.content.*;
import android.content.res.*;
import java.io.File;

/** File/resource context for the guest Application; system identity remains the host. */
final class GuestContext extends ContextWrapper {
    private final ClassLoader loader;
    private final Resources resources;
    private final File data;
    GuestContext(Context base, ClassLoader loader, Resources resources, File data) { super(base); this.loader = loader; this.resources = resources; this.data = data; }
    @Override public ClassLoader getClassLoader() { return loader; }
    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return resources.getAssets(); }
    @Override public File getFilesDir() { return data; }
    @Override public File getCacheDir() { File f = new File(data, "cache"); f.mkdirs(); return f; }
    @Override public File getDatabasePath(String name) {
        if (name.contains("/") || name.contains("\\")) throw new IllegalArgumentException("Database name must be local");
        File dir = new File(data, "databases"); dir.mkdirs(); return new File(dir, name);
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("guest-" + GuestStore.PACKAGE + "-" + name, mode); }
    @Override public Context getApplicationContext() { return AgentBridge.componentApplication == null ? this : AgentBridge.componentApplication; }
}
