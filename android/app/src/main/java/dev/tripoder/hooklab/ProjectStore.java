package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Durable project workspaces. IDs never come from display names or filesystem paths. */
final class ProjectStore {
    static final String DEFAULT = "default";
    private final Context context;
    private final File root;
    ProjectStore(Context context) {
        this.context = context;
        root = new File(context.getApplicationInfo().dataDir, "files/projects");
    }
    static String id(Activity activity) {
        String value=activity.getIntent().getStringExtra("projectId");
        return value==null?DEFAULT:validId(value);
    }
    static Intent intent(Context context,Class<?> type,String id) { return new Intent(context,type).putExtra("projectId",validId(id)); }
    static String validId(String id) {
        if(id==null||!id.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("无效的项目 ID");
        return id;
    }
    File directory(String id) { return new File(root,validId(id)); }
    private static void mkdir(File file) throws IOException { if(!file.isDirectory()&&!file.mkdirs())throw new IOException("无法创建项目目录"); }
    synchronized void initialize() throws Exception {
        mkdir(root);
        if(!new File(directory(DEFAULT),"project.json").isFile()){
            JSONObject app=fixture();
            createFiles(DEFAULT,"示例项目",app);
        }
        File migrated=new File(directory(DEFAULT),".legacy-migrated");
        if(!migrated.isFile()){
            String old=context.getSharedPreferences("ScriptActivity",0).getString("draft","");
            File legacy=new File(context.getApplicationInfo().dataDir,"files/hook-draft.ts");
            if(old.isEmpty()&&legacy.isFile())old=new String(Files.readAllBytes(legacy.toPath()),StandardCharsets.UTF_8);
            if(!old.isEmpty()&&!new File(directory(DEFAULT),"scripts/main.ts").exists())write(DEFAULT,"scripts/main.ts",old);
            if(!new File(directory(DEFAULT),"ai/prompt.txt").exists())write(DEFAULT,"ai/prompt.txt",context.getSharedPreferences("ScriptActivity",0).getString("prompt","将 Java 问候改为大写，Native 返回入参乘 2 加 700"));
            JSONObject env=new JSONObject();android.content.SharedPreferences prefs=context.getSharedPreferences("environment-editor",0);
            for(String name:new String[]{"MODEL","BRAND","MANUFACTURER"})env.put(name,prefs.getString(name,""));
            if(!new File(directory(DEFAULT),"config/environment.json").exists())write(DEFAULT,"config/environment.json",env.toString(2));
            JSONObject map=new JSONObject();prefs=context.getSharedPreferences("input-map",0);
            map.put("buttons",prefs.getString("buttons","{\"96\":\"action\"}"));map.put("recipe",prefs.getString("recipe","[]"));if(!new File(directory(DEFAULT),"config/input.json").exists())write(DEFAULT,"config/input.json",map.toString(2));
            File oldData=new GuestStore(context).dataDirectory();
            copyLegacy(oldData,new File(directory(DEFAULT),"runtime"));atomic(migrated,"done");
        }
    }
    private static void copyLegacy(File source,File destination) throws Exception {
        File[] files=source.listFiles();if(files==null)return;
        for(File f:files){if(Files.isSymbolicLink(f.toPath()))continue;File next=new File(destination,f.getName());if(f.isDirectory()){mkdir(next);copyLegacy(f,next);}else if(f.length()<=65536&&!next.exists())Files.copy(f.toPath(),next.toPath());}
    }
    static JSONObject fixture() throws Exception { return new JSONObject().put("id",GuestStore.PACKAGE).put("packageName",GuestStore.PACKAGE).put("name","Tripoder 示例应用").put("versionName","1.1").put("versionCode",GuestStore.VERSION).put("runnable",true); }
    JSONArray apps() throws Exception {
        File f=new File(root,"apps.json");if(!f.exists())return new JSONArray().put(fixture());
        return new JSONArray(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
    }
    synchronized JSONObject addInstalled(String packageName) throws Exception {
        PackageInfo info=context.getPackageManager().getPackageInfo(packageName,0);
        JSONObject app=new JSONObject().put("id",packageName).put("packageName",packageName).put("name",String.valueOf(info.applicationInfo.loadLabel(context.getPackageManager())))
            .put("versionName",info.versionName).put("versionCode",info.getLongVersionCode()).put("runnable",false).put("source","installed");
        JSONArray current=apps(),next=new JSONArray();boolean replaced=false;
        for(int i=0;i<current.length();i++){JSONObject item=current.getJSONObject(i);if(item.getString("id").equals(packageName)){next.put(app);replaced=true;}else next.put(item);}
        if(!replaced)next.put(app);atomic(new File(root,"apps.json"),next.toString(2));return app;
    }
    JSONObject app(String id) throws Exception { JSONArray list=apps();for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))return list.getJSONObject(i);throw new IOException("应用记录不存在"); }
    List<JSONObject> projects() throws Exception {
        List<JSONObject> list=new ArrayList<>();File[] dirs=root.listFiles();if(dirs!=null)for(File dir:dirs)if(dir.isDirectory()&&new File(dir,"project.json").isFile())list.add(get(dir.getName()));
        list.sort((a,b)->Long.compare(b.optLong("createdAt"),a.optLong("createdAt")));return list;
    }
    synchronized String create(String title,JSONObject app) throws Exception {
        title=title.trim();if(title.isEmpty()||title.length()>80)throw new IllegalArgumentException("项目名称需要 1–80 个字符");
        String id=UUID.randomUUID().toString();createFiles(id,title,app);return id;
    }
    private void createFiles(String id,String title,JSONObject app) throws Exception {
        File dir=directory(id);mkdir(dir);for(String name:new String[]{"scripts","tests","config","assets","ai","runs","compatibility","runtime"})mkdir(new File(dir,name));
        atomic(new File(dir,"project.json"),new JSONObject().put("schema",1).put("id",id).put("name",title).put("app",app).put("createdAt",System.currentTimeMillis()).toString(2));
    }
    JSONObject get(String id) throws Exception { return new JSONObject(new String(Files.readAllBytes(new File(directory(id),"project.json").toPath()),StandardCharsets.UTF_8)); }
    String title(String id) { try{return get(id).getString("name");}catch(Exception error){return "项目不可用";} }
    boolean runnable(String id) { try{return GuestStore.PACKAGE.equals(get(id).getJSONObject("app").getString("packageName"));}catch(Exception error){return false;} }
    File data(String id) throws Exception { get(id);File data=new File(directory(id),"runtime");mkdir(data);return data; }
    private File resolve(String id,String path) throws Exception {
        File base=directory(id);get(id);
        if(path==null||path.isEmpty()||path.length()>240||path.startsWith("/")||path.contains("\\"))throw new IOException("无效的项目路径");
        File file=base;for(String part:path.split("/",-1)){if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("路径越界");file=new File(file,part);if(Files.isSymbolicLink(file.toPath()))throw new IOException("不支持符号链接");}
        if(!file.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator))throw new IOException("路径越界");return file;
    }
    String read(String id,String path,String fallback) {
        try{File f=resolve(id,path);if(!f.exists())return fallback;try(InputStream in=new FileInputStream(f)){return AiClient.readLimited(in,65536);}}
        catch(Exception error){throw new IllegalStateException("读取项目文件失败："+path,error);}
    }
    synchronized void write(String id,String path,String value) throws Exception {
        File file=resolve(id,path);if(path.equals("project.json")||path.startsWith("runtime/"))throw new IOException("该文件由运行器管理");
        if(value.getBytes(StandardCharsets.UTF_8).length>65536)throw new IOException("项目文本超过 64 KiB");mkdir(file.getParentFile());atomic(file,value);
    }
    static void atomic(File file,String value) throws Exception {
        AtomicFile target=new AtomicFile(file);FileOutputStream out=target.startWrite();
        try{out.write(value.getBytes(StandardCharsets.UTF_8));target.finishWrite(out);}catch(Exception error){target.failWrite(out);throw error;}
    }
    List<String> files(String id) throws Exception { get(id);List<String> result=new ArrayList<>();collect(directory(id),"",result);Collections.sort(result);return result; }
    private void collect(File dir,String prefix,List<String> files){File[] children=dir.listFiles();if(children==null)return;for(File f:children){if(files.size()>=200||f.getName().startsWith(".")||Files.isSymbolicLink(f.toPath()))continue;String path=prefix+f.getName();if(f.isDirectory()){if(!path.equals("runtime"))collect(f,path+"/",files);}else files.add(path);}}
    void event(String id,String type,JSONObject detail) throws Exception { write(id,"runs/"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".json",new JSONObject().put("projectId",id).put("type",type).put("time",System.currentTimeMillis()).put("detail",detail).toString(2)); }
    void message(String id,String role,String text) throws Exception {
        JSONArray old=new JSONArray(read(id,"ai/conversation.json","[]")),next=new JSONArray();
        for(int i=Math.max(0,old.length()-11);i<old.length();i++)next.put(old.get(i));
        next.put(new JSONObject().put("role",role).put("text",text.length()>3000?text.substring(0,3000):text).put("time",System.currentTimeMillis()));write(id,"ai/conversation.json",next.toString(2));
    }
}
