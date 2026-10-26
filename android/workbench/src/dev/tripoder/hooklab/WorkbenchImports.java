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
    final WorkbenchImportBatch batch;
    volatile boolean preparing;
    interface Prepared {void complete(boolean submitted,Exception failure);}
    private interface Input {InputStream open()throws Exception;}
    WorkbenchImports(Context host,WorkbenchOperations operations){
        this.host=host;this.operations=operations;root=new File(host.getCacheDir(),"apk-import");
        if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("无法创建导入缓存");
        File[] abandoned=root.listFiles();if(abandoned==null)throw new IllegalStateException("导入缓存不可读");
        for(File file:abandoned)if(!file.delete())throw new IllegalStateException("无法清理中断的导入副本");
        batch=new WorkbenchImportBatch(host,this,operations,((WorkbenchApplication)host.getApplicationContext()).engine);
    }
    void importUri(Uri uri,String space){importUri(uri,space,false);}
    void importUri(Uri uri,String space,boolean autoBind){prepare(List.of(()->host.getContentResolver().openInputStream(uri)),space,null,autoBind,UUID.randomUUID().toString(),null);}
    void importInstalled(String pkg,String space){importInstalled(pkg,space,true);}
    void importInstalled(String pkg,String space,boolean autoBind){importInstalled(pkg,space,autoBind,UUID.randomUUID().toString(),null);}
    void importBatchItem(String kind,String source,String space,String operation,Prepared result){if(kind.equals("phone"))importInstalled(source,space,true,operation,result);else prepare(List.of(()->host.getContentResolver().openInputStream(Uri.parse(source))),space,null,true,operation,result);}
    private void importInstalled(String pkg,String space,boolean autoBind,String operation,Prepared completion){
        try{
            var info=host.getPackageManager().getApplicationInfo(pkg,0);
            CharSequence label=host.getPackageManager().getApplicationLabel(info);
            String suggestedLabel=label!=null?label.toString().trim():pkg;
            List<Input> sources=new ArrayList<>();String base=info.sourceDir;sources.add(()->new FileInputStream(base));
            if(info.splitSourceDirs!=null)for(String split:info.splitSourceDirs)sources.add(()->new FileInputStream(split));
            prepare(sources,space,suggestedLabel,autoBind,operation,completion);
        }catch(Exception error){operations.inform("读取手机应用失败："+UiText.error(error));if(completion!=null)completion.complete(false,error);}
    }
    private void prepare(List<Input> sources,String space,String suggestedLabel,boolean autoBind,String operation,Prepared completion){
        if(preparing||operations.busyImport()){operations.inform("上一个导入仍在提交");if(completion!=null)completion.complete(false,new IllegalStateException("上一个导入仍在提交"));return;}
        if(sources.isEmpty()||sources.size()>64){operations.inform("安装包集合需要 1–64 个 APK");if(completion!=null)completion.complete(false,new IllegalArgumentException("安装包集合需要 1–64 个 APK"));return;}
        preparing=true;operations.inform("正在读取安装包…");
        io.execute(()->{
            List<File> files=new ArrayList<>();List<ParcelFileDescriptor> descriptors=new ArrayList<>();
            String appLabel=suggestedLabel;
            try{
                Bundle request=ControlWire.request("install","operation",operation,"space",space);
                ArrayList<String> names=new ArrayList<>(),hashes=new ArrayList<>();long total=0,notified=0,lastSpaceCheck=-4L*1024*1024;
                for(int index=0;index<sources.size();index++){
                    File file=new File(root,UUID.randomUUID()+".apk");files.add(file);MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
                    try(InputStream input=sources.get(index).open();FileOutputStream output=new FileOutputStream(file)){
                        if(input==null)throw new IOException("无法读取所选文件");byte[] buffer=new byte[65536];int count;
                        while((count=input.read(buffer))!=-1){bytes+=count;total+=count;if(bytes>dev.tripoder.runtime.ArtifactSet.MAX_APK_BYTES)throw new IOException("单个 APK 超过 2 GiB 导入限额");if(total>dev.tripoder.runtime.ArtifactSet.MAX_SET_BYTES)throw new IOException("APK 集合超过 4 GiB 导入限额");if(total-lastSpaceCheck>=4L*1024*1024){if(root.getUsableSpace()<68L*1024*1024)throw new IOException("可用空间不足，至少需要保留 64 MiB");lastSpaceCheck=total;}
                            output.write(buffer,0,count);digest.update(buffer,0,count);if(total-notified>=4*1024*1024){notified=total;long copied=total;main.post(()->operations.inform("正在读取安装包 · "+copied/(1024*1024)+" MiB"));}
                        }output.getFD().sync();
                    }
                    if(bytes==0)throw new IOException("所选 APK 为空");
                    ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);descriptors.add(descriptor);request.putParcelable("fd"+index,descriptor);names.add(index==0?"base.apk":"split-"+index+".apk");hashes.add(HexFormat.of().formatHex(digest.digest()));
                }
                if(appLabel==null&&!files.isEmpty()){
                    try{
                        var pm=host.getPackageManager();
                        var pi=pm.getPackageArchiveInfo(files.get(0).getAbsolutePath(),0);
                        if(pi!=null&&pi.applicationInfo!=null){
                            pi.applicationInfo.sourceDir=files.get(0).getAbsolutePath();
                            pi.applicationInfo.publicSourceDir=files.get(0).getAbsolutePath();
                            CharSequence cs=pm.getApplicationLabel(pi.applicationInfo);
                            if(cs!=null&&!cs.toString().trim().isEmpty())appLabel=cs.toString().trim();
                        }
                    }catch(Exception ignored){}
                }
                // The selected APK and space identify the UI import target; fence an upgrade to its current generation.
                WorkbenchApplication owner=(WorkbenchApplication)host.getApplicationContext();
                var archiveInfo=host.getPackageManager().getPackageArchiveInfo(files.get(0).getAbsolutePath(),0);
                if(archiveInfo!=null){var installed=new org.json.JSONArray(owner.engine.executeBlocking(ControlWire.request("catalog")).getString("installs"));for(int i=0;i<installed.length();i++){var entry=installed.getJSONObject(i);if(space.equals(entry.getString("space"))&&archiveInfo.packageName.equals(entry.getString("package")))request.putString("expectedGeneration",entry.getString("generation"));}}
                request.putStringArrayList("names",names);request.putStringArrayList("sha256",hashes);
                final String finalLabel=appLabel;
                main.post(()->{
                    preparing=false;
                    try{
                        operations.submit(request,"添加应用",()->cleanup(files,descriptors),autoBind?(value,error)->{
                            if(error==null&&value!=null){
                                autoCreateAndBindProject(value,finalLabel);
                            }
                        }:null);
                        if(completion!=null)completion.complete(true,null);
                    }catch(Exception error){
                        try{cleanup(files,descriptors);}catch(IOException close){error.addSuppressed(close);}
                        operations.inform("导入没有提交："+UiText.error(error));if(completion!=null)completion.complete(false,error);
                    }
                });
            }catch(Exception error){
                try{cleanup(files,descriptors);}catch(IOException close){error.addSuppressed(close);}
                main.post(()->{preparing=false;operations.inform("导入没有提交："+UiText.error(error));if(completion!=null)completion.complete(false,error);});
            }
        });
    }
    private void autoCreateAndBindProject(Bundle value,String suggestedLabel){
        WorkbenchApplication app=host instanceof WorkbenchApplication?(WorkbenchApplication)host:(host.getApplicationContext() instanceof WorkbenchApplication?(WorkbenchApplication)host.getApplicationContext():null);
        if(app==null||app.projects==null)return;
        app.engine.execute(ControlWire.request("catalog"),(snapshot,error)->{try{if(error!=null)throw error;new WorkbenchCatalog(snapshot).sync(app);}catch(Exception failure){operations.inform("应用已添加，项目将在刷新时恢复："+UiText.error(failure));}});
    }
    private static void cleanup(List<File> files,List<ParcelFileDescriptor> descriptors)throws IOException{
        IOException failure=null;for(var descriptor:descriptors)try{descriptor.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        for(File file:files)if(file.exists()&&!file.delete()){IOException error=new IOException("导入副本未清理");if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw failure;
    }
}
