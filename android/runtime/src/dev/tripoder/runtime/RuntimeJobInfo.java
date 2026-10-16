package dev.tripoder.runtime;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.os.*;
import java.util.*;

/** Lossless admission of the explicitly supported JobInfo shape on the pinned platform. */
final class RuntimeJobInfo {
    static final String VERSION="dev.tripoder.job.version";
    static final int MAX_BYTES=48*1024;
    static byte[] encode(JobInfo info){
        Parcel parcel=Parcel.obtain();try{info.writeToParcel(parcel,0);if(parcel.hasFileDescriptors()||parcel.dataSize()>MAX_BYTES)throw new IllegalArgumentException("JOB_PAYLOAD_UNSUPPORTED");return parcel.marshall();}finally{parcel.recycle();}
    }
    static JobInfo decode(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("JOB_PAYLOAD_UNSUPPORTED");
        Parcel parcel=Parcel.obtain();try{parcel.unmarshall(bytes,0,bytes.length);parcel.setDataPosition(0);JobInfo info=JobInfo.CREATOR.createFromParcel(parcel);if(parcel.dataAvail()!=0)throw new IllegalArgumentException("JOB_TRAILING_PAYLOAD");validate(info);return info;}finally{parcel.recycle();}
    }
    static JobInfo physical(JobInfo input,boolean early,boolean late,ComponentName component,String version){
        validate(input);JobInfo rebuilt=copy(input,early,late,input.getService(),input.getExtras());
        // Compare complete platform encoding, including fields without public getters.
        // Both bundles are inspected below, giving the same parcel representation.
        if(!Arrays.equals(encode(input),encode(rebuilt)))throw new UnsupportedOperationException("JOB_FIELDS_NOT_LOSSLESS");
        if(version==null||!version.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("JOB_VERSION_INVALID");
        PersistableBundle route=new PersistableBundle();route.putString(VERSION,version);
        return copy(input,early,late,component,route);
    }
    private static JobInfo copy(JobInfo input,boolean early,boolean late,ComponentName service,PersistableBundle extras){
        JobInfo.Builder out=new JobInfo.Builder(input.getId(),service)
            .setExtras(extras).setTransientExtras(input.getTransientExtras())
            .setRequiresCharging(input.isRequireCharging()).setRequiresBatteryNotLow(input.isRequireBatteryNotLow())
            .setRequiresStorageNotLow(input.isRequireStorageNotLow()).setRequiresDeviceIdle(input.isRequireDeviceIdle())
            .setPriority(input.getPriority()).setRequiredNetwork(input.getRequiredNetwork())
            .setEstimatedNetworkBytes(input.getEstimatedNetworkDownloadBytes(),input.getEstimatedNetworkUploadBytes())
            .setMinimumNetworkChunkBytes(input.getMinimumNetworkChunkBytes());
        if(input.isPeriodic())out.setPeriodic(input.getIntervalMillis(),input.getFlexMillis());
        else{if(early)out.setMinimumLatency(input.getMinLatencyMillis());if(late)out.setOverrideDeadline(input.getMaxExecutionDelayMillis());}
        // Android prohibits explicitly selecting backoff together with idle constraints.
        if(!input.isRequireDeviceIdle())out.setBackoffCriteria(input.getInitialBackoffMillis(),input.getBackoffPolicy());
        for(String tag:input.getDebugTags())out.addDebugTag(tag);
        if(input.getTraceTag()!=null)out.setTraceTag(input.getTraceTag());
        return out.build();
    }
    private static void validate(JobInfo input){
        Objects.requireNonNull(input,"JOB_REQUIRED");
        if(input.getService()==null||input.isPersisted()||input.isExpedited()||input.isUserInitiated()||input.isPrefetch()||input.isImportantWhileForeground()||input.getClipData()!=null||input.getClipGrantFlags()!=0||input.getTriggerContentUris()!=null||input.getTriggerContentUpdateDelay()!=-1||input.getTriggerContentMaxDelay()!=-1)throw new UnsupportedOperationException("JOB_KIND_UNSUPPORTED");
        int[] count={0};bundle(input.getExtras(),0,count);bundle(input.getTransientExtras(),0,count);encode(input);
    }
    private static void bundle(BaseBundle bundle,int depth,int[] count){
        if(bundle==null||depth>8)throw new IllegalArgumentException("JOB_EXTRAS_DEPTH");
        for(String key:bundle.keySet()){
            if(++count[0]>256||key.length()>256)throw new IllegalArgumentException("JOB_EXTRAS_LIMIT");Object value=bundle.get(key);
            if(value==null||value instanceof Boolean||value instanceof Integer||value instanceof Long||value instanceof Double)continue;
            if(value instanceof String){if(((String)value).length()>4096)throw new IllegalArgumentException("JOB_EXTRA_STRING_LIMIT");continue;}
            if(value instanceof PersistableBundle){bundle((PersistableBundle)value,depth+1,count);continue;}
            if(value instanceof Bundle){bundle((Bundle)value,depth+1,count);continue;}
            if(value instanceof boolean[]||value instanceof int[]||value instanceof long[]||value instanceof double[]){if(java.lang.reflect.Array.getLength(value)>256)throw new IllegalArgumentException("JOB_EXTRA_ARRAY_LIMIT");continue;}
            if(value instanceof String[]){String[] strings=(String[])value;if(strings.length>256)throw new IllegalArgumentException("JOB_EXTRA_ARRAY_LIMIT");for(String string:strings)if(string!=null&&string.length()>4096)throw new IllegalArgumentException("JOB_EXTRA_STRING_LIMIT");continue;}
            throw new IllegalArgumentException("JOB_EXTRA_TYPE_UNSUPPORTED:"+value.getClass().getName());
        }
    }
    private RuntimeJobInfo(){}
}
