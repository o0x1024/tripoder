package dev.tripoder.nativelibrarycontract;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONObject;

/** Absolute JNI loads and DT_NEEDED must share one installation's native state. */
public final class Main extends Activity {
    private static native long dependencyIdentity();
    private static native boolean dependencyInitialized();
    private static native long driverDependencyIdentity();
    private static native boolean driverDependencyInitialized();
    private static native long namedDependencyIdentity();
    private static native boolean namedDependencyInitialized();
    private static native long namedDriverDependencyIdentity();
    private static native boolean namedDriverDependencyInitialized();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        TextView text=new TextView(this);setContentView(text);text.setText("RUNNING OWNED NATIVE LIBRARY CONTRACT");
        new Thread(()->{
            JSONObject result=new JSONObject(),checks=new JSONObject();
            try{
                String directory=getApplicationInfo().nativeLibraryDir;
                System.load(directory+"/libowneddependency.so");
                checks.put("absolute-load-jni-initialized",dependencyInitialized());
                System.load(directory+"/libowneddriver.so");
                checks.put("needed-shares-absolute-load",dependencyIdentity()==driverDependencyIdentity());
                checks.put("needed-sees-jni-initialization",driverDependencyInitialized());
                System.loadLibrary("owneddependency");
                checks.put("name-and-absolute-load-share-state",dependencyIdentity()==driverDependencyIdentity());
                System.load(directory+"/libownednameddependency.so");
                checks.put("soname-absolute-load-jni-initialized",namedDependencyInitialized());
                System.load(directory+"/libownednameddriver.so");
                checks.put("soname-needed-shares-absolute-load",namedDependencyIdentity()==namedDriverDependencyIdentity());
                checks.put("soname-needed-sees-jni-initialization",namedDriverDependencyInitialized());
                boolean passed=true;
                for(var names=checks.keys();names.hasNext();)passed&=checks.getBoolean(names.next());
                result.put("passed",passed).put("checks",checks.length()).put("results",checks);
            }catch(Throwable error){
                try{result.put("passed",false).put("checks",checks.length()).put("results",checks).put("errorType",error.getClass().getName()).put("error",error.getMessage());}catch(Exception invalid){throw new IllegalStateException(invalid);}
            }
            String report=result.toString();android.util.Log.i("TripoderNativeLibraryContract",report);
            runOnUiThread(()->text.setText(report));
        },"owned-native-library").start();
    }
}
