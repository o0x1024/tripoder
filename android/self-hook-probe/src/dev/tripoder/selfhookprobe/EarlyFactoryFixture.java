package dev.tripoder.selfhookprobe;
import android.app.Application;
import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
public final class EarlyFactoryFixture extends AppComponentFactory {
    static{EarlyIdentityAssertions.phase("factory-clinit",null);}
    public EarlyFactoryFixture(){EarlyIdentityAssertions.phase("factory-constructor",null);}
    @Override public ClassLoader instantiateClassLoader(ClassLoader loader,ApplicationInfo info){EarlyIdentityAssertions.phase("factory-loader",null);return super.instantiateClassLoader(loader,info);}
    @Override public Application instantiateApplication(ClassLoader loader,String name)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        EarlyIdentityAssertions.phase("factory-application",null);return super.instantiateApplication(loader,name);
    }
    public static int phases(){return EarlyIdentityAssertions.phases;}
}
