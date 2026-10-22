package dev.tripoder.hooklab;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Workbench-owned source only. Installation and execution identities are always read from the engine. */
final class WorkbenchProjects {
    private final File root;
    WorkbenchProjects(Context host){root=new File(host.getFilesDir(),"projects");}
    static String id(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("项目 ID 无效");return id;}
    private File directory(String id){File dir=new File(root,id(id));checkDirectory(root);checkDirectory(dir);checkDirectory(new File(dir,"scripts"));return dir;}
    private static void checkDirectory(File dir){if(Files.isSymbolicLink(dir.toPath())||(dir.exists()&&!dir.isDirectory()))throw new IllegalStateException("项目目录类型无效");}
    synchronized String create(String title)throws Exception{
        if(title==null||title.trim().isEmpty()||title.length()>80)throw new IllegalArgumentException("项目名称需要 1–80 个字符");
        String id=UUID.randomUUID().toString();File directory=directory(id);
        if(!new File(directory,"scripts").mkdirs())throw new IOException("创建项目目录失败");
        atomic(new File(directory,"scripts/main.ts"),"// 在这里编写 Tripoder 脚本。部署需要真实目标证据与引擎确认。\n");
        atomic(new File(directory,"project.json"),new JSONObject().put("schema",2).put("id",id).put("name",title.trim()).put("createdAt",System.currentTimeMillis()).toString(2));return id;
    }
    /** Source identity comes from the authority's unique application ownership. */
    synchronized void reconcile(org.json.JSONArray applications)throws Exception{
        for(int i=0;i<applications.length();i++){
            JSONObject app=applications.getJSONObject(i);String id=app.getString("project"),pkg=app.getString("package");if(deleted(id))continue;File dir=directory(id);
            if(!new File(dir,"project.json").exists()){
                if(!new File(dir,"scripts").isDirectory()&&!new File(dir,"scripts").mkdirs())throw new IOException("创建项目目录失败");
                if(!new File(dir,"scripts/main.ts").exists())atomic(new File(dir,"scripts/main.ts"),"// 在这里编写 Tripoder 脚本。保存后按需部署到所选空间的进程。\n");
                atomic(new File(dir,"project.json"),new JSONObject().put("schema",2).put("id",id).put("name",pkg).put("package",pkg).put("createdAt",System.currentTimeMillis()).toString(2));
            }else{JSONObject meta=get(id);if(!pkg.equals(meta.optString("package"))){meta.put("package",pkg);atomic(new File(dir,"project.json"),meta.toString(2));}}
            org.json.JSONArray legacy=app.optJSONArray("legacyProjects");if(legacy!=null)for(int j=0;j<legacy.length();j++)archive(legacy.getString(j),pkg,id);
        }
        // UI and MCP catalog replies can arrive out of order. Only genuinely unbound legacy sources
        // may be inferred from absence; established application sources are deleted by explicit receipts.
        Set<String> current=new HashSet<>();for(int i=0;i<applications.length();i++)current.add(applications.getJSONObject(i).getString("project"));
        for(JSONObject project:all())if(!current.contains(project.getString("id"))&&project.optString("package").isEmpty())archive(project.getString("id"),project.optString("package",""),"");
    }
    private File archives(){File archive=new File(root.getParentFile(),"project-archives");checkDirectory(archive);return archive;}
    private void archive(String id,String pkg,String canonical)throws Exception{
        File source=directory(id);if(!source.exists())return;File archive=archives();if(!archive.isDirectory()&&!archive.mkdirs())throw new IOException("无法建立历史归档");
        File dest=new File(archive,id(id));if(dest.exists())throw new IOException("历史归档冲突："+id);
        if(!canonical.isEmpty()&&(!script(canonical).equals(script(id))||!selectors(canonical).equals(selectors(id)))){JSONObject current=get(canonical);current.put("migrationPending",true);atomic(new File(directory(canonical),"project.json"),current.toString(2));}
        JSONObject meta=get(id);meta.put("package",pkg).put("canonicalProject",canonical);atomic(new File(source,"project.json"),meta.toString(2));
        Files.move(source.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    synchronized java.util.List<JSONObject> archived(String pkg)throws Exception{
        java.util.List<JSONObject> out=new ArrayList<>();File[] dirs=archives().listFiles();if(dirs==null)return out;
        for(File dir:dirs){checkDirectory(dir);JSONObject meta=new JSONObject(readFile(new File(dir,"project.json")));if(pkg==null||pkg.equals(meta.optString("package")))out.add(meta);}
        return out;
    }
    synchronized String archivedScript(String id)throws Exception{return readFile(new File(new File(archives(),id(id)),"scripts/main.ts"));}
    synchronized void acceptMigration(String project)throws Exception{JSONObject meta=get(project);meta.put("migrationPending",false);atomic(new File(directory(project),"project.json"),meta.toString(2));}
    synchronized void restore(String project,String archived)throws Exception{
        String source=archivedScript(archived);JSONObject meta=get(project);String backup=UUID.randomUUID().toString();File dir=new File(archives(),backup+"/scripts");if(!dir.mkdirs())throw new IOException("无法备份当前脚本");
        atomic(new File(dir,"main.ts"),script(project));atomic(new File(dir.getParentFile(),"selectors.json"),selectors(project));atomic(new File(dir.getParentFile(),"project.json"),new JSONObject().put("id",backup).put("name","恢复前的脚本").put("package",meta.optString("package")).put("canonicalProject",project).toString());
        File selectors=new File(new File(archives(),id(archived)),"selectors.json");
        String raw=selectors.exists()?readFile(selectors):"[]";new org.json.JSONArray(raw);atomic(new File(directory(project),"selectors.json"),raw);
        script(project,source);
    }
    private boolean deleted(String id){return Files.exists(new File(root.getParentFile(),"project-tombstones/"+id(id)).toPath(),LinkOption.NOFOLLOW_LINKS);}
    synchronized JSONObject get(String id)throws Exception{
        if(deleted(id))throw new IOException("应用项目已删除，请刷新");
        JSONObject value=new JSONObject(readFile(new File(directory(id),"project.json")));
        if(value.getInt("schema")!=2||!id.equals(value.getString("id")))throw new IOException("项目记录无效");return value;
    }
    synchronized List<JSONObject> all()throws Exception{
        checkDirectory(root);List<JSONObject> result=new ArrayList<>();File[] dirs=root.listFiles();if(dirs==null){if(root.exists())throw new IOException("项目目录不可读");return result;}
        for(File dir:dirs){if(Files.isSymbolicLink(dir.toPath())||!dir.isDirectory())throw new IOException("项目目录类型无效");if(new File(dir,"project.json").isFile()&&!deleted(dir.getName()))result.add(get(dir.getName()));}
        result.sort(Comparator.comparingLong(value->-value.optLong("createdAt")));return result;
    }
    /** Removes one project's source. Symbolic links are never followed, and an absent project is already gone. */
    synchronized void delete(String id)throws Exception{
        checkDirectory(root);id(id);File tombstones=new File(root.getParentFile(),"project-tombstones");checkDirectory(tombstones);if(!tombstones.isDirectory()&&!tombstones.mkdirs())throw new IOException("无法保存项目删除记录");atomic(new File(tombstones,id),"deleted");for(JSONObject archive:archived(null))if(id.equals(archive.optString("canonicalProject"))||id.equals(archive.optString("id")))removeTree(new File(archives(),archive.getString("id")));File dir=new File(root,id);
        removeTree(dir);
    }
    private static void removeTree(File dir)throws Exception{
        if(!Files.exists(dir.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(dir.toPath())||!dir.isDirectory())throw new IllegalStateException("项目目录类型无效");
        Files.walkFileTree(dir.toPath(),new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attributes)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure)throws IOException{if(failure!=null)throw failure;Files.delete(directory);return FileVisitResult.CONTINUE;}
        });
    }
    synchronized void rename(String id,String title)throws Exception{
        if(title==null||title.trim().isEmpty()||title.length()>80)throw new IllegalArgumentException("项目名称需要 1–80 个字符");
        JSONObject value=get(id);value.put("name",title.trim());atomic(new File(directory(id),"project.json"),value.toString(2));
    }
    /** Last time the project's script was saved, for "edited … ago" in lists. */
    synchronized long modified(String id){try{return Files.getLastModifiedTime(new File(directory(id),"scripts/main.ts").toPath(),LinkOption.NOFOLLOW_LINKS).toMillis();}catch(Exception unreadable){return 0;}}
    synchronized String script(String id)throws Exception{get(id);return readFile(new File(directory(id),"scripts/main.ts"));}
    synchronized String selectors(String id)throws Exception{get(id);File file=new File(directory(id),"selectors.json");return file.exists()?readFile(file):"[]";}
    synchronized void selectors(String id,String source)throws Exception{get(id);var array=new org.json.JSONArray(source);if(array.length()<1||array.length()>32||source.length()>24000)throw new IOException("目标声明需要 1–32 项且不能超过 24,000 字符");atomic(new File(directory(id),"selectors.json"),array.toString(2));}
    synchronized void script(String id,String source,String expected)throws Exception{if(!script(id).equals(expected))throw new IOException("脚本已被其他入口修改，请重新读取后再保存");script(id,source);}
    synchronized void script(String id,String source)throws Exception{get(id);if(source.getBytes(StandardCharsets.UTF_8).length>65536)throw new IOException("脚本超过 64 KiB");atomic(new File(directory(id),"scripts/main.ts"),source);}
    private static String readFile(File file)throws Exception{
        if(Files.isSymbolicLink(file.toPath())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.size(file.toPath())>65536)throw new IOException("项目文件不可读或超过限额");
        try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){byte[] data=input.readNBytes(65537);if(data.length>65536)throw new IOException("项目文件超过限额");return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();}
    }
    private static void atomic(File file,String content)throws Exception{
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("项目文件不能是符号链接");AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=atomic.startWrite();
        try{stream.write(content.getBytes(StandardCharsets.UTF_8));atomic.finishWrite(stream);}catch(Exception error){atomic.failWrite(stream);throw error;}
    }
}
