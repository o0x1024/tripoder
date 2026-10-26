package dev.tripoder.factorycontract;

import android.app.Application;
import java.util.concurrent.atomic.AtomicInteger;

public final class OwnedApplication extends Application {
    static final AtomicInteger creates=new AtomicInteger();
    @Override public void onCreate(){super.onCreate();creates.incrementAndGet();DeliveryObservation.install(this);}
}
