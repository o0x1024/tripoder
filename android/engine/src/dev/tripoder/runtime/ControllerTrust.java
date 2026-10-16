package dev.tripoder.runtime;

import android.content.Context;
import android.content.pm.*;
import java.security.MessageDigest;
import java.util.*;

/** A signature permission alone does not distinguish a trusted workbench from another signed app. */
final class ControllerTrust {
    static final String PACKAGE="dev.tripoder.workbench",PERMISSION="dev.tripoder.engine.permission.CONTROL";
    static void require(Context host,int pid,int uid){
        if(pid<=0||uid==android.os.Process.myUid()||host.checkPermission(PERMISSION,pid,uid)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("CONTROL_CALLER_REJECTED");
        requireIdentity(host,uid);
    }
    static void requireActivity(Context host,android.app.ComponentCaller caller){
        if(caller==null||caller.getUid()<0||caller.getUid()==android.os.Process.myUid()||!PACKAGE.equals(caller.getPackage()))throw new SecurityException("CONTROL_ACTIVITY_CALLER_REJECTED");
        if(host.getPackageManager().checkPermission(PERMISSION,PACKAGE)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("CONTROL_ACTIVITY_PERMISSION_REJECTED");
        requireIdentity(host,caller.getUid());
    }
    private static void requireIdentity(Context host,int uid){
        String[] packages=host.getPackageManager().getPackagesForUid(uid);
        if(packages==null||packages.length!=1||!PACKAGE.equals(packages[0]))throw new SecurityException("CONTROL_PACKAGE_REJECTED");
        try{
            PackageInfo info=host.getPackageManager().getPackageInfo(PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES);
            if(info.applicationInfo==null||info.applicationInfo.uid!=uid||info.signingInfo==null||info.signingInfo.hasMultipleSigners())throw new SecurityException("CONTROL_IDENTITY_REJECTED");
            Signature[] current=info.signingInfo.getApkContentsSigners();
            if(current.length!=1||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(current[0].toByteArray())).equals(EngineBuild.CONTROLLER_CERT_SHA256))throw new SecurityException("CONTROL_CERTIFICATE_REJECTED");
        }catch(PackageManager.NameNotFoundException error){throw new SecurityException("CONTROL_PACKAGE_MISSING",error);}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private ControllerTrust(){}
}
