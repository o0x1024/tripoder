package dev.tripoder.launchcontract;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONObject;

/** Only owned aliases and component settings; never dispatch a private or permission entry. */
public final class Main extends Activity {
    private static final String PKG="dev.tripoder.launchcontract";
    private void setting(String name,int value){getPackageManager().setComponentEnabledSetting(new ComponentName(PKG,PKG+name),value,PackageManager.DONT_KILL_APP);}
    private boolean selected(String name,String category){Intent value=getPackageManager().getLaunchIntentForPackage(PKG);return value!=null&&value.getComponent()!=null&&value.getComponent().getClassName().equals(PKG+name)&&Intent.ACTION_MAIN.equals(value.getAction())&&value.hasCategory(category)&&(value.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)!=0;}
    private static void check(JSONObject checks,String name,boolean value)throws Exception{checks.put(name,value);if(!value)throw new AssertionError(name);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView text=new TextView(this);text.setText("RUNNING OWNED LAUNCH CHECKS");setContentView(text);
        new Thread(()->{
            JSONObject checks=new JSONObject(),report=new JSONObject();
            try{
                check(checks,"real-controller-entry-retains-main-info-category",Intent.ACTION_MAIN.equals(getIntent().getAction())&&getIntent().hasCategory(Intent.CATEGORY_INFO)&&!getIntent().hasCategory(Intent.CATEGORY_LAUNCHER));
                check(checks,"real-controller-entry-retains-selected-alias",getIntent().getComponent()!=null&&getIntent().getComponent().getClassName().equals(PKG+".Info"));
                check(checks,"sdk-front-door-selects-info-before-launcher",selected(".Info",Intent.CATEGORY_INFO));
                var matches=getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(PKG),0);
                check(checks,"data-restricted-info-filter-cannot-match-no-data-intent",matches.stream().noneMatch(item->item.activityInfo.name.equals(PKG+".InfoWithData")));
                var alias=getPackageManager().getActivityInfo(new ComponentName(PKG,PKG+".Info"),0);
                check(checks,"query-preserves-alias-name-and-real-target",alias.name.equals(PKG+".Info")&&alias.targetActivity.equals(PKG+".Main"));
                Intent first=getPackageManager().getLaunchIntentForPackage(PKG);first.setAction("owned-mutated-launch-intent").setComponent(new ComponentName(PKG,PKG+".Main"));
                check(checks,"mutating-returned-intent-does-not-change-entry-selection",selected(".Info",Intent.CATEGORY_INFO));
                setting(".PreferredInfo",1);check(checks,"enabled-higher-priority-info-is-selected",selected(".PreferredInfo",Intent.CATEGORY_INFO));
                Intent selector=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(PKG);
                var resolved=getPackageManager().queryIntentActivities(selector,PackageManager.GET_RESOLVED_FILTER);var preferred=resolved.stream().filter(item->item.activityInfo.name.equals(PKG+".PreferredInfo")).findFirst().orElseThrow();
                check(checks,"ordinary-apk-positive-activity-priority-is-capped-at-zero",preferred.priority==0&&preferred.filter!=null&&preferred.filter.getPriority()==0);
                preferred.filter.addCategory("owned-mutated-returned-filter");preferred.filter.setPriority(-999);preferred.filter.addDataScheme("owned-mutated-data");
                check(checks,"mutating-returned-filter-does-not-change-canonical-resolution",selected(".PreferredInfo",Intent.CATEGORY_INFO));
                check(checks,"resolved-filter-is-only-returned-when-requested",getPackageManager().queryIntentActivities(selector,0).stream().allMatch(item->item.filter==null));
                setting(".PreferredInfo",0);check(checks,"default-restores-disabled-info-declaration",selected(".Info",Intent.CATEGORY_INFO));
                setting(".Info",2);check(checks,"no-enabled-matching-info-selects-highest-priority-launcher",selected(".LaunchHigh",Intent.CATEGORY_LAUNCHER));
                setting(".Info",0);check(checks,"default-restores-canonical-info-entry",selected(".Info",Intent.CATEGORY_INFO));
                setting(".PrivateInfo",1);check(checks,"own-package-getter-can-return-private-info-without-dispatch",selected(".PrivateInfo",Intent.CATEGORY_INFO));setting(".PrivateInfo",0);
                setting(".PermissionInfo",1);check(checks,"own-package-getter-can-return-permission-info-without-dispatch",selected(".PermissionInfo",Intent.CATEGORY_INFO));setting(".PermissionInfo",0);
                OrderedResultChecks.run(this,(name,value)->check(checks,name,value));
                StickyRejectionChecks.run(this,(name,value)->check(checks,name,value));
                Intent broadcast=new Intent("owned.launch.PRIORITY_RECEIVER").setPackage(PKG);
                var receivers=getPackageManager().queryBroadcastReceivers(broadcast,PackageManager.GET_RESOLVED_FILTER);
                check(checks,"manifest-receiver-resource-priority-survives-resolution",receivers.size()==1&&receivers.get(0).activityInfo.name.equals(PKG+".PriorityReceiver")&&receivers.get(0).priority==-30&&receivers.get(0).filter!=null&&receivers.get(0).filter.getPriority()==-30);
                sendBroadcast(broadcast);
                check(checks,"package-targeted-manifest-broadcast-uses-resolved-filter-projection",PriorityReceiver.received.await(10,java.util.concurrent.TimeUnit.SECONDS));
                report.put("passed",true).put("checks",checks);
            }catch(Throwable error){try{report.put("passed",false).put("checks",checks).put("error",error.getClass().getName()+":"+error.getMessage());}catch(Exception ignored){}}
            finally{for(String name:new String[]{".Info",".PreferredInfo",".PrivateInfo",".PermissionInfo"})try{setting(name,0);}catch(Exception error){try{report.put("passed",false).put("restoreFailure",error.getClass().getName());}catch(Exception ignored){}}}
            String result=report.toString();android.util.Log.i("TripoderLaunchContract",result);runOnUiThread(()->text.setText(result));
        },"owned-launch-checks").start();
    }
}
