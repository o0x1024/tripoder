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

/** Legacy workspaces, never the authority for runtime spaces or virtual installations. */
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
            File destination=new File(directory(DEFAULT),"runtime");
            validateLegacyCopy(oldData,destination);
            copyLegacy(oldData,destination);atomic(migrated,"done");
        }
    }
    /** Validate the whole source before copying; incomplete legacy data must not receive a done marker. */
    private static void validateLegacyCopy(File source,File destination) throws Exception {
        rejectLegacyLink(source);rejectLegacyLink(destination);
        if(!source.isDirectory())throw new IOException("LEGACY_SOURCE_NOT_DIRECTORY: "+source.getName());
        if(destination.exists()&&!destination.isDirectory())throw new IOException("LEGACY_DESTINATION_NOT_DIRECTORY: "+destination.getName());
        File[] files=source.listFiles();if(files==null)throw new IOException("LEGACY_DIRECTORY_READ_FAILED: "+source.getName());
        for(File file:files){
            rejectLegacyLink(file);File next=new File(destination,file.getName());rejectLegacyLink(next);
            if(file.isDirectory())validateLegacyCopy(file,next);
            else {
                byte[] bytes=legacyBytes(file);
                if(next.exists()&&!Arrays.equals(bytes,legacyBytes(next)))throw new IOException("LEGACY_DESTINATION_CONFLICT: "+file.getName());
            }
        }
    }
    private static void rejectLegacyLink(File file) throws IOException {
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("LEGACY_SYMLINK_UNSUPPORTED: "+file.getName());
    }
    private static byte[] legacyBytes(File file) throws IOException {
        rejectLegacyLink(file);
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("LEGACY_FILE_NOT_REGULAR: "+file.getName());
        try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1){
                if(output.size()+count>65536)throw new IOException("LEGACY_FILE_TOO_LARGE: "+file.getName());
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
    }
    private static void copyLegacy(File source,File destination) throws Exception {
        rejectLegacyLink(source);rejectLegacyLink(destination);mkdir(destination);
        File[] files=source.listFiles();if(files==null)throw new IOException("LEGACY_DIRECTORY_READ_FAILED: "+source.getName());
        for(File file:files){
            rejectLegacyLink(file);File next=new File(destination,file.getName());rejectLegacyLink(next);
            if(file.isDirectory())copyLegacy(file,next);
            else {
                byte[] bytes=legacyBytes(file);
                if(next.exists()){
                    if(!Arrays.equals(bytes,legacyBytes(next)))throw new IOException("LEGACY_DESTINATION_CONFLICT: "+file.getName());
                }else {
                    Files.write(next.toPath(),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                    if(!Arrays.equals(bytes,legacyBytes(next)))throw new IOException("LEGACY_COPY_VERIFICATION_FAILED: "+file.getName());
                }
            }
        }
    }
    static JSONObject fixture() throws Exception { return new JSONObject().put("id",GuestStore.PACKAGE).put("packageName",GuestStore.PACKAGE).put("name","Tripoder 示例 · legacy").put("versionName","1.1").put("versionCode",GuestStore.VERSION).put("source","bundled-legacy-fixture"); }
    /** Selects only the old owned-fixture experiment. It does not assert installation or runtime readiness. */
    static boolean legacyFixtureApp(JSONObject app) {
        if(!GuestStore.PACKAGE.equals(app.optString("id"))||!GuestStore.PACKAGE.equals(app.optString("packageName"))||app.optLong("versionCode",-1)!=GuestStore.VERSION)return false;
        return "bundled-legacy-fixture".equals(app.optString("source"));
    }
    static boolean hostSourceApp(JSONObject app) { return "host-package-snapshot".equals(app.optString("source"))||"installed".equals(app.optString("source")); }
    JSONArray apps() throws Exception {
        File f=new File(root,"apps.json");if(!f.exists())return new JSONArray().put(fixture());
        return new JSONArray(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
    }
    synchronized JSONObject addInstalled(String packageName) throws Exception {
        PackageInfo info=context.getPackageManager().getPackageInfo(packageName,0);
        JSONObject app=new JSONObject().put("id",packageName).put("packageName",packageName).put("name",String.valueOf(info.applicationInfo.loadLabel(context.getPackageManager())))
            .put("versionName",info.versionName).put("versionCode",info.getLongVersionCode()).put("source","host-package-snapshot");
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
    boolean legacyFixtureProject(String id) {
        try{JSONObject project=get(id);return project.getInt("schema")==1&&id.equals(project.getString("id"))&&legacyFixtureApp(project.getJSONObject("app"));}
        catch(Exception error){throw new IllegalStateException("旧示例项目记录无法读取",error);}
    }
    /** Read-only, bounded input to a future explicit migration; never initializes, copies data, or binds an install. */
    JSONObject exportLegacyProjectMetadata(String id) throws Exception {
        File dir=directory(id),metadata=new File(dir,"project.json");
        rejectLegacyLink(root);rejectLegacyLink(dir);rejectLegacyLink(metadata);
        if(!metadata.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("LEGACY_PROJECT_OUTSIDE_WORKSPACE");
        byte[] bytes=legacyBytes(metadata);
        String source=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        JSONObject project=new JSONObject(source);
        if(project.getInt("schema")!=1||!id.equals(project.getString("id")))throw new IOException("LEGACY_PROJECT_IDENTITY_MISMATCH");
        JSONObject app=project.getJSONObject("app");
        String packageName=app.getString("packageName");
        if(!packageName.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))throw new IOException("LEGACY_PACKAGE_NAME_INVALID");
        String kind=legacyFixtureApp(app)?"bundled-legacy-fixture":hostSourceApp(app)?"host-package-snapshot":"unclassified-legacy-source";
        JSONObject appSource=new JSONObject().put("kind",kind).put("packageName",packageName).put("name",app.getString("name"))
            .put("versionName",app.optString("versionName")).put("versionCode",app.getLong("versionCode"));
        return new JSONObject().put("schema",1).put("format","tripoder-legacy-project-metadata")
            .put("projectId",id).put("projectName",project.getString("name")).put("createdAt",project.getLong("createdAt"))
            .put("sourceProjectSha256",GuestStore.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("appSource",appSource).put("bindingState","UNBOUND").put("runtimeInstall",JSONObject.NULL).put("runtimeDataMigrated",false);
    }
    File data(String id) throws Exception { get(id);File data=new File(directory(id),"runtime");mkdir(data);return data; }
    private File resolve(String id,String path) throws Exception {
        File base=directory(id);get(id);
        if(path==null||path.isEmpty()||path.length()>240||path.startsWith("/")||path.contains("\\"))throw new IOException("无效的项目路径");
        File file=base;for(String part:path.split("/",-1)){if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("路径越界");file=new File(file,part);if(Files.isSymbolicLink(file.toPath()))throw new IOException("不支持符号链接");}
        if(!file.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator))throw new IOException("路径越界");return file;
    }
    String read(String id,String path,String defaultContent) {
        try{File f=resolve(id,path);if(!f.exists())return defaultContent;try(InputStream in=new FileInputStream(f)){return AiClient.readLimited(in,65536);}}
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
