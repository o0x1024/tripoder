package dev.tripoder.runtimeprobe;

import android.system.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import android.content.Context;
import android.os.Bundle;
import dev.tripoder.runtime.ApkStore;

/** The collector only sees a final name after the complete JSON has been synced and closed. */
final class ProbeReports {
    static void publish(File target,String value)throws Exception{
        if(target.exists())throw new IllegalStateException("PROBE_REPORT_ALREADY_PUBLISHED");
        checkpoint(target,value);
    }
    /** Mutable progress is a separate artifact; final reports remain write-once. */
    static void checkpoint(File target,String value)throws Exception{
        File stage=new File(target+".pending");
        // This directory's ext_data_rw group is the explicitly authorized adb evidence reader.
        // Only self-owned probe observations are published here, never guest/account data.
        try(var out=new FileOutputStream(Os.open(stage.toString(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0660))){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Os.rename(stage.toString(),target.toString());
    }
    static Bundle handoff(Context context,String value)throws Exception{
        File target=new File(context.getExternalFilesDir(null),"inventory-"+java.util.UUID.randomUUID()+".json");
        publish(target,value);Bundle result=new Bundle();result.putString("reportName",target.getName());result.putString("reportSha256",ApkStore.digest(target));result.putLong("reportBytes",target.length());return result;
    }
    static String receive(Context context,Bundle receipt)throws Exception{
        String name=receipt.getString("reportName");if(name==null||!name.matches("inventory-[0-9a-f-]{36}\\.json"))throw new SecurityException("PROBE_REPORT_NAME_INVALID");
        File target=new File(context.getExternalFilesDir(null),name);long size=receipt.getLong("reportBytes",-1);
        if(size<0||size>32*1024*1024||target.length()!=size||!ApkStore.digest(target).equals(receipt.getString("reportSha256")))throw new SecurityException("PROBE_REPORT_INTEGRITY_FAILED");
        return java.nio.file.Files.readString(target.toPath());
    }
    private ProbeReports(){}
}
