package dev.tripoder.fixture;
import android.app.Application;
import java.nio.file.Files;
import java.io.File;
import java.nio.charset.StandardCharsets;

public final class FixtureApplication extends Application {
    public static int created;
    public static volatile Application instance;
    private final String instanceToken = "live-app-token-42";
    public String instanceSign(String payload) {
        return instanceToken + ":" + payload + ":created=" + created;
    }
    @Override public void onCreate() {
        super.onCreate(); created++; instance = this;
        try { Files.write(new File(getFilesDir(), "application-created.txt").toPath(), getString(R.string.guest_title).getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
