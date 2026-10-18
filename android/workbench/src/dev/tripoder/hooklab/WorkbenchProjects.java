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
    JSONObject get(String id)throws Exception{
        JSONObject value=new JSONObject(readFile(new File(directory(id),"project.json")));
        if(value.getInt("schema")!=2||!id.equals(value.getString("id")))throw new IOException("项目记录无效");return value;
    }
    List<JSONObject> all()throws Exception{
        checkDirectory(root);List<JSONObject> result=new ArrayList<>();File[] dirs=root.listFiles();if(dirs==null){if(root.exists())throw new IOException("项目目录不可读");return result;}
        for(File dir:dirs){if(Files.isSymbolicLink(dir.toPath())||!dir.isDirectory())throw new IOException("项目目录类型无效");if(new File(dir,"project.json").isFile())result.add(get(dir.getName()));}
        result.sort(Comparator.comparingLong(value->-value.optLong("createdAt")));return result;
    }
    /** Removes one project's source. Symbolic links are never followed, and an absent project is already gone. */
    synchronized void delete(String id)throws Exception{
        checkDirectory(root);File dir=new File(root,id(id));
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
    long modified(String id){try{return Files.getLastModifiedTime(new File(directory(id),"scripts/main.ts").toPath(),LinkOption.NOFOLLOW_LINKS).toMillis();}catch(Exception unreadable){return 0;}}
    String script(String id)throws Exception{get(id);return readFile(new File(directory(id),"scripts/main.ts"));}
    String selectors(String id)throws Exception{get(id);File file=new File(directory(id),"selectors.json");return file.exists()?readFile(file):"[]";}
    synchronized void selectors(String id,String source)throws Exception{get(id);var array=new org.json.JSONArray(source);if(array.length()<1||array.length()>32||source.length()>24000)throw new IOException("目标声明需要 1–32 项且不能超过 24,000 字符");atomic(new File(directory(id),"selectors.json"),array.toString(2));}
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
