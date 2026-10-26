package dev.tripoder.selfhookprobe;
import android.app.Application;
import android.content.Context;
public final class EarlyApplicationFixture extends Application {
    static{EarlyIdentityAssertions.phase("application-clinit",null);}
    public EarlyApplicationFixture(){EarlyIdentityAssertions.phase("application-constructor",null);}
    @Override protected void attachBaseContext(Context base){EarlyIdentityAssertions.phase("application-attach",null);super.attachBaseContext(base);}
    @Override public void onCreate(){EarlyIdentityAssertions.phase("application-onCreate",this);super.onCreate();}
}
