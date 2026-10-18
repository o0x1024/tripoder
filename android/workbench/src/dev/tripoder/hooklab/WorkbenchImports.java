package dev.tripoder.hooklab;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import dev.tripoder.control.ControlWire;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Original base/split bytes cross the UID boundary only as bounded read-only descriptors. */
final class WorkbenchImports {
    private final Context host;private final WorkbenchOperations operations;private final File root;
    private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    volatile boolean preparing;
    private interface Input {InputStream open()throws Exception;}
    WorkbenchImports(Context host,WorkbenchOperations operations){
        this.host=host;this.operations=operations;root=new File(host.getCacheDir(),"apk-import");
        if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("无法创建导入缓存");
        File[] abandoned=root.listFiles();if(abandoned==null)throw new IllegalStateException("导入缓存不可读");
        for(File file:abandoned)if(!file.delete())throw new IllegalStateException("无法清理中断的导入副本");
    }
    void importUri(Uri uri,String space){prepare(List.of(()->host.getContentResolver().openInputStream(uri)),space);}
    void importInstalled(String pkg,String space){
        try{var info=host.getPackageManager().getApplicationInfo(pkg,0);List<Input> sources=new ArrayList<>();String base=info.sourceDir;sources.add(()->new FileInputStream(base));
            if(info.splitSourceDirs!=null)for(String split:info.splitSourceDirs)sources.add(()->new FileInputStream(split));prepare(sources,space);
        }catch(Exception error){operations.inform("读取手机应用失败："+UiText.error(error));}
    }
    private void prepare(List<Input> sources,String space){
        if(preparing||operations.busyUnscoped()){operations.inform("上一个导入仍在提交");return;}
        if(sources.isEmpty()||sources.size()>64){operations.inform("安装包集合需要 1–64 个 APK");return;}
        preparing=true;operations.inform("正在读取安装包…");
        io.execute(()->{
            List<File> files=new ArrayList<>();List<ParcelFileDescriptor> descriptors=new ArrayList<>();
            try{
                Bundle request=ControlWire.request("install","operation",UUID.randomUUID().toString(),"space",space);
                ArrayList<String> names=new ArrayList<>(),hashes=new ArrayList<>();long total=0,notified=0,lastSpaceCheck=-4L*1024*1024;
                for(int index=0;index<sources.size();index++){
                    File file=new File(root,UUID.randomUUID()+".apk");files.add(file);MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
                    try(InputStream input=sources.get(index).open();FileOutputStream output=new FileOutputStream(file)){
                        if(input==null)throw new IOException("无法读取所选文件");byte[] buffer=new byte[65536];int count;
                        while((count=input.read(buffer))!=-1){bytes+=count;total+=count;if(bytes>1024L*1024*1024)throw new IOException("单个 APK 超过 1 GiB 导入限额");if(total-lastSpaceCheck>=4L*1024*1024){if(root.getUsableSpace()<68L*1024*1024)throw new IOException("可用空间不足，至少需要保留 64 MiB");lastSpaceCheck=total;}
                            output.write(buffer,0,count);digest.update(buffer,0,count);if(total-notified>=4*1024*1024){notified=total;long copied=total;main.post(()->operations.inform("正在读取安装包 · "+copied/(1024*1024)+" MiB"));}
                        }output.getFD().sync();
                    }
                    if(bytes==0)throw new IOException("所选 APK 为空");
                    ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);descriptors.add(descriptor);request.putParcelable("fd"+index,descriptor);names.add(index==0?"base.apk":"split-"+index+".apk");hashes.add(HexFormat.of().formatHex(digest.digest()));
                }
                request.putStringArrayList("names",names);request.putStringArrayList("sha256",hashes);
                main.post(()->{preparing=false;try{operations.submit(request,"添加应用",()->cleanup(files,descriptors));}
                    catch(Exception error){try{cleanup(files,descriptors);}catch(IOException close){error.addSuppressed(close);}operations.inform("导入没有提交："+UiText.error(error));}});
            }catch(Exception error){try{cleanup(files,descriptors);}catch(IOException close){error.addSuppressed(close);}main.post(()->{preparing=false;operations.inform("导入没有提交："+UiText.error(error));});}
        });
    }
    private static void cleanup(List<File> files,List<ParcelFileDescriptor> descriptors)throws IOException{
        IOException failure=null;for(var descriptor:descriptors)try{descriptor.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        for(File file:files)if(file.exists()&&!file.delete()){IOException error=new IOException("导入副本未清理");if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw failure;
    }
}
