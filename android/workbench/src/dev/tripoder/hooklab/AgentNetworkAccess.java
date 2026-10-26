package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Request LAN access before advertising a reachable MCP endpoint. */
final class AgentNetworkAccess {
    private static final String PERMISSION="android.permission.ACCESS_LOCAL_NETWORK";
    private static final int REQUEST=29731;
    static boolean granted(Context context){return Build.VERSION.SDK_INT<37||context.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED;}
    static void enable(Activity page){
        if(!granted(page)){page.requestPermissions(new String[]{PERMISSION},REQUEST);return;}
        WorkbenchApplication app=(WorkbenchApplication)page.getApplication();
        try{
            page.getSharedPreferences("mcp-network",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).commit();
            page.startForegroundService(new Intent(page,AgentControlService.class));
        }catch(Exception error){app.agents.message=UiText.error(error);}
    }
    static void permissionResult(Activity page,int request){
        if(request!=REQUEST)return;
        if(granted(page))enable(page);
        else ((WorkbenchApplication)page.getApplication()).agents.message="网络入口未开启：请在系统应用权限中允许访问本地网络，再开启网络 MCP";
    }
    private AgentNetworkAccess(){}
}
