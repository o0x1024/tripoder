package dev.tripoder.hooklab;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.res.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;

/** Guest resources and project storage; OS services still use the host's real identity. */
final class GameContext extends ContextWrapper {
    private final GameRuntime runtime;
    private Resources.Theme theme;
    private android.content.pm.PackageManager packageManager;
    GameContext(Context base, GameRuntime runtime) { super(base); this.runtime=runtime; }
    @Override public ClassLoader getClassLoader() { return runtime.loader; }
    @Override public Resources getResources() { return runtime.resources; }
    @Override public AssetManager getAssets() { return getResources().getAssets(); }
    @Override public Resources.Theme getTheme() { if(theme==null){theme=getResources().newTheme();if(runtime.info.theme!=0)theme.applyStyle(runtime.info.theme,true);}return theme; }
    @Override public void setTheme(int id) { getTheme().applyStyle(id,true); }
    @Override public Object getSystemService(String name) { if(LAYOUT_INFLATER_SERVICE.equals(name))return android.view.LayoutInflater.from(getBaseContext()).cloneInContext(this);return super.getSystemService(name); }
    @Override public String getPackageName() { return GameRuntime.PACKAGE; }
    @Override public android.content.pm.PackageManager getPackageManager() { if(packageManager==null)packageManager=new GamePackageManager(getBaseContext().getPackageManager(),runtime);return packageManager; }
    @Override public String getOpPackageName() { return android.os.Build.VERSION.SDK_INT>=29?getBaseContext().getOpPackageName():runtime.host.getPackageName(); }
    @Override public ApplicationInfo getApplicationInfo() { return runtime.info; }
    @Override public String getPackageCodePath() { return runtime.info.sourceDir; }
    @Override public String getPackageResourcePath() { return runtime.info.publicSourceDir; }
    @Override public Context getApplicationContext() { return runtime.application==null?this:runtime.application; }
    private File directory(String name) { File f=new File(runtime.data,name); if(!f.isDirectory()&&!f.mkdirs())throw new IllegalStateException("无法创建游戏目录："+name);return f; }
    @Override public File getDataDir() { return runtime.data; }
    @Override public File getFilesDir() { return directory("files"); }
    @Override public File getCacheDir() { return directory("cache"); }
    @Override public File getCodeCacheDir() { return directory("code_cache"); }
    @Override public File getNoBackupFilesDir() { return directory("no_backup"); }
    @Override public File getDir(String name,int mode) { return directory("app_"+filename(name)); }
    private static String filename(String name) { if(name==null||name.isEmpty()||name.contains("/")||name.contains("\\")||name.equals(".."))throw new IllegalArgumentException("无效文件名");return name; }
    @Override public File getFileStreamPath(String name) { return new File(getFilesDir(),filename(name)); }
    @Override public FileInputStream openFileInput(String name) throws FileNotFoundException { return new FileInputStream(getFileStreamPath(name)); }
    @Override public FileOutputStream openFileOutput(String name,int mode) throws FileNotFoundException { return new FileOutputStream(getFileStreamPath(name),(mode&MODE_APPEND)!=0); }
    @Override public boolean deleteFile(String name) { return getFileStreamPath(name).delete(); }
    @Override public String[] fileList() { String[] list=getFilesDir().list();return list==null?new String[0]:list; }
    @Override public File getDatabasePath(String name) { return new File(directory("databases"),filename(name)); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler); }
    @Override public boolean deleteDatabase(String name) { return SQLiteDatabase.deleteDatabase(getDatabasePath(name)); }
    @Override public String[] databaseList() { String[] list=directory("databases").list();return list==null?new String[0]:list; }
    @Override public SharedPreferences getSharedPreferences(String name,int mode) { return getBaseContext().getSharedPreferences("game_"+runtime.projectId+"_"+filename(name),mode); }
    @Override public boolean deleteSharedPreferences(String name) { return getBaseContext().deleteSharedPreferences("game_"+runtime.projectId+"_"+filename(name)); }
    @Override public File getExternalFilesDir(String type) {
        File root=directory("external/files");if(type==null||type.isEmpty())return root;
        try{
            File selected=new File(root,type).getCanonicalFile();
            if(!selected.getPath().startsWith(root.getCanonicalPath()+File.separator))throw new IllegalArgumentException("外部目录越界");
            if(!selected.isDirectory()&&!selected.mkdirs())throw new IllegalStateException("无法创建外部目录");return selected;
        }catch(IOException error){throw new IllegalStateException("无法解析外部目录",error);}
    }
    @Override public File[] getExternalFilesDirs(String type) { return new File[]{getExternalFilesDir(type)}; }
    @Override public File getExternalCacheDir() { return directory("external/cache"); }
    @Override public File[] getExternalCacheDirs() { return new File[]{getExternalCacheDir()}; }
    @Override public File getObbDir() { return directory("external/obb"); }
    @Override public File[] getObbDirs() { return new File[]{getObbDir()}; }
    @Override public File[] getExternalMediaDirs() { return new File[]{directory("external/media")}; }
    @Override public Context createPackageContext(String name,int flags) throws android.content.pm.PackageManager.NameNotFoundException { return name.equals(getPackageName())?this:super.createPackageContext(name,flags); }
}
