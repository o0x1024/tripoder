package dev.tripoder.provideridentity;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.lang.reflect.*;
import org.json.*;

/** Owned platform fixture: changes construction without using any runtime implementation. */
public final class InstrumentationApplication extends Application {
    static JSONArray checks=new JSONArray();static int originals,children;static ClassLoader selectedLoader;static boolean switched;
    @Override public void onCreate(){super.onCreate();try{
        Class<?> type=Class.forName("android.app.ActivityThread");Method current=type.getDeclaredMethod("currentActivityThread");current.setAccessible(true);Object thread=current.invoke(null);
        Field field=type.getDeclaredField("mInstrumentation");field.setAccessible(true);field.set(thread,new ReplacementInstrumentation());
    }catch(Exception error){throw new IllegalStateException("OWNED_INSTRUMENTATION_INSTALL_FAILED",error);}}
    static void check(String name,boolean passed){try{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}catch(JSONException error){throw new IllegalStateException(error);}}
    public static class ReplacementInstrumentation extends Instrumentation {
        @Override public Context getContext(){
            if(!switched)try{
                Class<?> type=Class.forName("android.app.ActivityThread");Method current=type.getDeclaredMethod("currentActivityThread");current.setAccessible(true);Object thread=current.invoke(null);Field field=type.getDeclaredField("mInstrumentation");field.setAccessible(true);
                field.set(thread,new LateInstrumentation());switched=true;
            }catch(Exception error){throw new IllegalStateException("OWNED_LATE_INSTRUMENTATION_INSTALL_FAILED",error);}
            return super.getContext();
        }
        @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
            if(name.equals(InstrumentationActivity.class.getName())){
                checks=new JSONArray();originals=children=0;selectedLoader=loader;
                check("same-launch-new-instrumentation-receives-logical-arguments",switched&&getClass()==LateInstrumentation.class&&name.equals(intent.getComponent().getClassName())&&intent.getStringExtra("tripoder.ticket")==null);
                check("instrumentation-receives-logical-class-and-component",name.equals(intent.getComponent().getClassName())&&"dev.tripoder.provideridentity".equals(intent.getComponent().getPackageName()));
                check("instrumentation-sees-no-runtime-routing-extras",intent.getStringExtra("tripoder.ticket")==null&&intent.getStringExtra("tripoder.delivery")==null);
                intent.putExtra("construction-marker","kept-from-instrumentation");
                String mode=intent.getStringExtra("mode");
                if("super".equals(mode))return super.newActivity(loader,name,intent);
                if("super-replace".equals(mode))super.newActivity(loader,name,intent);
                return new InstrumentationActivity.Replacement();
            }
            if(name.equals(InstrumentationActivity.Child.class.getName())){super.newActivity(loader,name,intent);return new InstrumentationActivity.ChildReplacement();}
            return super.newActivity(loader,name,intent);
        }
        @Override public void callActivityOnCreate(Activity activity,Bundle state){
            if(activity instanceof InstrumentationActivity||activity instanceof InstrumentationActivity.Child){
                check("instrumentation-pre-lifecycle-context-"+activity.getClass().getSimpleName(),activity.getPackageName().equals("dev.tripoder.provideridentity")&&activity.getApplication() instanceof InstrumentationApplication);
                int marker=activity.getResources().getIdentifier("creation_marker","string",activity.getPackageName());check("instrumentation-pre-lifecycle-resources-"+activity.getClass().getSimpleName(),marker!=0&&activity.getString(marker).equals("owned creation resources"));
                android.util.TypedValue value=new android.util.TypedValue();activity.getTheme().resolveAttribute(android.R.attr.windowActionBar,value,true);check("instrumentation-pre-lifecycle-theme-"+activity.getClass().getSimpleName(),value.data==0);
                activity.getTheme().resolveAttribute(android.R.attr.colorAccent,value,true);check("instrumentation-pre-lifecycle-private-theme-"+activity.getClass().getSimpleName(),value.data==0xff123456);
            }
            super.callActivityOnCreate(activity,state);
        }
    }
    public static final class LateInstrumentation extends ReplacementInstrumentation {
        @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{return super.newActivity(loader,name,intent);}
    }
}
