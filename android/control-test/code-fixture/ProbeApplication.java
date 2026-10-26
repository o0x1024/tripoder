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
    private static Throwable expectedError;private static Thread expectedThread;
    private static int objectCalls,overrideCalls;private static boolean objectIdentity=true;
    public static final class HostileException extends RuntimeException {
        public HostileException(){super("owned-object-message",new IllegalArgumentException("owned-object-cause"));}
        @Override public String getMessage(){overrideCalls++;throw new AssertionError("guest getMessage must not run");}
        @Override public synchronized Throwable getCause(){overrideCalls++;throw new AssertionError("guest getCause must not run");}
        @Override public StackTraceElement[] getStackTrace(){overrideCalls++;throw new AssertionError("guest getStackTrace must not run");}
        @Override public String toString(){overrideCalls++;throw new AssertionError("guest toString must not run");}
    }
    public static void uncaughtException(Thread thread,Throwable error){objectCalls++;objectIdentity&=thread==expectedThread&&error==expectedError;}
    public static void handleException(Throwable error){objectCalls++;objectIdentity&=error==expectedError;}
    public static Throwable echoThrowable(Throwable error){objectCalls++;objectIdentity&=error==expectedError;return error;}
    public static String runObjectChecks(){expectedThread=Thread.currentThread();expectedError=new HostileException();expectedError.addSuppressed(new IllegalStateException("owned-suppressed"));uncaughtException(expectedThread,expectedError);handleException(expectedError);objectIdentity&=echoThrowable(expectedError)==expectedError;return objectSummary();}
    public static String objectSummary(){return "calls="+objectCalls+";identity="+objectIdentity+";overrides="+overrideCalls;}
    public static Throwable forbiddenObjectResult(){objectCalls+=1000;return expectedError;}
    public static String captureThenExit(){runObjectChecks();android.os.Process.killProcess(android.os.Process.myPid());return "exit-requested";}
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
    @Override public void onCreate(){super.onCreate();startup=startupValue();runObjectChecks();}
}
