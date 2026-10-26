package dev.tripoder.isolatedservicecontract;

import android.app.*;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

/** The measured platform creation phase receives no binding Intent. */
public final class OwnedFactory extends AppComponentFactory {
    static int loaders,applications,services;static boolean serviceIntentNull;
    @Override public ClassLoader instantiateClassLoader(ClassLoader loader,ApplicationInfo info){loaders++;return loader;}
    @Override public Application instantiateApplication(ClassLoader loader,String name)throws InstantiationException,IllegalAccessException,ClassNotFoundException{applications++;return super.instantiateApplication(loader,name);}
    @Override public Service instantiateService(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{services++;serviceIntentNull=intent==null;if(!serviceIntentNull)throw new SecurityException("OWNED_SERVICE_FACTORY_CREATION_INTENT_MUST_BE_NULL");return super.instantiateService(loader,name,intent);}
}
