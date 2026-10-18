package dev.tripoder.runtime;

import android.content.ActivityNotFoundException;
import android.content.pm.ApplicationInfo;
import android.graphics.*;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Read-only APK resources. Never loads guest code or consults a host application's identity. */
final class RuntimeControllerPresentation {
    static Bundle read(RuntimeBroker broker, Bundle input) throws Exception {
        RuntimeControllerCommands.fields(input,"command","space","package","generation");
        String space=ControlWire.required(input,"space");
        String pkg=ControlWire.required(input,"package");
        String generation=ControlWire.required(input,"generation");
        ApkStore.Row row;
        synchronized(broker.apks.authority){
            broker.catalog.requireSpace(space);
            RuntimeFiles.id(pkg);
            RuntimeFiles.id(generation);
            row=broker.apks.row(space,pkg);
        }
        if(row==null)throw new ActivityNotFoundException("PACKAGE_NOT_IN_SPACE:"+pkg);
        if(!"COMMITTED".equals(row.state))throw new SecurityException("INSTALL_CORRUPT:"+row.reason);
        if(!row.generation.equals(generation))throw new IllegalStateException("STALE_INSTALL");

        File cacheDir=new File(broker.apks.root,"presentations");
        File metaFile=new File(cacheDir,generation+".meta");
        File iconFile=new File(cacheDir,generation+".png");
        if(metaFile.exists()){
            Bundle out=new Bundle();
            out.putString("label",Files.readString(metaFile.toPath(),StandardCharsets.UTF_8));
            out.putString("generation",generation);
            if(iconFile.exists())out.putByteArray("icon",Files.readAllBytes(iconFile.toPath()));
            return out;
        }

        var install=broker.apks.get(space,pkg);
        if(!install.generation.equals(generation))throw new IllegalStateException("STALE_INSTALL");
        var info=new ApplicationInfo(install.info.applicationInfo);
        info.sourceDir=info.publicSourceDir=install.apk.toString();
        info.splitSourceDirs=info.splitPublicSourceDirs=install.artifacts.parts.stream().skip(1).map(part->part.file.toString()).toArray(String[]::new);
        var pm=broker.apks.host.getPackageManager();
        var resources=pm.getResourcesForApplication(info);
        String label=info.labelRes==0?(info.nonLocalizedLabel==null?info.packageName:info.nonLocalizedLabel.toString()):resources.getText(info.labelRes).toString();
        Bundle out=new Bundle();out.putString("label",label);out.putString("generation",install.generation);
        byte[] iconBytes=null;
        if(info.icon!=0){
            var drawable=resources.getDrawable(info.icon,null);var bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);
            try{drawable.setBounds(0,0,96,96);drawable.draw(new Canvas(bitmap));var bytes=new ByteArrayOutputStream();
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)||bytes.size()>65536)throw new IllegalStateException("APP_ICON_SIZE_INVALID");
                iconBytes=bytes.toByteArray();
                out.putByteArray("icon",iconBytes);
            }finally{bitmap.recycle();}
        }
        try{
            if(!cacheDir.exists())cacheDir.mkdirs();
            File tempMeta=new File(cacheDir,generation+".meta.tmp");
            Files.writeString(tempMeta.toPath(),label,StandardCharsets.UTF_8);
            tempMeta.renameTo(metaFile);
            if(iconBytes!=null){
                File tempIcon=new File(cacheDir,generation+".png.tmp");
                Files.write(tempIcon.toPath(),iconBytes);
                tempIcon.renameTo(iconFile);
            }
        }catch(Exception ignored){}
        return out;
    }
    private RuntimeControllerPresentation(){}
}
