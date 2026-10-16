package dev.tripoder.previewguest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** Standalone self-owned APK. No Tripoder classes, host calls, or runtime dependency. */
public final class CounterActivity extends Activity {
    private int count,recreations;
    private TextView number,message;
    private boolean returned;
    public static String formatCount(int value){return Integer.toString(value);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(4),1);return t;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void add(LinearLayout p,View v,int margin){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(margin);p.addView(v,lp);}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(15);b.setAllCaps(false);b.setTextColor(0xffffffff);b.setMinHeight(dp(54));b.setBackground(shape(0xff235c4b,18));b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        count=getSharedPreferences("counter",0).getInt("count",0);recreations=state==null?0:state.getInt("recreations",0);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xfff6f4ee);
        root.setOnApplyWindowInsetsListener((v,insets)->{var b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);if(v.getWindowInsetsController()!=null)v.getWindowInsetsController().setSystemBarsAppearance(24,24);return insets;});
        ScrollView scroll=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(24),dp(24),dp(24),dp(28));scroll.addView(page);root.addView(scroll);setContentView(root);
        add(page,text("TRIPODER  /  OWNED APP",12,0xff597368),0);
        TextView title=text("空间里的\n小实验。",36,0xff183d32);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);add(page,title,24);
        add(page,text("这是一个独立 APK，正在客体进程中运行。\n点击计数，退出后再打开，数据会留在这个空间。",14,0xff607268),16);
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setGravity(Gravity.CENTER);card.setPadding(dp(24),dp(28),dp(24),dp(28));card.setBackground(shape(0xffffffff,28));
        add(card,text("本空间的计数",13,0xff607268),0);number=text(formatCount(count),82,0xff235c4b);number.setGravity(Gravity.CENTER);number.setTypeface(Typeface.DEFAULT,Typeface.BOLD);add(card,number,14);
        message=text("已从本地数据读取",12,0xff607268);message.setGravity(Gravity.CENTER);add(card,message,8);
        add(card,button("＋  计数一次",()->{int next=count+1;if(!getSharedPreferences("counter",0).edit().putInt("count",next).commit()){message.setText("保存失败，计数未确认");return;}count=next;number.setText(formatCount(count));message.setText("已保存到当前空间");}),24);add(page,card,28);
        Button recreate=button("重新创建界面",()->{recreations++;recreate();});recreate.setBackground(shape(0xffe3eadf,18));recreate.setTextColor(0xff235c4b);add(page,recreate,18);
        add(page,button("完成并返回 Tripoder",this::complete),12);
        add(page,text("真实进程  "+android.os.Process.myPid()+"   ·   界面重建  "+recreations+" 次\n空间数据彼此分开；当前仍共享宿主 UID。",12,0xff607268),22);
    }
    private void complete(){if(returned)return;try{
        JSONObject result=new JSONObject().put("challenge",getIntent().getStringExtra("challenge")).put("package",getPackageName()).put("component",getComponentName().flattenToString()).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("count",count).put("recreations",recreations).put("data",getFilesDir().toString());
        setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));returned=true;finish();
    }catch(Exception error){message.setText("无法提交结果："+error.getMessage());}}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("recreations",recreations);super.onSaveInstanceState(state);}
}
