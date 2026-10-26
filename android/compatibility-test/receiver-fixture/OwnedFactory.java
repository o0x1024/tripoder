package dev.tripoder.closurefixture;

import android.app.AppComponentFactory;
import android.app.Application;
import android.content.pm.ApplicationInfo;

public final class OwnedFactory extends AppComponentFactory {
    static ClassLoader selected;static OwnedFactory instance;
    static{BindingChecks.phase("factory-clinit",null,null,null);}
    public OwnedFactory(){BindingChecks.phase("factory-constructor",null,null,null);instance=this;}
    @Override public ClassLoader instantiateClassLoader(ClassLoader loader,ApplicationInfo info){
        BindingChecks.phase("factory-loader",null,loader,this);BindingChecks.classLoaderArgument(info);selected=new ClassLoader(loader){};return selected;
    }
    @Override public Application instantiateApplication(ClassLoader loader,String name)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        BindingChecks.phase("factory-application",null,loader,this);return super.instantiateApplication(loader,name);
    }
}
