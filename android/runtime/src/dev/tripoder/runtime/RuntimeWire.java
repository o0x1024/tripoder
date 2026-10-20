package dev.tripoder.runtime;

import android.os.*;

/** Versioned bounded protocol. No caller-selected Parcelable classes or Binder forwarding. */
public final class RuntimeWire {
    private RuntimeWire() {}
    public static final String DESCRIPTOR="dev.tripoder.runtime.v1";
    public static final int LOAD=1, REGISTER=2, ROUTE=3, RESULT=4, EVENT=5, STOP=6, CHECK=7, RECEIVED=8, CONTROL=9;
    public static final int ACTIVITY_CLAIM=10,ACTIVITY_CREATED=11,ACTIVITY_DESTROYED=12;
    public static final int START_APPLICATION=13,SERVICE_ROUTE=14,SERVICE_ACK=15,SERVICE_CLAIM=16,SERVICE_EVENT=17;
    public static final int PROVIDER_ACQUIRE=18,PROVIDER_PUBLICATION=19,PROVIDER_RELEASE=20,PROVIDER_BEGIN=21,PROVIDER_DISPATCH=22,PROVIDER_FINISH=23,PROVIDER_WAIT=24;
    public static final int BROADCAST_REGISTER=25,BROADCAST_REGISTRATION=26,BROADCAST_ROUTE=27,BROADCAST_CLAIM=28,BROADCAST_EVENT=29;
    public static final int PROVIDER_CURSOR_ACCESS=30,PROVIDER_RECEIVED=31;
    public static final int ACTIVITY_NEW_INTENT=32,ACTIVITY_NEW_INTENT_ACK=33,ACTIVITY_ATTACHED=34,ACTIVITY_RESULT_ENTER=35;
    public static final int ACTIVITY_RESUMED=36,ACTIVITY_START_RESULT=37;
    public static final int HOOK=38,PROCESS_VIEW=39;
    public static final int APPLICATION_START_EVENT=40,APPLICATION_START_HISTORY=41;
    public static final int BROADCAST_SYSTEM_CLAIM=42;
    public static final int ACTIVITY_ENTRY_STATUS=43,BROADCAST_STICKY_VIEW=44;
    public static final int JOB_ROUTE=45,JOB_BEGIN=46,JOB_EVENT=47;
    public static final int PENDING_INTENT=48;
    public static final int EXIT_HISTORY=49;
    /** Late delivery of the bind-time abort capability to an identity-free spare worker that was bound before it had a launch. */
    public static final int ABORT_CONFIGURE=50;
    /** The package that presents runtime-permission dialogs; answered by the authority, whose PackageManager is the platform's. */
    public static final int PERMISSION_CONTROLLER=51;
    /** Authority to its own worker: perform a pending activity/service start as the guest application (a fired PendingIntent token). */
    public static final int DISPATCH_PENDING=52;
    /** Worker to authority: resolve a launch lease to the physical component this process owns for it. */
    public static final int ACTIVITY_RECORD=53;
    /** Worker obtains only its own generation's runtime-artifact directory. */
    public static final int CODE_ARTIFACT_DIRECTORY=54;
    public abstract static class Endpoint extends Binder {
        @Override protected final boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
            if(code<FIRST_CALL_TRANSACTION||code>CODE_ARTIFACT_DIRECTORY)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(DESCRIPTOR);
            final int pid=Binder.getCallingPid(),uid=Binder.getCallingUid();
            if(data.dataAvail()>64*1024)throw new SecurityException("REQUEST_TOO_LARGE");
            boolean descriptors=data.hasFileDescriptors();if(descriptors&&!acceptsFileDescriptors(code,pid,uid))throw new SecurityException("CONTROL_FILE_DESCRIPTOR_UNSUPPORTED");
            Bundle input=data.readBundle(RuntimeWire.class.getClassLoader());data.enforceNoDataAvail();
            Bundle output=null;
            try {if(descriptors)validateFileDescriptors(input);output=handle(code,input,pid,uid);reply.writeNoException();reply.writeBundle(output);}
            catch(Exception error){reply.writeException(wireException(error));}
            finally{if(descriptors)closeFileDescriptors(input);if(code==CODE_ARTIFACT_DIRECTORY&&output!=null){ParcelFileDescriptor directory=output.getParcelable("directory",ParcelFileDescriptor.class);if(directory!=null)try{directory.close();}catch(java.io.IOException failure){android.util.Log.w("TripoderCode","Directory descriptor close failed",failure);}}}
            return true;
        }
        protected abstract Bundle handle(int code,Bundle input,int pid,int uid) throws Exception;
        protected boolean acceptsFileDescriptors(int code,int pid,int uid){return false;}
        protected void validateFileDescriptors(Bundle input){throw new SecurityException("CONTROL_FILE_DESCRIPTOR_UNSUPPORTED");}
        protected void closeFileDescriptors(Bundle input){}
    }
    private static Exception wireException(Exception error){
        if(error instanceof ExecutionContract.ContractException)return new IllegalStateException(((ExecutionContract.ContractException)error).code+":"+error.getMessage());
        if(error instanceof SecurityException||error instanceof IllegalArgumentException||error instanceof NullPointerException||error instanceof IllegalStateException||error instanceof UnsupportedOperationException||error instanceof BadParcelableException)return error;
        return new IllegalStateException(error.getClass().getName()+":"+error.getMessage());
    }
    public static Bundle call(IBinder target,int code,Bundle input) throws RemoteException {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {data.writeInterfaceToken(DESCRIPTOR);data.writeBundle(input);if(data.hasFileDescriptors()){if(code!=CONTROL)throw new SecurityException("CONTROL_FILE_DESCRIPTOR_UNSUPPORTED");RuntimeInstallRequest.validate(input);}
            if(!target.transact(code,data,reply,0))throw new RemoteException("UNSUPPORTED_OPERATION");
            if(reply.hasFileDescriptors()&&code!=CODE_ARTIFACT_DIRECTORY)throw new SecurityException("CONTROL_FILE_DESCRIPTOR_UNSUPPORTED");
            reply.readException();Bundle output=reply.readBundle(RuntimeWire.class.getClassLoader());
            if(code==CODE_ARTIFACT_DIRECTORY&&(output==null||output.size()!=1||output.getParcelable("directory",ParcelFileDescriptor.class)==null))throw new SecurityException("ARTIFACT_DIRECTORY_RESPONSE_INVALID");return output;
        } finally {data.recycle();reply.recycle();}
    }
    public static String required(Bundle b,String key){String value=b.getString(key);if(value==null||value.isEmpty()||value.length()>4096)throw new IllegalArgumentException("INVALID_"+key);return value;}
}
