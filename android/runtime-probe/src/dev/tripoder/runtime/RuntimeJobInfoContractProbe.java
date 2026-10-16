package dev.tripoder.runtime;

import android.app.job.JobInfo;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import org.json.*;

/** Owned platform-object tests. Does not submit, cancel or run a system job. */
public final class RuntimeJobInfoContractProbe {
    private static final ComponentName LOGICAL=new ComponentName("dev.tripoder.owned","dev.tripoder.owned.Job");
    private static final ComponentName PHYSICAL=new ComponentName("dev.tripoder.runtimeprobe","dev.tripoder.runtime.RuntimeJobSlots$J0_0");
    private static final String VERSION="0123456789abcdef".repeat(4);
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private static JobInfo.Builder builder(int id){return new JobInfo.Builder(id,LOGICAL);}
    private static JobInfo route(JobInfo input,boolean early,boolean late,JSONArray checks,String label)throws Exception{
        JobInfo snapshot=RuntimeJobInfo.decode(RuntimeJobInfo.encode(input));byte[] before=RuntimeJobInfo.encode(snapshot);
        JobInfo output=RuntimeJobInfo.physical(snapshot,early,late,PHYSICAL,VERSION);
        check(checks,"job-info-"+label+"-source-unchanged",Arrays.equals(before,RuntimeJobInfo.encode(snapshot)));
        check(checks,"job-info-"+label+"-routing-isolated",output.getService().equals(PHYSICAL)&&output.getId()==input.getId()&&output.getExtras().size()==1&&VERSION.equals(output.getExtras().getString(RuntimeJobInfo.VERSION)));
        return output;
    }
    private interface Action{void run();}
    private static boolean rejected(Action action,String code){try{action.run();return false;}catch(IllegalArgumentException|UnsupportedOperationException error){return error.getMessage().contains(code);}}
    public static JSONObject run()throws Exception{
        JSONArray checks=new JSONArray();JobInfo zero=builder(1).setMinimumLatency(0).build();route(zero,true,false,checks,"explicit-zero-latency");
        check(checks,"job-info-wrong-zero-latency-presence-rejected",rejected(()->RuntimeJobInfo.physical(zero,false,false,PHYSICAL,VERSION),"JOB_FIELDS_NOT_LOSSLESS"));
        route(builder(2).setOverrideDeadline(0).build(),false,true,checks,"explicit-zero-deadline");
        PersistableBundle extras=new PersistableBundle(),nested=new PersistableBundle();nested.putLong("large",9007199254740993L);extras.putPersistableBundle("nested",nested);extras.putStringArray("strings",new String[]{"first",null,"third"});extras.putIntArray("ints",new int[]{1,2,3});extras.putDoubleArray("doubles",new double[]{1.25,-0.0});
        Bundle transientExtras=new Bundle();transientExtras.putBoolean("ephemeral",true);transientExtras.putString("optional",null);
        JobInfo input=builder(3).setMinimumLatency(1000).setOverrideDeadline(2000).setRequiresCharging(true).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPriority(JobInfo.PRIORITY_HIGH).setEstimatedNetworkBytes(2048,8192).setMinimumNetworkChunkBytes(128).setBackoffCriteria(10000,JobInfo.BACKOFF_POLICY_LINEAR).setExtras(extras).setTransientExtras(transientExtras).addDebugTag("owned").setTraceTag("owned-job").build();
        JobInfo output=route(input,true,true,checks,"constraints-and-typed-extras");
        check(checks,"job-info-real-constraint-fields-preserved",output.isRequireCharging()&&output.isRequireBatteryNotLow()&&output.isRequireStorageNotLow()&&output.getMinLatencyMillis()==1000&&output.getMaxExecutionDelayMillis()==2000&&output.getRequiredNetwork().equals(input.getRequiredNetwork())&&output.getPriority()==JobInfo.PRIORITY_HIGH&&output.getEstimatedNetworkDownloadBytes()==2048&&output.getEstimatedNetworkUploadBytes()==8192&&output.getMinimumNetworkChunkBytes()==128&&output.getInitialBackoffMillis()==10000&&output.getBackoffPolicy()==JobInfo.BACKOFF_POLICY_LINEAR&&output.getDebugTags().contains("owned")&&"owned-job".equals(output.getTraceTag())&&output.getTransientExtras().getBoolean("ephemeral")&&output.getTransientExtras().containsKey("optional"));
        JobInfo periodic=builder(4).setPeriodic(JobInfo.getMinPeriodMillis(),JobInfo.getMinFlexMillis()).setRequiresDeviceIdle(true).build();JobInfo mapped=route(periodic,true,true,checks,"periodic-idle");
        check(checks,"job-info-period-and-flex-preserved",mapped.isPeriodic()&&mapped.isRequireDeviceIdle()&&mapped.getIntervalMillis()==periodic.getIntervalMillis()&&mapped.getFlexMillis()==periodic.getFlexMillis());
        for(JobInfo unsupported:List.of(builder(5).setMinimumLatency(1).setPersisted(true).build(),builder(6).setExpedited(true).build(),builder(7).addTriggerContentUri(new JobInfo.TriggerContentUri(Uri.parse("content://dev.tripoder.owned/records"),0)).build()))check(checks,"job-info-unsupported-kind-"+unsupported.getId(),rejected(()->RuntimeJobInfo.physical(unsupported,false,false,PHYSICAL,VERSION),"JOB_KIND_UNSUPPORTED"));
        Bundle arbitrary=new Bundle();arbitrary.putParcelable("intent",new Intent("owned"));JobInfo custom=builder(8).setMinimumLatency(1).setTransientExtras(arbitrary).build();check(checks,"job-info-custom-transient-parcelable-rejected",rejected(()->RuntimeJobInfo.physical(custom,true,false,PHYSICAL,VERSION),"JOB_EXTRA_TYPE_UNSUPPORTED"));
        return new JSONObject().put("passed",true).put("checks",checks).put("systemJobsSubmitted",0);
    }
    private RuntimeJobInfoContractProbe(){}
}
