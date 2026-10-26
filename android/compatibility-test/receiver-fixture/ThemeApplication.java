package dev.tripoder.closurefixture;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

public final class ThemeApplication extends Application {
    static{BindingChecks.phase("application-clinit",null,null,null);}
    public ThemeApplication(){BindingChecks.phase("application-constructor",null,null,null);}
    static boolean themeReady,deferredBeforeOnCreate;
    @Override public void onCreate(){BindingChecks.phase("application-onCreate",this,null,null);deferredBeforeOnCreate=DeferredProvider.constructors.get()==0&&DeferredProvider.attachments.get()==0&&DeferredProvider.creates.get()==0;super.onCreate();}
    @Override protected void attachBaseContext(Context base){
        BindingChecks.phase("application-attach",null,null,null);
        super.attachBaseContext(base);
        BindingChecks.deferOwnedProvider();
        Configuration override=new Configuration(base.getResources().getConfiguration());override.fontScale=1.05f;
        Context derived=base.createConfigurationContext(override);
        themeReady=base.getTheme()!=null&&derived.getTheme()!=null&&base.getTheme().getResources().getAssets()==base.getAssets()&&derived.getTheme().getResources().getAssets()==derived.getAssets();
    }
}
