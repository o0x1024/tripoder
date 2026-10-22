package dev.tripoder.taskpresentation;

import android.app.*;
import android.graphics.*;
import android.os.Bundle;
import android.widget.*;

public final class TaskActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(40,80,40,40);
        TextView title=new TextView(this);title.setText("客体任务验收");title.setTextSize(26);root.addView(title);
        Button custom=new Button(this);custom.setText("设置动态任务标题");custom.setOnClickListener(v->{
            Bitmap icon=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);icon.eraseColor(Color.MAGENTA);
            setTaskDescription(new ActivityManager.TaskDescription("动态客体任务",icon,Color.BLUE));
            title.setText(icon.getPixel(40,40)==Color.MAGENTA?"原图保持不变":"原图被修改");
        });root.addView(custom);setContentView(root);
    }
}
