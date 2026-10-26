package dev.tripoder.runtime;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** The descriptor invoked by the pinned, interpreted framework launch body.
 * A covariant Java override is reached through its Activity-returning bridge. */
final class RuntimeInstrumentationMethods {
    private static final Class<?>[] PARAMETERS={ClassLoader.class,String.class,Intent.class};
    private RuntimeInstrumentationMethods(){}
    static Method construction(Class<? extends Instrumentation> type)throws NoSuchMethodException{
        for(Class<?> owner=type;owner!=null&&Instrumentation.class.isAssignableFrom(owner);owner=owner.getSuperclass()){
            Method selected=null;
            for(Method method:owner.getDeclaredMethods()){
                if(!method.getName().equals("newActivity"))continue;
                if(method.getReturnType()!=Activity.class||!Arrays.equals(method.getParameterTypes(),PARAMETERS))continue;
                int modifiers=method.getModifiers();
                if(!Modifier.isPublic(modifiers)||Modifier.isStatic(modifiers)||Modifier.isAbstract(modifiers))
                    throw new IllegalArgumentException("INSTRUMENTATION_CONSTRUCTION_DESCRIPTOR_INVALID");
                if(selected!=null)throw new IllegalArgumentException("INSTRUMENTATION_CONSTRUCTION_DESCRIPTOR_DUPLICATE");
                selected=method;
            }
            if(selected!=null)return selected;
        }
        throw new NoSuchMethodException("INSTRUMENTATION_CONSTRUCTION_DESCRIPTOR_REQUIRED");
    }
}
