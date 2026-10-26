package dev.tripoder.isolatedfdprobe;

import android.os.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;

final class ProbeContract {
    static final String DESCRIPTOR="dev.tripoder.owned.isolated.fd.v1";
    static final int RUN=1,RESULT=2;
    static Bundle call(IBinder binder,int code,Bundle input)throws RemoteException{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
            data.writeInterfaceToken(DESCRIPTOR);data.writeBundle(input);
            if(!binder.transact(code,data,reply,0))throw new RemoteException("OWNED_PROBE_RPC_UNSUPPORTED");
            reply.readException();return reply.readBundle(ProbeContract.class.getClassLoader());
        }finally{data.recycle();reply.recycle();}
    }
    static byte[] bytes(ParcelFileDescriptor fd)throws Exception{
        long size=fd.getStatSize();if(size<=0||size>4*1024*1024)throw new IllegalArgumentException("OWNED_FD_SIZE_INVALID");
        byte[] result=new byte[(int)size];int at=0;
        while(at<result.length){int n=android.system.Os.pread(fd.getFileDescriptor(),result,at,result.length-at,at);if(n<=0)throw new EOFException("OWNED_FD_TRUNCATED");at+=n;}
        return result;
    }
    static String digest(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private ProbeContract(){}
}
