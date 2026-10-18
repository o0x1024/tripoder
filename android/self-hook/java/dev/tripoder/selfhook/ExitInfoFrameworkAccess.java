package dev.tripoder.selfhook;

import android.app.ApplicationExitInfo;
import android.os.Parcel;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A logical name and hidden ownership stamp over an otherwise real system exit record. */
public final class ExitInfoFrameworkAccess {
    private static Method processName,summary;
    private static boolean attempted;
    public static synchronized void prepare()throws Exception{
        if(attempted)throw new IllegalStateException("EXIT_INFO_FRAMEWORK_ALREADY_ATTEMPTED");attempted=true;
        List<String> declarations=new ArrayList<>();Class<?> owner=FixedFrameworkMembers.owner(58);
        for(Object raw:FrameworkAccess.fixedMembers(58))if(raw instanceof Executable){
            Executable member=(Executable)raw;if(member.getDeclaringClass()!=owner)throw new SecurityException("EXIT_INFO_MEMBER_OWNER");
            declarations.add((member instanceof Constructor<?>?"<init>":member.getName())+FixedFrameworkMembers.signature(member.getParameterTypes(),member instanceof Method?((Method)member).getReturnType():void.class)+":"+Integer.toHexString(member.getModifiers()));
        }
        Collections.sort(declarations);String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((String.join("\n",declarations)+"\n").getBytes(StandardCharsets.UTF_8)));
        if(!digest.equals("ffa6a3e76e5ca765609aeaf441d64c0b7bdb6e8205990c65e90e0dfc29782a58"))throw new IllegalStateException("EXIT_INFO_DECLARATIONS_CHANGED:"+digest);
        processName=FixedFrameworkMembers.method(58,"setProcessName","(Ljava/lang/String;)V",false);
        summary=FixedFrameworkMembers.method(58,"setProcessStateSummary","([B)V",false);
    }
    public static ApplicationExitInfo view(ApplicationExitInfo physical,String logicalProcess){
        if(processName==null||summary==null)throw new IllegalStateException("EXIT_INFO_FRAMEWORK_NOT_READY");
        Objects.requireNonNull(physical);Objects.requireNonNull(logicalProcess);Parcel parcel=Parcel.obtain();ApplicationExitInfo result;
        try{physical.writeToParcel(parcel,0);if(parcel.hasFileDescriptors()||parcel.dataSize()>48000)throw new SecurityException("EXIT_INFO_PARCEL_UNSUPPORTED");parcel.setDataPosition(0);result=ApplicationExitInfo.CREATOR.createFromParcel(parcel);if(parcel.dataAvail()!=0)throw new SecurityException("EXIT_INFO_PARCEL_TRAILING");}finally{parcel.recycle();}
        FixedFrameworkMembers.call(processName,result,logicalProcess);
        // Guest code has not been admitted to set its own summary. The internal
        // process-generation stamp is not application data. Trace retrievers keep
        // their genuine physical package/UID/PID and original system capability.
        FixedFrameworkMembers.call(summary,result,(Object)null);
        if(result.getPid()!=physical.getPid()||result.getRealUid()!=physical.getRealUid()||result.getReason()!=physical.getReason()||result.getStatus()!=physical.getStatus()||result.getTimestamp()!=physical.getTimestamp())throw new SecurityException("EXIT_INFO_SYSTEM_FIELDS_CHANGED");
        return result;
    }
    private ExitInfoFrameworkAccess(){}
}
