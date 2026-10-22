package dev.tripoder.controltest;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.*;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import dev.tripoder.control.ControlWire;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Real guest tasks in both modes, including the application's own later description. */
public final class TaskPresentationIntegration extends WorkbenchUiTestBase {
    private static final String PACKAGE="dev.tripoder.taskpresentation",LABEL="客体任务验收";
    private IBinder session;
    @Override void test()throws Exception{
        report.put("scope","guest-recents-label-icon-and-custom-description");
        startThroughWorkbench(false);Object app=getTargetContext().getApplicationContext();session=(IBinder)field(field(app,"engine"),"session");
        JSONArray before=new JSONArray(execute(session,"catalog").getString("applications"));
        for(int i=0;i<before.length();i++)if(PACKAGE.equals(before.getJSONObject(i).getString("package")))throw new IllegalStateException("FIXTURE_APPLICATION_ALREADY_EXISTS");
        JSONObject fixtures;try(var input=getContext().getAssets().open("install-fixtures.json")){fixtures=new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        File apk=new File(getTargetContext().getCacheDir(),run+"-task.apk");
        try(var in=getContext().getAssets().open("task-presentation-guest.apk");var out=new FileOutputStream(apk)){in.transferTo(out);}
        List<String> spaces=new ArrayList<>();
        try{
            for(String mode:new String[]{"thin","thick"}){
                String space="task-ui-"+run.substring(8)+"-"+mode;spaces.add(space);execute(session,"createSpace","operation",id(),"space",space);
                Bundle installed;
                try(var descriptor=ParcelFileDescriptor.open(apk,ParcelFileDescriptor.MODE_READ_ONLY)){
                    Bundle input=ControlWire.request("install","space",space,"operation",id());
                    input.putStringArrayList("names",new ArrayList<>(List.of("base.apk")));input.putStringArrayList("sha256",new ArrayList<>(List.of(fixtures.getString("taskPresentationSha256"))));input.putParcelable("fd0",descriptor);
                    installed=ControlWire.call(session,ControlWire.EXECUTE,input);
                }
                String generation=installed.getString("generation");Bundle target=execute(session,"applicationTarget","space",space,"package",PACKAGE,"generation",generation);
                execute(session,"setApplicationMode","operation",id(),"project",target.getString("project"),"space",space,"generation",generation,"mode",mode);
                badgeChecks(mode,space);
                Bundle ready=execute(session,"prepareApplicationLaunch","operation",id(),"space",space,"package",PACKAGE,"generation",generation);
                Intent entry=ready.getParcelable("intent",Intent.class);getTargetContext().startActivity(entry);waitNode("设置动态任务标题",false);
                ActivityManager.AppTask task=awaitTask(entry,LABEL);
                check("guest-task-uses-application-label-"+mode,LABEL.equals(task.getTaskInfo().taskDescription.getLabel()),task.getTaskInfo().taskDescription.toString());
                int taskId=task.getTaskInfo().taskId;
                check("guest-task-remains-owned-by-host-"+mode,enginePackage().equals(task.getTaskInfo().baseActivity.getPackageName()),taskId);
                check("guest-recents-icon-has-tripoder-badge-"+mode,awaitBadgedIcon(task,false),mode);
                captureRecents(mode);
                getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);waitNode("设置动态任务标题",false);
                click("设置动态任务标题");awaitTask(entry,"动态客体任务");
                check("guest-can-replace-task-description-"+mode,"动态客体任务".equals(task.getTaskInfo().taskDescription.getLabel())&&task.getTaskInfo().taskDescription.getPrimaryColor()==Color.BLUE,task.getTaskInfo().taskDescription.toString());
                check("custom-icon-keeps-tripoder-badge-"+mode,awaitBadgedIcon(task,true),mode);
                waitNode("原图保持不变",false);check("guest-input-bitmap-unchanged-"+mode,true,"source bitmap retains original pixels");
                captureRecents("dynamic-"+mode);
                getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);waitNode("设置动态任务标题",false);
                getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);SystemClock.sleep(250);
                check("background-keeps-custom-description-"+mode,"动态客体任务".equals(task.getTaskInfo().taskDescription.getLabel()),task.getTaskInfo().taskDescription.toString());
                execute(session,"stopApplication","operation",id(),"space",space,"package",PACKAGE,"generation",generation);
                shell("am start -W -n "+enginePackage()+"/dev.tripoder.hooklab.WorkbenchActivity");
            }
            for(var task:getTargetContext().getSystemService(ActivityManager.class).getAppTasks()){
                var info=task.getTaskInfo();if(info.baseActivity!=null&&info.baseActivity.getClassName().equals("dev.tripoder.hooklab.WorkbenchActivity"))check("workbench-task-retains-own-identity",!LABEL.equals(info.taskDescription.getLabel())&&!"动态客体任务".equals(info.taskDescription.getLabel()),String.valueOf(info.taskDescription.getLabel()));
            }
        }finally{
            apk.delete();
            for(String space:spaces)execute(session,"deleteSpace","operation",id(),"space",space);
            Bundle deleted=execute(session,"deleteApplication","operation",id(),"package",PACKAGE);
            Object projects=field(app,"projects");JSONArray ids=new JSONArray(deleted.getString("deletedProjects"));for(int i=0;i<ids.length();i++)invoke(projects,"delete",new Class<?>[]{String.class},ids.getString(i));
        }
    }
    private boolean awaitBadgedIcon(ActivityManager.AppTask task,boolean custom)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+4000;
        do{
            Bitmap icon=task.getTaskInfo().taskDescription.getIcon();
            if(icon!=null&&!icon.isRecycled()){
                int count=0,w=icon.getWidth(),h=icon.getHeight();
                for(int x=w/2;x<w;x++)for(int y=h/2;y<h;y++){
                    int c=icon.getPixel(x,y);if(Math.abs(Color.red(c)-89)<10&&Math.abs(Color.green(c)-100)<10&&Math.abs(Color.blue(c)-221)<10)count++;
                }
                int base=icon.getPixel(w/4,h/4);
                boolean baseIntact=!custom||Color.red(base)>230&&Color.green(base)<25&&Color.blue(base)>230;
                if(count>w*h/40&&baseIntact)return true;
            }
            SystemClock.sleep(60);
        }while(SystemClock.elapsedRealtime()<deadline);
        return false;
    }
    private void badgeChecks(String mode,String space)throws Exception{
        Activity page=startActivitySync(new Intent().setComponent(engineComponent("dev.tripoder.hooklab.WorkbenchActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        String all=mode.equals("thin")?"THIN":"THIN / THICK";awaitBadge(page,all);capture(mode.equals("thin")?"mode-badge-thin":"mode-badge-mixed");
        if(mode.equals("thick")){
            runOnMainSync(()->{try{var filter=page.getClass().getDeclaredField("spaceFilter");filter.setAccessible(true);filter.set(page,space);invoke(page,"render",new Class<?>[0]);}catch(Exception error){throw new IllegalStateException(error);}});
            awaitBadge(page,"THICK");capture("mode-badge-thick");
        }
    }
    private void awaitBadge(Activity page,String label)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+8000;View[] tile={null};
        do{runOnMainSync(()->{try{tile[0]=findTile((View)field(page,"body"));}catch(Exception error){throw new IllegalStateException(error);}});if(tile[0]!=null&&String.valueOf(tile[0].getContentDescription()).contains("运行模式："+label))break;SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<deadline);
        check("application-card-announces-mode-"+label,tile[0]!=null&&String.valueOf(tile[0].getContentDescription()).contains("运行模式："+label),tile[0]==null?"missing":String.valueOf(tile[0].getContentDescription()));
        boolean[] valid={false};runOnMainSync(()->{try{
            TextView badge=(TextView)field(tile[0],"modeBadge");View icon=(View)field(tile[0],"icon");Rect b=new Rect(),i=new Rect(),t=new Rect();badge.getGlobalVisibleRect(b);icon.getGlobalVisibleRect(i);tile[0].getGlobalVisibleRect(t);
            valid[0]=badge.getText().toString().equals(label)&&badge.getVisibility()==View.VISIBLE&&!badge.isClickable()&&!badge.isLongClickable()&&b.bottom<=i.top&&b.right<=t.right&&b.left>=t.left&&b.top>=t.top;
        }catch(Exception error){throw new IllegalStateException(error);}});
        check("mode-badge-fits-card-without-blocking-icon-"+label,valid[0],label);
    }
    private View findTile(View view)throws Exception{
        if(view.getClass().getName().endsWith("WorkbenchLibraryUi$Tile")&&PACKAGE.equals(field(view,"pkg")))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findTile(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private ActivityManager.AppTask awaitTask(Intent entry,String label)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+8000;
        do{for(var task:getTargetContext().getSystemService(ActivityManager.class).getAppTasks()){
            var info=task.getTaskInfo();if(entry.getComponent().equals(info.baseActivity)&&label.equals(info.taskDescription.getLabel()))return task;
        }SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<deadline);
        throw new IllegalStateException("GUEST_TASK_DESCRIPTION_MISSING:"+label);
    }
    private void captureRecents(String mode)throws Exception{
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS);SystemClock.sleep(800);
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();JSONArray names=new JSONArray();labels(root,names);report.put("recentsLabels-"+mode,names);
        // This capture deliberately covers the requested system Recents UI, not the workbench capture helper.
        String name=run+"-task-recents-"+mode+".png";File out=new File(getTargetContext().getExternalFilesDir(null),name);
        shell("screencap -p "+out.getAbsolutePath());
        JSONArray captures=report.optJSONArray("captures");if(captures==null){captures=new JSONArray();report.put("captures",captures);}captures.put(name);
    }
    private static void labels(AccessibilityNodeInfo node,JSONArray out){if(node==null)return;if(node.getText()!=null)out.put(node.getText().toString());if(node.getContentDescription()!=null)out.put(node.getContentDescription().toString());for(int i=0;i<node.getChildCount();i++)labels(node.getChild(i),out);}
}
