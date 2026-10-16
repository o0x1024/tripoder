package dev.tripoder.p2;

import android.app.Application;
import java.io.*;
import java.nio.file.Files;

/** Self-owned lifecycle fixture. Never inspects another installed application's data. */
public final class App extends Application {
    private Object ownedPackage;
    @Override protected void attachBaseContext(android.content.Context context){super.attachBaseContext(context);try{ownedPackage=ApplicationOwnership.attach(this,context);}catch(Exception error){throw new IllegalStateException("FIXTURE_APPLICATION_ATTACH_FAILED",error);}}
    public int starts;
    public Services.Link startup;
    @Override public void onCreate(){super.onCreate();try{
        ApplicationOwnership.check(this,ownedPackage);
        boolean permissionDenied=false;try{getSystemService(android.net.ConnectivityManager.class).getActiveNetwork();}catch(SecurityException expected){permissionDenied=expected.getMessage().contains("GUEST_PERMISSION_NOT_DECLARED");}
        if(!permissionDenied)throw new IllegalStateException("UNDECLARED_NETWORK_PERMISSION_NOT_REJECTED");
        if(Factory.applications!=1||Factory.loaders!=1)throw new IllegalStateException("DECLARED_FACTORY_NOT_USED");
        Providers.application(this);
        File file=new File(getFilesDir(),"starts-"+getProcessName().replace(':','_'));
        starts=file.exists()?Integer.parseInt(Files.readString(file.toPath()))+1:1;
        try(var out=new FileOutputStream(file)){out.write(Integer.toString(starts).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        if(getProcessName().equals("dev.tripoder.p2:serviceapp")){startup=new Services.Link();if(!bindService(Services.intent(this,Services.Alpha.class),startup,BIND_AUTO_CREATE))throw new IllegalStateException("STARTUP_BIND_REJECTED");}
    }catch(Exception error){throw new IllegalStateException("FIXTURE_APPLICATION_FAILED",error);}}
}
