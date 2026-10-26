package dev.tripoder.isolatedservicecontract;
public final class OwnedApplication extends android.app.Application {
    static int creates;
    @Override public void onCreate(){super.onCreate();creates++;}
}
