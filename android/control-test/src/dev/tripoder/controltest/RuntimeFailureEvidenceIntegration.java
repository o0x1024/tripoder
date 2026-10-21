package dev.tripoder.controltest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
/** Read-only collection from an explicitly identified installation's crash files. */
public final class RuntimeFailureEvidenceIntegration extends ControlTestBase {
    @Override void test()throws Exception{
        String space=args.getString("space"),pkg=args.getString("package"),data=args.getString("dataId");
        if(space==null||!space.matches("[A-Za-z0-9_-]{1,80}")||pkg==null||!pkg.matches("[A-Za-z0-9_.]{1,200}")||data==null||!data.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("EXPLICIT_INSTALLATION_REQUIRED");
        File root=new File(getTargetContext().getFilesDir(),"runtime/data/"+space+"/"+pkg+"/"+data);List<File> files=new ArrayList<>();int[] visited={0};scan(root,0,visited,files);files.sort(Comparator.comparingLong(File::lastModified).reversed());
        JSONArray rows=new JSONArray();for(File file:files.subList(0,Math.min(6,files.size())))try(var input=new FileInputStream(file)){rows.put(new JSONObject().put("path",root.toPath().relativize(file.toPath()).toString()).put("modified",file.lastModified()).put("text",new String(input.readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8)));}
        report.put("scope","explicit-installation-failure-evidence").put("crashFiles",rows).put("visited",visited[0]);check("explicit-data-root-exists",root.isDirectory(),root.toString());
    }
    private static void scan(File file,int depth,int[] visited,List<File> files){
        if(depth>8||++visited[0]>12000||Files.isSymbolicLink(file.toPath()))return;
        if(file.isDirectory()){if(file.getName().equals("code"))return;File[] children=file.listFiles();if(children!=null)for(File child:children)scan(child,depth+1,visited,files);}
        else if((file.getName().endsWith(".xcrash")&&!file.getName().endsWith(".clean.xcrash"))||file.getName().startsWith("tombstone")||file.getName().endsWith(".trace"))files.add(file);
    }
}
