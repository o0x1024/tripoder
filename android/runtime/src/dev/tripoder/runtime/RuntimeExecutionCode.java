package dev.tripoder.runtime;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Verified executable copies live beside mutable ART/packer sidecars, outside the sealed installation tree. */
final class RuntimeExecutionCode {
    final File directory;
    final String canonicalDirectory,visibleDirectory;
    private final Set<String> parts;
    private RuntimeExecutionCode(File directory,String visibleDirectory,Set<String> parts)throws IOException{
        this.directory=directory;canonicalDirectory=directory.getCanonicalPath();this.visibleDirectory=visibleDirectory;this.parts=Set.copyOf(parts);
    }
    static RuntimeExecutionCode prepare(ArtifactSet artifacts,File data,String generation)throws Exception{
        if(!generation.matches("[A-Za-z0-9_-]{1,80}"))throw new SecurityException("EXECUTION_CODE_GENERATION_INVALID");
        String name=artifacts.packageName+"-"+generation;File parent=new File(data,"code");directory(parent);
        File directory=new File(parent,name);directory(directory);
        Path lock=new File(parent,"."+name+".lock").toPath();
        try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lease=channel.lock()){
            if(!lease.isValid())throw new IOException("EXECUTION_CODE_LEASE_LOST");
            Set<String> parts=new LinkedHashSet<>();
            for(var part:artifacts.parts){parts.add(part.path);materialize(part.file,new File(directory,part.path),part.size,part.sha256);}
            File libraries=new File(directory,"lib");directory(libraries);libraries=new File(libraries,"arm64");directory(libraries);
            for(var file:artifacts.nativeFiles){
                if(!file.path.startsWith("lib/")||file.path.substring(4).contains("/"))throw new SecurityException("EXECUTION_NATIVE_PATH_INVALID");
                materialize(new File(artifacts.directory,file.path),new File(libraries,file.path.substring(4)),file.size,file.sha256);
            }
            return new RuntimeExecutionCode(directory,"/data/user/0/"+artifacts.packageName+"/code/"+name,parts);
        }
    }
    String part(String name){if(!parts.contains(name))throw new SecurityException("EXECUTION_PART_OUTSIDE_INSTALLATION");return visibleDirectory+"/"+name;}
    String libraryPath(String name){return visibleDirectory+"/lib/arm64/"+name;}
    File libraryFile(String name){return new File(directory,"lib/arm64/"+name);}
    private static void directory(File directory)throws Exception{
        if(Files.isSymbolicLink(directory.toPath()))throw new SecurityException("EXECUTION_CODE_SYMLINK");
        if(!directory.isDirectory()&&!directory.mkdir()&&!directory.isDirectory())throw new IOException("EXECUTION_CODE_DIRECTORY_FAILED");
        StructStat stat=Os.lstat(directory.toString());if(!OsConstants.S_ISDIR(stat.st_mode)||stat.st_uid!=android.os.Process.myUid())throw new SecurityException("EXECUTION_CODE_DIRECTORY_OWNER");
    }
    private static void materialize(File source,File target,long size,String expected)throws Exception{
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
            ArtifactSet.regular(target.toPath());StructStat before=Os.lstat(target.toString());
            if(before.st_uid!=android.os.Process.myUid()||before.st_nlink!=1)throw new SecurityException("EXECUTION_CODE_FILE_OWNER");
            if(before.st_size!=size||!ArtifactSet.digest(target).equals(expected))throw new SecurityException("EXECUTION_CODE_CHANGED:"+target.getName());
            StructStat after=Os.lstat(target.toString());if(!stamp(before).equals(stamp(after)))throw new SecurityException("EXECUTION_CODE_CHANGED_DURING_VERIFICATION");
            if((after.st_mode&0222)!=0&&!target.setReadOnly())throw new IOException("EXECUTION_CODE_READONLY_FAILED");
            ArtifactSet.readonly(target.toPath());return;
        }
        ArtifactSet.regular(source.toPath());File temporary=new File(target.getParentFile(),".incoming-"+UUID.randomUUID());
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long copied=0;
            try(InputStream input=new FileInputStream(source);FileOutputStream output=new FileOutputStream(temporary)){
                byte[] buffer=new byte[262144];int count;while((count=input.read(buffer))!=-1){copied+=count;if(copied>size)throw new SecurityException("EXECUTION_SOURCE_SIZE_CHANGED");output.write(buffer,0,count);digest.update(buffer,0,count);}output.getFD().sync();
            }
            if(copied!=size||!HexFormat.of().formatHex(digest.digest()).equals(expected))throw new SecurityException("EXECUTION_SOURCE_DIGEST_CHANGED");
            if(!temporary.setReadOnly())throw new IOException("EXECUTION_CODE_READONLY_FAILED");
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary.toPath());}
    }
    private static String stamp(StructStat s){return s.st_dev+":"+s.st_ino+":"+s.st_mode+":"+s.st_nlink+":"+s.st_uid+":"+s.st_gid+":"+s.st_size+":"+s.st_mtim.tv_sec+":"+s.st_mtim.tv_nsec+":"+s.st_ctim.tv_sec+":"+s.st_ctim.tv_nsec;}
}
