package dev.tripoder.runtime;

import android.content.pm.ApplicationInfo;
import android.graphics.*;
import android.os.Bundle;
import dev.tripoder.control.ControlWire;
import java.io.ByteArrayOutputStream;

/** Read-only APK resources. Never loads guest code or consults a host application's identity. */
final class RuntimeControllerPresentation {
    static Bundle read(RuntimeBroker broker, Bundle input) throws Exception {
        RuntimeControllerCommands.fields(input,"command","space","package","generation");
        var install=broker.apks.get(ControlWire.required(input,"space"),ControlWire.required(input,"package"));
        if(!install.generation.equals(ControlWire.required(input,"generation")))throw new IllegalStateException("STALE_INSTALL");
        var info=new ApplicationInfo(install.info.applicationInfo);
        info.sourceDir=info.publicSourceDir=install.apk.toString();
        info.splitSourceDirs=info.splitPublicSourceDirs=install.artifacts.parts.stream().skip(1).map(part->part.file.toString()).toArray(String[]::new);
        var pm=broker.apks.host.getPackageManager();
        var resources=pm.getResourcesForApplication(info);
        String label=info.labelRes==0?(info.nonLocalizedLabel==null?info.packageName:info.nonLocalizedLabel.toString()):resources.getText(info.labelRes).toString();
        Bundle out=new Bundle();out.putString("label",label);out.putString("generation",install.generation);
        if(info.icon!=0){
            var drawable=resources.getDrawable(info.icon,null);var bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);
            try{drawable.setBounds(0,0,96,96);drawable.draw(new Canvas(bitmap));var bytes=new ByteArrayOutputStream();
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)||bytes.size()>65536)throw new IllegalStateException("APP_ICON_SIZE_INVALID");out.putByteArray("icon",bytes.toByteArray());
            }finally{bitmap.recycle();}
        }
        return out;
    }
    private RuntimeControllerPresentation(){}
}
