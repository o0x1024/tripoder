package dev.tripoder.selfhook;

import android.app.Application;
import android.app.ActivityThread;
import android.app.LoadedApk;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/** Own boot-class fixtures only. No Android device or platform library is accessed. */
public final class PhysicalApplicationHostTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static Field field(Class<?> owner,String name)throws Exception{return owner.getDeclaredField(name);}
    private static FrameworkAccess.Members members()throws Exception {
        Class<?>[] owners={Application.class,ActivityThread.class,LoadedApk.class,Object.class,Object.class,Object.class,Object.class};
        Field[] fields=new Field[12];fields[0]=field(ActivityThread.class,"mInitialApplication");
        fields[1]=field(Application.class,"mLoadedApk");fields[2]=field(LoadedApk.class,"mApplication");
        fields[11]=field(ActivityThread.class,"sCurrentActivityThread");return new FrameworkAccess.Members(owners,fields);
    }
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action,Class<? extends Throwable> kind)throws Exception {
        try{action.run();throw new AssertionError("accepted");}catch(Throwable error){if(!kind.isInstance(error))throw new AssertionError(error);checks++;}
    }
    public static void main(String[] args)throws Exception {
        String scenario=args[1];FrameworkAccess.Members registry=members();Application host=new Application();
        if(scenario.startsWith("metadata-")) {
            switch(scenario) {
                case "metadata-old":registry=new FrameworkAccess.Members(registry.owners,new Field[11]);break;
                case "metadata-null":registry.fields[0]=null;break;
                case "metadata-owner":registry.fields[0]=field(LoadedApk.class,"mApplication");break;
                case "metadata-name":registry.fields[0]=field(ActivityThread.class,"otherInitial");break;
                case "metadata-class":registry.owners[1]=String.class;break;
                case "metadata-loader":
                    byte[] bytes=ActivityThread.class.getResourceAsStream("ActivityThread.class").readAllBytes();
                    ClassLoader loader=new ClassLoader(null){Class<?> define(){return defineClass("android.app.ActivityThread",bytes,0,bytes.length);}};
                    var define=loader.getClass().getDeclaredMethod("define");define.setAccessible(true);registry.owners[1]=(Class<?>)define.invoke(loader);break;
                case "metadata-type":case "metadata-static":case "metadata-current-static":break;
                default:throw new AssertionError(scenario);
            }
            FrameworkAccess.Members rejected=registry;
            // Deliberately do not load Native: metadata rejection must precede JNI linkage.
            rejects(()->PhysicalApplication.prepare(host,rejected),IllegalArgumentException.class);
            rejects(()->PhysicalApplication.prepare(host,members()),IllegalStateException.class);
            rejects(PhysicalApplication::check,IllegalStateException.class);
            System.out.println(scenario+" checks="+checks);return;
        }
        System.load(args[0]);
        Object thread=new ActivityThread(),apk=new LoadedApk();
        registry.fields[11].set(null,thread);registry.fields[0].set(thread,host);
        registry.fields[1].set(host,apk);registry.fields[2].set(apk,host);
        PhysicalApplication.prepare(host,registry);PhysicalApplication.check();PhysicalApplication.check(host);
        check(PhysicalApplication.reportNative().contains("\"retainedGlobalRefs\":4"));
        if(scenario.equals("normal")) {
            AtomicReference<Throwable> failure=new AtomicReference<>();Thread[] threads=new Thread[4];
            for(int i=0;i<4;i++){threads[i]=new Thread(()->{try{for(int n=0;n<100;n++)PhysicalApplication.check();}catch(Throwable error){failure.set(error);}});threads[i].start();}
            for(Thread worker:threads)worker.join();check(failure.get()==null);
            for(int i=0;i<8;i++){System.gc();PhysicalApplication.check();}
            check(PhysicalApplication.reportNative().contains("\"checks\":411"));
            rejects(()->PhysicalApplication.check(new Application()),SecurityException.class);PhysicalApplication.check();
            rejects(()->PhysicalApplication.prepare(host,members()),IllegalStateException.class);
            check(PhysicalApplication.reportNative().contains("\"ready\":true"));
        } else if(scenario.equals("roots")) {
            WeakReference<Object> weakThread=new WeakReference<>(thread),weakApk=new WeakReference<>(apk);
            registry.fields[11].set(null,null);registry.fields[1].set(host,null);thread=null;apk=null;
            for(int i=0;i<8;i++)System.gc();check(weakThread.get()!=null&&weakApk.get()!=null);
            rejects(PhysicalApplication::check,IllegalStateException.class);
            check(PhysicalApplication.reportNative().contains("\"retainedGlobalRefs\":4"));
        } else {
            switch(scenario) {
                case "thread":registry.fields[11].set(null,new ActivityThread());break;
                case "initial":registry.fields[0].set(thread,new Application());break;
                case "apk":registry.fields[1].set(host,new LoadedApk());break;
                case "owner":registry.fields[2].set(apk,new Application());break;
                default:throw new AssertionError(scenario);
            }
            rejects(PhysicalApplication::check,IllegalStateException.class);
            check(PhysicalApplication.reportNative().contains("\"ready\":false"));
            registry.fields[11].set(null,thread);registry.fields[0].set(thread,host);
            registry.fields[1].set(host,apk);registry.fields[2].set(apk,host);
            rejects(PhysicalApplication::check,IllegalStateException.class);
        }
        System.out.println(scenario+" checks="+checks);
    }
}
