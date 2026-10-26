package dev.tripoder.runtime;

import android.os.ParcelFileDescriptor;
import android.system.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Read-only, PackageInstaller-managed data-app code, independently checked in both processes. */
final class RuntimePublishedCodeFiles {
    static boolean trusted(int id){return id==0||id==1000;}
    private static void ownership(StructStat stat,boolean directory){
        if((stat.st_mode&OsConstants.S_IFMT)!=(directory?OsConstants.S_IFDIR:OsConstants.S_IFREG)||!trusted(stat.st_uid)||(stat.st_mode&0002)!=0||((stat.st_mode&0020)!=0&&!trusted(stat.st_gid)))throw new SecurityException("PUBLIC_CODE_OWNERSHIP_REJECTED");
    }
    static String canonical(String path)throws Exception{
        if(path==null||!path.startsWith("/data/app/")||path.indexOf(':')>=0||path.indexOf('!')>=0||!new File(path).getCanonicalPath().equals(path))throw new SecurityException("PUBLIC_CODE_PATH_REJECTED");return path;
    }
    static void directory(String path)throws Exception{
        canonical(path);File current=new File(path);
        while(current!=null&&current.getPath().startsWith("/data/app")){
            ownership(Os.lstat(current.getPath()),true);if(current.getPath().equals("/data/app"))return;current=current.getParentFile();
        }
        throw new SecurityException("PUBLIC_CODE_PARENT_REJECTED");
    }
    static boolean same(StructStat a,StructStat b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_mtime==b.st_mtime&&a.st_uid==b.st_uid&&a.st_gid==b.st_gid&&a.st_mode==b.st_mode;}
    static String digest(ParcelFileDescriptor fd,long size)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[262144];long at=0;
        while(at<size){int count=Os.pread(fd.getFileDescriptor(),buffer,0,(int)Math.min(buffer.length,size-at),at);if(count<=0)throw new EOFException("PUBLIC_CODE_TRUNCATED");sha.update(buffer,0,count);at+=count;}return HexFormat.of().formatHex(sha.digest());
    }
    static Open open(String path,String root,String digest,long size)throws Exception{
        canonical(root);canonical(path);if(!path.startsWith(root+"/")||size<=0||size>ArtifactSet.MAX_APK_BYTES||digest==null||!digest.matches("[0-9a-f]{64}"))throw new SecurityException("PUBLIC_CODE_SCOPE_REJECTED");
        directory(new File(path).getParent());StructStat before=Os.lstat(path);ownership(before,false);if(before.st_size!=size)throw new SecurityException("PUBLIC_CODE_SIZE_CHANGED");
        ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY);
        try{
            StructStat actual=Os.fstat(fd.getFileDescriptor());ownership(actual,false);
            if(!same(before,actual)||!digest(fd,size).equals(digest)||!same(actual,Os.lstat(path)))throw new SecurityException("PUBLIC_CODE_DIGEST_OR_IDENTITY_CHANGED");
            return new Open(path,fd,actual);
        }catch(Exception|Error failure){fd.close();throw failure;}
    }
    static final class Open implements AutoCloseable {
        final String path;final ParcelFileDescriptor fd;final StructStat stat;
        Open(String path,ParcelFileDescriptor fd,StructStat stat){this.path=path;this.fd=fd;this.stat=stat;}
        String descriptor(){return "/proc/self/fd/"+fd.getFd();}
        void unchanged()throws Exception{if(!same(stat,Os.lstat(path)))throw new SecurityException("PUBLIC_CODE_REPLACED");}
        @Override public void close()throws IOException{fd.close();}
    }
    private RuntimePublishedCodeFiles(){}
}
