package dev.tripoder.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Android/JVM-with-JSON source inspection checks. Inputs exist only in a newly created synthetic directory. */
public final class ProjectBindingsSourceTest {
    private final File work;
    private final JSONArray checks=new JSONArray();
    private interface Attempt {void run()throws Exception;}
    private ProjectBindingsSourceTest(File parent)throws Exception{work=new File(parent,"migration-source-tests-"+UUID.randomUUID());Files.createDirectory(work.toPath());}
    private void check(String name,boolean value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value));if(!value)throw new AssertionError(name);}
    private void rejects(String name,String reason,Attempt attempt)throws Exception{
        try{attempt.run();throw new AssertionError(name+" accepted");}
        catch(Exception error){check(name,error.getMessage()!=null&&error.getMessage().contains(reason));}
    }
    private File source(String name)throws Exception{
        File root=new File(work,name);Files.createDirectory(root.toPath());
        Files.writeString(new File(root,"project.json").toPath(),"{\"schema\":1,\"id\":\"legacy-owned\",\"app\":{\"packageName\":\"dev.tripoder.p0.a\",\"runnable\":true,\"source\":\"installed\"}}");return root;
    }
    private void run()throws Exception{
        File normal=source("normal");Files.createDirectories(new File(normal,"scripts").toPath());Files.createDirectories(new File(normal,"runtime/empty").toPath());
        Files.writeString(new File(normal,"scripts/main.ts").toPath(),"owned test script");
        Files.write(new File(normal,"runtime/binary.bin").toPath(),new byte[]{0,1,(byte)255});
        Files.writeString(new File(normal,".legacy-migrated").toPath(),"legacy history only");
        ProjectBindings.Snapshot initial=ProjectBindings.inspectSource(normal);
        check("source-package-is-metadata",initial.packageName.equals("dev.tripoder.p0.a")&&initial.projectId.equals("legacy-owned"));
        check("all-files-including-hidden",initial.files.size()==4&&initial.files.stream().anyMatch(f->f.path.equals(".legacy-migrated")));
        check("runtime-and-empty-directory-preserved",initial.directories.contains("runtime/empty")&&initial.files.stream().anyMatch(f->f.path.equals("runtime/binary.bin")&&f.size==3));
        check("stable-source-digest",initial.digest.equals(ProjectBindings.inspectSource(normal).digest));
        Files.write(new File(normal,"runtime/binary.bin").toPath(),new byte[]{0,2,(byte)255});
        var changed=ProjectBindings.inspectSource(normal);check("runtime-byte-change-invalidates-digest",!initial.digest.equals(changed.digest));
        Files.createDirectory(new File(normal,"another-empty").toPath());
        var directoryChanged=ProjectBindings.inspectSource(normal);check("empty-directory-change-invalidates-digest",!changed.digest.equals(directoryChanged.digest));
        Files.move(new File(normal,"scripts/main.ts").toPath(),new File(normal,"scripts/renamed.ts").toPath());
        check("rename-invalidates-digest",!directoryChanged.digest.equals(ProjectBindings.inspectSource(normal).digest));
        File rootLink=new File(work,"root-link");Files.createSymbolicLink(rootLink.toPath(),normal.toPath());
        rejects("root-symlink-rejected","MIGRATION_SOURCE_SYMLINK",()->ProjectBindings.inspectSource(rootLink));
        File link=source("link");Files.createSymbolicLink(new File(link,"alias").toPath(),new File(normal,"scripts").toPath());
        rejects("nested-symlink-rejected","MIGRATION_SOURCE_SYMLINK",()->ProjectBindings.inspectSource(link));
        File limit=source("file-limit");try(RandomAccessFile f=new RandomAccessFile(new File(limit,"large"),"rw")){f.setLength(ProjectBindings.MAX_FILE_BYTES+1);}
        rejects("single-file-limit","MIGRATION_FILE_LIMIT",()->ProjectBindings.inspectSource(limit));
        File total=source("total-limit");for(int i=0;i<4;i++)try(RandomAccessFile f=new RandomAccessFile(new File(total,"large-"+i),"rw")){f.setLength(ProjectBindings.MAX_FILE_BYTES);}
        rejects("total-content-limit","MIGRATION_TOTAL_LIMIT",()->ProjectBindings.inspectSource(total));
        File count=source("file-count");for(int i=0;i<ProjectBindings.MAX_FILES;i++)Files.createFile(new File(count,"file-"+i).toPath());
        rejects("file-count-limit","MIGRATION_FILE_COUNT_LIMIT",()->ProjectBindings.inspectSource(count));
        File dirs=source("directory-count");for(int i=0;i<=ProjectBindings.MAX_DIRECTORIES;i++)Files.createDirectory(new File(dirs,"dir-"+i).toPath());
        rejects("directory-count-limit","MIGRATION_DIRECTORY_LIMIT",()->ProjectBindings.inspectSource(dirs));
        File deep=source("deep");File current=deep;for(int i=0;i<=ProjectBindings.MAX_DEPTH;i++){current=new File(current,"d");Files.createDirectory(current.toPath());}
        rejects("directory-depth-limit","MIGRATION_DEPTH_LIMIT",()->ProjectBindings.inspectSource(deep));
        File noMetadata=new File(work,"no-metadata");Files.createDirectory(noMetadata.toPath());
        rejects("missing-project-json","MIGRATION_PROJECT_METADATA_MISSING",()->ProjectBindings.inspectSource(noMetadata));
        File schema=source("schema");Files.writeString(new File(schema,"project.json").toPath(),"{\"schema\":2,\"id\":\"old\",\"app\":{\"packageName\":\"dev.tripoder.p0.a\"}}");
        rejects("unsupported-source-schema","MIGRATION_SCHEMA_UNSUPPORTED",()->ProjectBindings.inspectSource(schema));
        File fractional=source("fractional-schema");Files.writeString(new File(fractional,"project.json").toPath(),"{\"schema\":1.5,\"id\":\"old\",\"app\":{\"packageName\":\"dev.tripoder.p0.a\"}}");
        rejects("fractional-schema-rejected","MIGRATION_SCHEMA_UNSUPPORTED",()->ProjectBindings.inspectSource(fractional));
        File runtimeFile=source("runtime-file");Files.writeString(new File(runtimeFile,"runtime").toPath(),"wrong shape");
        rejects("runtime-file-is-not-data-tree","MIGRATION_RUNTIME_NOT_DIRECTORY",()->ProjectBindings.inspectSource(runtimeFile));
        File path=source("path");Files.createFile(new File(path,"ambiguous\\name").toPath());
        rejects("backslash-path-rejected","MIGRATION_PATH_UNSUPPORTED",()->ProjectBindings.inspectSource(path));
        rejects("regular-file-is-not-export-tree","MIGRATION_SOURCE_NOT_DIRECTORY",()->ProjectBindings.inspectSource(new File(normal,"project.json")));
        File trailing=source("trailing");Files.writeString(new File(trailing,"project.json").toPath(),Files.readString(new File(trailing,"project.json").toPath())+" true");
        rejects("trailing-metadata-rejected","MIGRATION_PROJECT_METADATA_INVALID",()->ProjectBindings.inspectSource(trailing));
        check("inspection-never-creates-runtime-authority",!new File(work,"tripoder-runtime-v2.db").exists()&&!new File(work,"runtime").exists());
    }
    public static JSONObject run(File parent)throws Exception{
        ProjectBindingsSourceTest test=new ProjectBindingsSourceTest(parent);test.run();
        return new JSONObject().put("passed",true).put("scope","synthetic-source-inspection-only").put("checks",test.checks).put("work",test.work.toString());
    }
    public static void main(String[] args)throws Exception{System.out.println(run(new File(args[0])).toString());}
}
