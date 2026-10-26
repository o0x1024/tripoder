package dev.tripoder.runtime;

import android.app.*;
import android.content.Context;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Android persists the exact generation stamp with the real exit record.
 * No PID reuse guesses, synthetic exit reasons, or wall-clock attribution. */
final class RuntimeExitHistory {
    private static final byte[] MAGIC={'T','P','X','1'};
    static byte[] marker(String generation,String dataId)throws Exception{
        if(generation==null||!generation.matches("[a-f0-9]{64}"))throw new SecurityException("EXIT_GENERATION_INVALID");
        byte[] value=new byte[68];System.arraycopy(MAGIC,0,value,0,4);System.arraycopy(HexFormat.of().parseHex(generation),0,value,4,32);System.arraycopy(dataHash(dataId),0,value,36,32);return value;
    }
    static void stamp(Context host,String generation,String dataId)throws Exception{
        Objects.requireNonNull(host.getSystemService(ActivityManager.class),"EXIT_PLATFORM_MANAGER_REQUIRED").setProcessStateSummary(marker(generation,dataId));
    }
    private static byte[] dataHash(String dataId)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(dataId).getBytes(StandardCharsets.UTF_8));}
    static Bundle query(RuntimeBroker broker,Bundle input,int pid,int uid)throws Exception{
        if(Thread.holdsLock(broker))throw new IllegalStateException("EXIT_SYSTEM_QUERY_INSIDE_BROKER_LOCK");
        if(input==null||!input.keySet().equals(Set.of("generation","package","pid","maxNum"))||!(input.get("pid") instanceof Integer)||!(input.get("maxNum") instanceof Integer))throw new IllegalArgumentException("EXIT_HISTORY_QUERY_SHAPE");
        int requestedPid=input.getInt("pid"),max=input.getInt("maxNum");if(requestedPid<0||max<0)throw new IllegalArgumentException("EXIT_HISTORY_QUERY_RANGE");
        ApkStore.BundleInstall install;
        synchronized(broker){String generation=RuntimeWire.required(input,"generation");broker.contract.authenticate(generation,pid,uid);install=broker.requiredSlot(generation).install;
            String pkg=input.getString("package");if(pkg!=null&&!pkg.equals(install.info.packageName))throw new SecurityException("EXIT_HISTORY_OTHER_PACKAGE_DENIED");}
        List<ApplicationExitInfo> observed=Objects.requireNonNull(broker.host.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(broker.host.getPackageName(),requestedPid,0),"EXIT_SYSTEM_HISTORY_UNAVAILABLE");
        if(observed.size()>256)throw new IllegalStateException("EXIT_SYSTEM_HISTORY_BUDGET");
        byte[] data=dataHash(install.data.getName());ArrayList<Bundle> rows=new ArrayList<>();
        synchronized(broker){
            var current=broker.apks.row(install.space,install.info.packageName);if(current==null||!"COMMITTED".equals(current.state)||!install.generation.equals(current.generation)||!install.data.getName().equals(current.dataId))throw new SecurityException("EXIT_HISTORY_INSTALL_CHANGED");
            for(ApplicationExitInfo physical:observed){
                byte[] summary=physical.getProcessStateSummary();
                if(summary==null||summary.length!=68||!Arrays.equals(MAGIC,Arrays.copyOfRange(summary,0,4))||!MessageDigest.isEqual(data,Arrays.copyOfRange(summary,36,68)))continue;
                String generation=HexFormat.of().formatHex(summary,4,36);
                try(var session=broker.store.getReadableDatabase().rawQuery("SELECT space,package,installGeneration,pid,state,processName,activityStub,processKind FROM sessions WHERE generation=?",new String[]{generation})){
                    if(!session.moveToFirst()||!install.space.equals(session.getString(0))||!install.info.packageName.equals(session.getString(1))||!install.generation.equals(session.getString(2))||physical.getPid()!=session.getInt(3)||!"DEAD".equals(session.getString(4)))continue;
                    if(!"ORDINARY".equals(session.getString(7)))throw new UnsupportedOperationException("ISOLATED_EXIT_STAMP_NOT_IMPLEMENTED");
                    String expected=broker.host.getPackageName()+":slot"+RuntimeSlot.index(session.getString(6));
                    if(physical.getRealUid()!=uid||physical.getPackageUid()!=uid||!expected.equals(physical.getProcessName()))throw new SecurityException("EXIT_SYSTEM_IDENTITY_MISMATCH");
                    Bundle row=new Bundle();row.putParcelable("info",physical);row.putString("processName",session.getString(5));rows.add(row);
                }
                if(max>0&&rows.size()==max)break;
            }
        }
        Bundle out=new Bundle();out.putParcelableArrayList("exits",rows);return out;
    }
    private RuntimeExitHistory(){}
}
