package dev.tripoder.provideridentity;
public final class DisabledMetadataService extends android.app.Service {
    @Override public android.os.IBinder onBind(android.content.Intent intent){throw new AssertionError("DISABLED_SERVICE_MUST_NOT_EXECUTE");}
}
