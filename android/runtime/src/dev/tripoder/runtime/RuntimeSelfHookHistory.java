package dev.tripoder.runtime;

import android.app.ApplicationStartInfo;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Only fields explicitly present in the logical ledger. No physical startup Intent or host history. */
final class RuntimeSelfHookHistory {
    static final class Row {
        final long createdMs,startedNs;final Long onCreateNs;
        final int pid,uid,startupState,startType,reason,component,launchMode;
        final String packageName,processName;
        Row(Bundle source) {
            Objects.requireNonNull(source,"APPLICATION_HISTORY_ROW_REQUIRED");
            createdMs=source.getLong("createdMs");startedNs=source.getLong("startedNs");
            onCreateNs=source.containsKey("onCreateNs")?Long.valueOf(source.getLong("onCreateNs")):null;
            pid=source.getInt("pid");uid=source.getInt("uid");startupState=source.getInt("startupState");startType=source.getInt("startType");
            reason=source.getInt("reason");component=source.getInt("component");launchMode=source.getInt("launchMode");
            packageName=source.getString("package");processName=source.getString("processName");
        }
    }
    static List<ApplicationStartInfo> query(int max,RuntimeSelfHookManagerFramework framework)throws Exception {
        ArrayList<Bundle> rows=RuntimeBridge.historicalStarts(max);List<ApplicationStartInfo> result=new ArrayList<>(rows.size());
        for(Bundle row:rows)result.add(Objects.requireNonNull(framework.applicationStartInfo(new Row(row)),"APPLICATION_HISTORY_PLATFORM_VALUE_REQUIRED"));
        return result;
    }
    private RuntimeSelfHookHistory(){}
}
