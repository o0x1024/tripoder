package dev.tripoder.isolatedfdprobe;

import android.app.AppComponentFactory;
import android.app.Service;
import android.content.Intent;

/** Measures the platform's actual creation input, independently of bind delivery. */
public final class ProbeFactory extends AppComponentFactory {
    static volatile boolean serviceCreated,intentPresent,challengePresent,binderPresent;
    @Override public Service instantiateService(ClassLoader loader,String name,Intent intent)
            throws InstantiationException,IllegalAccessException,ClassNotFoundException {
        if(name.equals(IsolatedService.class.getName())){
            serviceCreated=true;intentPresent=intent!=null;
            challengePresent=intent!=null&&intent.hasExtra("ownedChallenge");
            binderPresent=intent!=null&&intent.getExtras()!=null&&intent.getExtras().getBinder("ownedBinder")!=null;
        }
        return super.instantiateService(loader,name,intent);
    }
}
