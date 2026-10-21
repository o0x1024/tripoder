package dev.tripoder.controltest;

import android.system.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** Owned APK only: immutable input, reusable verified copies and mutable optimizer sidecars. */
public final class ExecutionCodeIntegration extends ControlTestBase {
    @Override void test()throws Exception{
        report.put("scope","generic-verified-execution-code-materialization");
        File root=new File(getTargetContext().getCacheDir(),run);if(!root.mkdir())throw new IOException("TEST_ROOT_EXISTS");
        try{
            File source=new File(root,"source"),data=new File(root,"data");if(!source.mkdir()||!data.mkdir())throw new IOException("TEST_DIRECTORIES");
            File apk=new File(source,"base.apk");try(var input=getContext().getAssets().open("a.apk");var output=new FileOutputStream(apk)){input.transferTo(output);}
            Class<?> artifact=Class.forName("dev.tripoder.runtime.ArtifactSet"),codeType=Class.forName("dev.tripoder.runtime.RuntimeExecutionCode");
            Object set=artifact.getMethod("materialize",File.class).invoke(null,source);
            String expected;try(var input=getContext().getAssets().open("install-fixtures.json")){expected=new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getString("baseSha256");}
            Method prepare=codeType.getDeclaredMethod("prepare",artifact,File.class,String.class);prepare.setAccessible(true);String generation=UUID.randomUUID().toString();
            Object code=prepare.invoke(null,set,data,generation);Field field=codeType.getDeclaredField("directory");field.setAccessible(true);File directory=(File)field.get(code),copy=new File(directory,"base.apk");
            check("execution-input-is-a-separate-copy",!apk.getCanonicalPath().equals(copy.getCanonicalPath())&&digest(copy).equals(expected),true);
            check("copied-apk-remains-readonly",(Os.lstat(copy.toString()).st_mode&0222)==0,true);
            File nativeCopy=new File(directory,"lib/arm64/libp0fixture.so");check("native-copy-keeps-abi-directory-and-digest",nativeCopy.isFile()&&digest(nativeCopy).equals(digest(new File(source,"lib/libp0fixture.so")))&&(Os.lstat(nativeCopy.toString()).st_mode&0222)==0,true);
            File oat=new File(directory,"oat/arm64");if(!oat.mkdirs())throw new IOException("TEST_OAT_DIRECTORY");
            File sidecar=new File(oat,"base.odex"),profile=new File(directory,"base.apk.prof");Files.writeString(sidecar.toPath(),"derived");Files.writeString(profile.toPath(),"profile");
            prepare.invoke(null,set,data,generation);
            check("derived-art-and-profile-sidecars-remain-writable",Files.readString(sidecar.toPath()).equals("derived")&&profile.canWrite(),true);
            check("sealed-installation-not-used-for-sidecars",!new File(source,"base.apk.prof").exists()&&(Os.lstat(source.toString()).st_mode&0222)==0&&digest(apk).equals(expected),true);
            if(!copy.setWritable(true,true))throw new IOException("TEST_TAMPER_PERMISSION");try(var file=new RandomAccessFile(copy,"rw")){file.seek(0);file.writeByte(0);}
            boolean rejected=false;try{prepare.invoke(null,set,data,generation);}catch(InvocationTargetException error){rejected=String.valueOf(error.getCause()).contains("EXECUTION_CODE_CHANGED");}
            check("changed-executable-copy-rejected-before-load",rejected,true);
            check("cache-tamper-cannot-change-authenticated-input",digest(apk).equals(expected),true);
            String second=UUID.randomUUID().toString(),pkg=(String)artifact.getField("packageName").get(set);File other=new File(root,"other");other.mkdir();
            Files.createSymbolicLink(new File(data,"code/"+pkg+"-"+second).toPath(),other.toPath());
            rejected=false;try{prepare.invoke(null,set,data,second);}catch(InvocationTargetException error){rejected=String.valueOf(error.getCause()).contains("EXECUTION_CODE_SYMLINK");}
            check("execution-directory-symlink-rejected",rejected,true);
        }finally{
            try(var paths=Files.walk(root.toPath())){for(Path path:paths.toList())if(!Files.isSymbolicLink(path))path.toFile().setWritable(true,true);}
            try(var paths=Files.walk(root.toPath())){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
    private static String digest(File file)throws Exception{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(var input=new FileInputStream(file)){byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);}return HexFormat.of().formatHex(digest.digest());}
}
