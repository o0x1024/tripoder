package dev.tripoder.p2;

import android.app.*;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

/** Exercises public Android factory semantics without linking the runtime. */
public final class Factory extends AppComponentFactory {
    public static int loaders,applications,activities,services,providers,receivers;
    @Override public ClassLoader instantiateClassLoader(ClassLoader original,ApplicationInfo info){if(!info.packageName.equals("dev.tripoder.p2"))throw new IllegalStateException("FACTORY_WRONG_PACKAGE");loaders++;return new ClassLoader(original){};}
    @Override public Application instantiateApplication(ClassLoader loader,String name)throws ClassNotFoundException,IllegalAccessException,InstantiationException{if(loaders!=1||applications++!=0)throw new IllegalStateException("FACTORY_APPLICATION_ORDER");return super.instantiateApplication(loader,name);}
    @Override public Activity instantiateActivity(ClassLoader loader,String name,Intent intent)throws ClassNotFoundException,IllegalAccessException,InstantiationException{check(intent);activities++;return name.equals(Chain.Leaf.class.getName())?new ReplacedLeaf():super.instantiateActivity(loader,name,intent);}
    @Override public Service instantiateService(ClassLoader loader,String name,Intent intent)throws ClassNotFoundException,IllegalAccessException,InstantiationException{if(intent!=null)check(intent);services++;return super.instantiateService(loader,name,intent);}
    @Override public android.content.ContentProvider instantiateProvider(ClassLoader loader,String name)throws ClassNotFoundException,IllegalAccessException,InstantiationException{providers++;return super.instantiateProvider(loader,name);}
    @Override public android.content.BroadcastReceiver instantiateReceiver(ClassLoader loader,String name,Intent intent)throws ClassNotFoundException,IllegalAccessException,InstantiationException{check(intent);receivers++;return super.instantiateReceiver(loader,name,intent);}
    private static void check(Intent intent){if(intent==null||intent.getComponent()==null||!intent.getComponent().getPackageName().equals("dev.tripoder.p2"))throw new IllegalStateException("FACTORY_PHYSICAL_INTENT_LEAK");if(intent.getExtras()!=null)for(String key:intent.getExtras().keySet())if(key.startsWith("tripoder."))throw new IllegalStateException("FACTORY_PRIVATE_EXTRA_LEAK");}
    public static final class ReplacedLeaf extends Chain {protected String next(){return null;}}
}
