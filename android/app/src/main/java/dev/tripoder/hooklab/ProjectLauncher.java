package dev.tripoder.hooklab;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import java.util.function.Consumer;
final class ProjectLauncher {
    static GuestClient launch(Activity activity,String projectId,String tool,Consumer<String> status){
        status.accept("正在准备项目…");GuestClient client=new GuestClient(activity,projectId);
        client.connect(()->client.send(GuestService.EDITOR,new Bundle(),result->{
            if(activity.isFinishing()||activity.isDestroyed()){client.close();return;}
            if(result.has("error")){status.accept("无法启动");ProductUi.notice(activity,"应用未能启动",result.optString("error"));client.close();return;}
            activity.getSharedPreferences("recent-project",0).edit().putString(GuestStore.PACKAGE,projectId).apply();status.accept("项目已就绪");Intent next=ProjectStore.intent(activity,GuestStubActivity.class,projectId);if(tool!=null)next.putExtra("tool",tool);
            activity.startActivity(next);client.close();
        }));return client;
    }
}
