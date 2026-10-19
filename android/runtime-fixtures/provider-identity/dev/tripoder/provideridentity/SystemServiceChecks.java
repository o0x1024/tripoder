package dev.tripoder.provideridentity;

import android.app.ActivityManager;
import android.app.ApplicationStartInfo;
import android.content.*;
import android.content.pm.*;
import org.json.*;

final class SystemServiceChecks {
    private interface Attempt{void run()throws Exception;}
    private static void check(JSONArray checks,String name,boolean passed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed));if(!passed)throw new AssertionError(name);}
    private static boolean missing(Attempt action)throws Exception{try{action.run();return false;}catch(PackageManager.NameNotFoundException expected){return true;}}
    private static boolean rejected(Attempt action,String code)throws Exception{try{action.run();return false;}catch(UnsupportedOperationException expected){return expected.getMessage().contains(code);}}
    static JSONObject inspect(Context context,boolean baseline,JSONArray checks)throws Exception{
        PackageManager pm=context.getPackageManager();String pkg=context.getPackageName();ComponentName service=new ComponentName(pkg,MultiprocessPeer.class.getName());
        ServiceInfo info=pm.getServiceInfo(service,PackageManager.GET_META_DATA);
        check(checks,"service-declared-remote-process-preserved",(pkg+":peer").equals(info.processName)&&info.enabled&&!info.exported);
        check(checks,"service-application-process-is-declared-default",pkg.equals(info.applicationInfo.processName)&&pkg.equals(context.getApplicationInfo().processName));
        check(checks,"service-metadata-from-own-manifest","owned-service".equals(info.metaData.getString("owned.service")));
        check(checks,"service-metadata-flag-bound",pm.getServiceInfo(service,0).metaData==null&&pm.getServiceInfo(service,0).applicationInfo.metaData==null);
        check(checks,"service-long-flags-preserve-metadata","owned-service".equals(pm.getServiceInfo(service,PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA)).metaData.getString("owned.service")));
        check(checks,"service-missing-is-name-not-found",missing(()->pm.getServiceInfo(new ComponentName(pkg,pkg+".MissingService"),0)));
        ComponentName disabled=new ComponentName(pkg,DisabledMetadataService.class.getName());
        check(checks,"disabled-service-excluded-by-default",missing(()->pm.getServiceInfo(disabled,0)));
        check(checks,"disabled-service-explicit-match",!pm.getServiceInfo(disabled,PackageManager.MATCH_DISABLED_COMPONENTS).enabled);
        int aware=PackageManager.MATCH_DIRECT_BOOT_AWARE,unaware=PackageManager.MATCH_DIRECT_BOOT_UNAWARE,disabledFlag=PackageManager.MATCH_DISABLED_COMPONENTS;
        check(checks,"service-direct-boot-unaware-selected",!pm.getServiceInfo(service,unaware).directBootAware);
        check(checks,"service-direct-boot-aware-excludes-unaware",missing(()->pm.getServiceInfo(service,aware)));
        check(checks,"service-direct-boot-both-includes-unaware",!pm.getServiceInfo(service,aware|unaware).directBootAware);
        check(checks,"service-direct-boot-disabled-aware-selected",pm.getServiceInfo(disabled,aware|disabledFlag).directBootAware);
        check(checks,"service-direct-boot-unaware-excludes-aware",missing(()->pm.getServiceInfo(disabled,unaware|disabledFlag)));
        check(checks,"service-direct-boot-does-not-enable-disabled",missing(()->pm.getServiceInfo(disabled,aware|unaware)));
        check(checks,"service-metadata-combined-direct-boot-flags","owned-service".equals(pm.getServiceInfo(service,PackageManager.GET_META_DATA|disabledFlag|aware|unaware).metaData.getString("owned.service")));
        if(!baseline){
            info.metaData.putString("owned.service","tampered");info.applicationInfo.metaData.putString("owned.label","tampered");
            ServiceInfo fresh=pm.getServiceInfo(service,PackageManager.GET_META_DATA);
            check(checks,"service-metadata-and-application-defensive-copy","owned-service".equals(fresh.metaData.getString("owned.service"))&&"owned-installation".equals(fresh.applicationInfo.metaData.getString("owned.label")));
            check(checks,"service-unknown-long-flags-rejected",rejected(()->pm.getServiceInfo(service,PackageManager.ComponentInfoFlags.of(1L<<40)),"SERVICE_INFO_FLAGS_UNSUPPORTED"));
            boolean notInstalled=false;try{pm.getServiceInfo(new ComponentName("dev.tripoder.notinstalled",MultiprocessPeer.class.getName()),0);}catch(PackageManager.NameNotFoundException expected){notInstalled=true;}
            check(checks,"service-other-package-query-not-found",notInstalled);
        }
        ActivityManager manager=context.getSystemService(ActivityManager.class);ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();manager.getMemoryInfo(memory);
        check(checks,"activity-manager-application-create-observed-self",MetadataApplication.observedSelfDuringCreate);
        if(!baseline)check(checks,"activity-manager-shared-across-owned-contexts",manager==context.getApplicationContext().getSystemService(ActivityManager.class));
        check(checks,"activity-manager-memory-classes-real-positive",manager.getMemoryClass()>0&&manager.getLargeMemoryClass()>=manager.getMemoryClass());
        check(checks,"activity-manager-memory-info-complete",memory.totalMem>0&&memory.advertisedMem>=memory.totalMem&&memory.availMem>0&&memory.freeMem>=0&&memory.freeMem<=memory.availMem&&memory.threshold>0);
        JSONArray processes=new JSONArray();boolean self=false,scoped=true;
        var observed=manager.getRunningAppProcesses();if(observed==null)throw new IllegalStateException("PROCESS_LIST_NULL");
        if(!baseline){
            long deadline=android.os.SystemClock.elapsedRealtime()+30000L;
            while(android.os.SystemClock.elapsedRealtime()<deadline){
                java.util.Set<Integer> started=new java.util.HashSet<>();
                for(var s:manager.getHistoricalProcessStartReasons(0))if(s.getStartupTimestamps().get(ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE)!=null)started.add(s.getPid());
                boolean ready=observed.size()>=2;for(var p:observed)ready&=started.contains(p.pid);if(ready)break;
                Thread.sleep(50);observed=manager.getRunningAppProcesses();if(observed==null)throw new IllegalStateException("PROCESS_LIST_NULL");
            }
        }
        for(var process:observed){
            self|=process.pid==android.os.Process.myPid()&&process.uid==android.os.Process.myUid()&&process.processName.equals(android.app.Application.getProcessName());
            scoped&=process.uid==android.os.Process.myUid()&&(process.processName.equals(pkg)||process.processName.startsWith(pkg+":"))&&process.pkgList!=null&&process.pkgList.length==1&&process.pkgList[0].equals(pkg);
            processes.put(new JSONObject().put("pid",process.pid).put("uid",process.uid).put("name",process.processName).put("importance",process.importance));
        }
        check(checks,"activity-manager-observes-real-caller",self);
        JSONArray starts=new JSONArray();boolean startupSelf=false,startupScoped=true,startupKnown=true;Long ownLaunch=null;
        var history=manager.getHistoricalProcessStartReasons(0);
        for(var start:history){
            Long launch=start.getStartupTimestamps().get(ApplicationStartInfo.START_TIMESTAMP_LAUNCH),create=start.getStartupTimestamps().get(ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE);
            if(start.getPid()==android.os.Process.myPid()&&start.getRealUid()==android.os.Process.myUid()&&start.getProcessName().equals(android.app.Application.getProcessName())){startupSelf=true;ownLaunch=launch;}
            startupScoped&=start.getRealUid()==android.os.Process.myUid()&&(start.getProcessName().equals(pkg)||start.getProcessName().startsWith(pkg+":"));
            startupKnown&=start.getIntent()==null&&start.getReason()==ApplicationStartInfo.START_REASON_OTHER&&start.getStartComponent()==ApplicationStartInfo.START_COMPONENT_OTHER
                &&!start.getStartupTimestamps().containsKey(ApplicationStartInfo.START_TIMESTAMP_FORK)&&!start.getStartupTimestamps().containsKey(ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME);
            starts.put(new JSONObject().put("pid",start.getPid()).put("uid",start.getRealUid()).put("name",start.getProcessName()).put("launchNs",launch).put("onCreateNs",create).put("state",start.getStartupState()).put("reason",start.getReason()));
        }
        if(baseline){
            // This firmware returns persisted snapshots, often with pid=0; it does not
            // promise that the currently running process is already in this history.
            check(checks,"application-start-history-native-snapshot",!history.isEmpty()&&startupScoped);
        }else check(checks,"application-start-history-observes-self",startupSelf&&ownLaunch!=null&&ownLaunch>0);
        if(!baseline){
            check(checks,"application-start-history-visible-during-onCreate",MetadataApplication.observedStartupDuringCreate);
            check(checks,"application-start-history-only-own-logical-package",startupScoped);
            check(checks,"application-start-history-does-not-invent-platform-facts",startupKnown);
            check(checks,"application-start-history-max-limit",manager.getHistoricalProcessStartReasons(1).size()==1);
            boolean negative=false;try{manager.getHistoricalProcessStartReasons(-1);}catch(IllegalArgumentException expected){negative=true;}
            check(checks,"application-start-history-negative-limit-rejected",negative);
            for(var start:history)start.getStartupTimestamps().clear();boolean preserved=false;
            for(var start:manager.getHistoricalProcessStartReasons(0))if(start.getPid()==android.os.Process.myPid()&&ownLaunch.equals(start.getStartupTimestamps().get(ApplicationStartInfo.START_TIMESTAMP_LAUNCH)))preserved=true;
            check(checks,"application-start-history-defensive-timestamp-copy",preserved);
            check(checks,"activity-manager-only-own-logical-package",scoped);
            check(checks,"activity-manager-tasks-empty-without-host-leakage",manager.getRunningTasks(1).isEmpty()&&manager.getAppTasks().isEmpty()&&manager.getRunningServices(1).isEmpty());
            android.os.Debug.MemoryInfo[] ownMemory=manager.getProcessMemoryInfo(new int[]{android.os.Process.myPid()});
            check(checks,"activity-manager-owned-pid-memory-is-real",ownMemory!=null&&ownMemory.length==1&&ownMemory[0]!=null&&ownMemory[0].getTotalPss()>0);
            boolean foreignMemory=false;try{manager.getProcessMemoryInfo(new int[]{1});}catch(SecurityException expected){foreignMemory=expected.getMessage().contains("PROCESS_MEMORY_PID_OUTSIDE_INSTALL");}
            check(checks,"activity-manager-arbitrary-pid-memory-rejected",foreignMemory);
        }
        return new JSONObject().put("memoryClass",manager.getMemoryClass()).put("largeMemoryClass",manager.getLargeMemoryClass()).put("lowRam",manager.isLowRamDevice())
            .put("totalMem",memory.totalMem).put("advertisedMem",memory.advertisedMem).put("availMem",memory.availMem).put("freeMem",memory.freeMem).put("threshold",memory.threshold).put("lowMemory",memory.lowMemory).put("processes",processes).put("startHistory",starts);
    }
}
