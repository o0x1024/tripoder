package dev.tripoder.runtime;

import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Strict owned-tree operations. No symlink traversal, silent skips or overwrite recovery. */
final class RuntimeFiles {
    static String id(String value){if(value==null||!value.matches("[a-zA-Z0-9_.-]{1,150}")||value.equals(".")||value.equals(".."))throw new IllegalArgumentException("INVALID_ID");return value;}
    static void mkdir(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())||(!file.isDirectory()&&!file.mkdirs()))throw new IOException("DIRECTORY_UNAVAILABLE:"+file);}
    static void sync(File directory)throws Exception{var fd=Os.open(directory.toString(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW,0);try{Os.fsync(fd);}finally{Os.close(fd);}}
    static List<File> children(File directory)throws IOException{
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("SYMLINK_UNSUPPORTED");
        if(!directory.exists())return List.of();File[] files=directory.listFiles();if(files==null)throw new IOException("DIRECTORY_READ_FAILED:"+directory);return Arrays.asList(files);
    }
    static void deleteTree(File file)throws Exception{
        if(Files.isSymbolicLink(file.toPath())){Files.delete(file.toPath());return;}
        if(file.isDirectory()){Os.chmod(file.toString(),0700);for(File child:children(file))deleteTree(child);}
        if(!file.delete())throw new IOException("DELETE_FAILED:"+file);
    }
    static void write(InputStream in,File file,long limit)throws Exception{
        if(file.exists()||Files.isSymbolicLink(file.toPath()))throw new IOException("OUTPUT_EXISTS");
        try(FileOutputStream out=new FileOutputStream(file)){Os.chmod(file.toString(),0400);byte[] buffer=new byte[65536];long total=0,lastSpaceCheck=-4L*1024*1024;int n;
            while((n=in.read(buffer))!=-1){total+=n;if(total>limit)throw new IOException("PAYLOAD_LIMIT");if(total-lastSpaceCheck>=4L*1024*1024){ArtifactSet.requireSpace(file.getParentFile(),68L*1024*1024);lastSpaceCheck=total;}out.write(buffer,0,n);}out.getFD().sync();}
    }
    private RuntimeFiles(){}
}
