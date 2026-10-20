package dev.tripoder.probefixture;

import android.app.Application;
import android.content.Context;
import dalvik.system.DexClassLoader;
import dalvik.system.InMemoryDexClassLoader;
import java.io.*;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;

/** Owned standalone APK. No Tripoder classes or host calls. The payload is an
 * asset, absent from this APK's class definitions, loaded before onCreate. */
public final class ProbeApplication extends Application {
    public static String startup="",memoryValue="",fileValue="",failure="";
    public static Method memoryMethod;public static ClassLoader memoryLoader,fileLoader;
    public static String startupValue(){return "original";}
    @Override protected void attachBaseContext(Context context){super.attachBaseContext(context);try{
        byte[] dex;try(InputStream in=getAssets().open("payload.dex")){dex=in.readAllBytes();}
        memoryLoader=new InMemoryDexClassLoader(ByteBuffer.wrap(dex),getClassLoader());
        memoryMethod=Class.forName("dev.tripoder.dynamic.Payload",true,memoryLoader).getMethod("value");memoryValue=(String)memoryMethod.invoke(null);
        File file=new File(getFilesDir(),"payload.dex");if(file.exists()&&!file.delete())throw new IOException("OLD_PAYLOAD_DELETE");
        try(FileOutputStream output=new FileOutputStream(file)){android.system.Os.fchmod(output.getFD(),0444);output.write(dex);output.getFD().sync();}
        fileLoader=new DexClassLoader(file.toString(),getCodeCacheDir().toString(),null,getClassLoader());
        fileValue=(String)Class.forName("dev.tripoder.dynamic.Payload",true,fileLoader).getMethod("value").invoke(null);
    }catch(Throwable error){failure=error.toString();}}
    @Override public void onCreate(){super.onCreate();startup=startupValue();}
}
