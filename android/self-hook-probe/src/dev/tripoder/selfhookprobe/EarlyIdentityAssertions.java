package dev.tripoder.selfhookprobe;
import android.app.Application;
import java.lang.reflect.Field;
final class EarlyIdentityAssertions {
    static int phases;
    static void phase(String name,Application expected){
        try{
            Class<?> type=Class.forName("android.app.ActivityThread",false,null);Object thread=type.getDeclaredMethod("currentActivityThread").invoke(null);
            Field initial=type.getDeclaredField("mInitialApplication");initial.setAccessible(true);
            if(initial.get(thread)!=expected||type.getDeclaredMethod("currentApplication").invoke(null)!=expected
                ||!Application.getProcessName().equals("owned.logical.process"))throw new IllegalStateException("EARLY_IDENTITY:"+name);
            phases++;
        }catch(ReflectiveOperationException error){throw new IllegalStateException(name,error);}
    }
}
