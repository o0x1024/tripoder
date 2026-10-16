package dev.tripoder.selfhookprobe;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import java.lang.reflect.Field;
import org.json.JSONObject;

/** Owned, second-loader application. It exercises legitimate context unwrapping. */
public final class LogicalApplicationFixture extends Application {
    public static int constructors,attaches,creates,denials;
    private Object logicalApk;
    public LogicalApplicationFixture(){constructors++;}
    private static void require(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    private boolean resolvesSelf(ClassLoader selected){
        try{return Class.forName(getClass().getName(),false,selected)==getClass();}
        catch(ClassNotFoundException error){return false;}
    }
    @Override protected void attachBaseContext(Context base){
        require(attaches++==0,"FIXTURE_ATTACH_ONCE");
        require(base.getPackageName().equals("dev.tripoder.selfhookprobe.logical"),"FIXTURE_LOGICAL_PACKAGE");
        require(resolvesSelf(base.getClassLoader()),"FIXTURE_SELECTED_LOADER_DEFINITION");
        require(base.getApplicationContext()==this,"FIXTURE_LOGICAL_OUTER_CONTEXT");
        try {
            Context raw=base;int depth=0;
            while(raw instanceof ContextWrapper&&depth++<16)raw=((ContextWrapper)raw).getBaseContext();
            require(raw.getClass().getName().equals("android.app.ContextImpl"),"FIXTURE_REAL_CONTEXT_IMPL");
            Field packageInfo=raw.getClass().getDeclaredField("mPackageInfo");packageInfo.setAccessible(true);
            logicalApk=packageInfo.get(raw);
            Field owner=logicalApk.getClass().getDeclaredField("mApplication");owner.setAccessible(true);
            require(owner.get(logicalApk)==this,"FIXTURE_LOGICAL_APK_OWNER");owner.set(logicalApk,this);
            Class<?> threadType=Class.forName("android.app.ActivityThread",false,null);
            Object thread=threadType.getDeclaredMethod("currentActivityThread").invoke(null);
            Field initial=threadType.getDeclaredField("mInitialApplication");initial.setAccessible(true);
            require(initial.get(thread)==this,"FIXTURE_INITIAL_LOGICAL_VIEW");initial.set(thread,this);
            try{initial.set(thread,null);throw new IllegalStateException("FIXTURE_NULL_VIEW_WRITE_ACCEPTED");}catch(SecurityException expected){denials++;}
            try{initial.set(thread,new Application());throw new IllegalStateException("FIXTURE_FOREIGN_VIEW_WRITE_ACCEPTED");}catch(SecurityException expected){denials++;}
        }catch(ReflectiveOperationException error){throw new IllegalStateException("FIXTURE_FRAMEWORK_ACCESS",error);}
        super.attachBaseContext(base);
        require(getBaseContext()==base,"FIXTURE_BASE_IDENTITY");
    }
    @Override public void onCreate(){
        require(creates++==0,"FIXTURE_ON_CREATE_ONCE");super.onCreate();
        try {Field apk=Application.class.getDeclaredField("mLoadedApk");apk.setAccessible(true);
            require(apk.get(this)==logicalApk,"FIXTURE_APPLICATION_ATTACH_APK_IDENTITY");
        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        require(getApplicationContext()==this&&resolvesSelf(getClassLoader()),"FIXTURE_CONTEXT_AFTER_ON_CREATE");
    }
    public String report() throws Exception {return new JSONObject().put("constructors",constructors).put("attaches",attaches)
        .put("creates",creates).put("denials",denials).put("package",getPackageName()).toString();}
}
