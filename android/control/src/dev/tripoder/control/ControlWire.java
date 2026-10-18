package dev.tripoder.control;

import android.os.*;
import java.util.*;

/** Workbench control transport. Guest component routing uses its separate internal protocol. */
public final class ControlWire {
    public static final String DESCRIPTOR="dev.tripoder.control.v1";
    public static final int CONNECT=1,EXECUTE=2;
    private ControlWire(){}
    /** Lifetime token of a controller: the engine links to its death to revoke the lease. */
    public static final class Owner extends Binder {}
    public abstract static class Endpoint extends Binder {
        protected abstract void authorize(int code,int pid,int uid);
        protected abstract Bundle handle(int code,Bundle input,int pid,int uid)throws Exception;
        protected boolean acceptsDescriptors(int code){return false;}
        @Override protected final boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
            int pid=Binder.getCallingPid(),uid=Binder.getCallingUid();Bundle input=null;
            try{
                authorize(code,pid,uid);data.enforceInterface(DESCRIPTOR);
                if(data.dataAvail()>65536)throw new IllegalArgumentException("CONTROL_REQUEST_TOO_LARGE");
                if(data.hasFileDescriptors()&&!acceptsDescriptors(code))throw new SecurityException("CONTROL_FD_REJECTED");
                input=data.readBundle(ControlWire.class.getClassLoader());data.enforceNoDataAvail();
                if(input==null)throw new IllegalArgumentException("CONTROL_REQUEST_REQUIRED");
                Bundle output=handle(code,input,pid,uid);reply.writeNoException();reply.writeBundle(output);
            }catch(Exception error){reply.setDataPosition(0);reply.setDataSize(0);reply.writeException(serializable(error));}
            finally{closeDescriptors(input,Collections.newSetFromMap(new IdentityHashMap<>()));}
            return true;
        }
    }
    public static Bundle call(IBinder peer,int code,Bundle input)throws RemoteException{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
            data.writeInterfaceToken(DESCRIPTOR);data.writeBundle(input);
            if(data.dataSize()>65536)throw new IllegalArgumentException("CONTROL_REQUEST_TOO_LARGE");
            if(!peer.transact(code,data,reply,0))throw new RemoteException("CONTROL_PROTOCOL_REJECTED");
            if(reply.dataSize()>524288||reply.hasFileDescriptors())throw new SecurityException("CONTROL_RESPONSE_SHAPE_INVALID");
            reply.readException();Bundle out=reply.readBundle(ControlWire.class.getClassLoader());reply.enforceNoDataAvail();if(out==null)throw new IllegalStateException("CONTROL_RESPONSE_REQUIRED");return out;
        }finally{data.recycle();reply.recycle();}
    }
    private static Exception serializable(Exception error){
        if(error instanceof SecurityException||error instanceof IllegalArgumentException||error instanceof NullPointerException||error instanceof IllegalStateException||error instanceof UnsupportedOperationException||error instanceof BadParcelableException)return error;
        return new IllegalStateException(error.getClass().getName()+":"+error.getMessage());
    }
    private static void closeDescriptors(Object value,Set<Object> visited){
        if(value==null||!visited.add(value))return;
        if(value instanceof ParcelFileDescriptor){try{((ParcelFileDescriptor)value).close();}catch(java.io.IOException error){android.util.Log.e("TripoderControl","Descriptor close failed",error);}}
        else if(value instanceof Bundle)for(String key:((Bundle)value).keySet())closeDescriptors(((Bundle)value).get(key),visited);
        else if(value instanceof ArrayList)for(Object item:(ArrayList<?>)value)closeDescriptors(item,visited);
        else if(value instanceof Object[])for(Object item:(Object[])value)closeDescriptors(item,visited);
    }
    public static Bundle request(String command,String...pairs){if(pairs.length%2!=0)throw new IllegalArgumentException("CONTROL_PAIRS_INVALID");Bundle out=new Bundle();out.putString("command",command);for(int i=0;i<pairs.length;i+=2)out.putString(pairs[i],pairs[i+1]);return out;}
    public static String required(Bundle input,String key){String value=input.getString(key);if(value==null||value.isEmpty()||value.length()>4096)throw new IllegalArgumentException("CONTROL_FIELD_REQUIRED:"+key);return value;}
}
