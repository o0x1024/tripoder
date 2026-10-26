package dev.tripoder.antidetect;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class AntiDetectActivity extends Activity {
    public static int creates, resumes;
    private TextView result;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        creates++;
        setContentView(R.layout.antidetect_screen);
        result = findViewById(R.id.result);
        findViewById(R.id.action).setOnClickListener(view -> runChecks());
        runChecks();
    }

    private void runChecks() {
        List<AntiDetectChecks.CheckResult> checks = AntiDetectChecks.runAll(this);
        StringBuilder text = new StringBuilder();
        int suspicious = 0;
        for (AntiDetectChecks.CheckResult check : checks) {
            text.append(check).append('\n');
            if (check.suspicious) suspicious++;
        }
        text.append("\n").append(suspicious).append('/').append(checks.size()).append(" checks flagged suspicious");
        result.setText(text.toString());
        try {
            File out = new File(getFilesDir(), "anti-detect-result.txt");
            Files.write(out.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    @Override public void onResume() { super.onResume(); resumes++; }
}
