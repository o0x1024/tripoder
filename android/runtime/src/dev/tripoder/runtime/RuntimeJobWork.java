package dev.tripoder.runtime;

import android.app.job.JobWorkItem;
import android.content.Intent;
import android.os.Parcel;

/** Lossless real work-item payloads; no Binder/FD or physical URI-grant authority. */
final class RuntimeJobWork {
    static final int MAX_BYTES=32*1024;
    private static void validate(JobWorkItem item){
        java.util.Objects.requireNonNull(item,"JOB_WORK_REQUIRED");
        Intent intent=item.getIntent();
        if(intent!=null){
            int grants=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            if(intent.getClipData()!=null||(intent.getFlags()&grants)!=0)throw new UnsupportedOperationException("JOB_WORK_URI_GRANTS_UNSUPPORTED");
            RuntimeProviderFacade.primitive(intent.getExtras());
        }
        RuntimeJobInfo.workExtras(item.getExtras());
    }
    static byte[] encode(JobWorkItem item){
        validate(item);Parcel parcel=Parcel.obtain();try{item.writeToParcel(parcel,0);if(parcel.hasFileDescriptors()||parcel.dataSize()>MAX_BYTES)throw new IllegalArgumentException("JOB_WORK_PAYLOAD_UNSUPPORTED");return parcel.marshall();}finally{parcel.recycle();}
    }
    static JobWorkItem decode(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("JOB_WORK_PAYLOAD_UNSUPPORTED");
        Parcel parcel=Parcel.obtain();try{parcel.unmarshall(bytes,0,bytes.length);parcel.setDataPosition(0);JobWorkItem item=JobWorkItem.CREATOR.createFromParcel(parcel);if(parcel.dataAvail()!=0)throw new IllegalArgumentException("JOB_WORK_TRAILING_PAYLOAD");validate(item);return item;}finally{parcel.recycle();}
    }
    private RuntimeJobWork(){}
}
