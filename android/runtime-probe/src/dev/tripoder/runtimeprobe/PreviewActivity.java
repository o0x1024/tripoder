package dev.tripoder.runtimeprobe;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Product-shaped UI over the new runtime, deliberately limited to owned fixtures. */
public final class PreviewActivity extends Activity implements PreviewClient.Listener {
    private PreviewClient client;
    private LinearLayout body,nav;
    private ScrollView scroll;
    private int tab;
    private final int[] positions=new int[4];
    private String fatal;
    private int d(int n){return PreviewUi.d(this,n);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);if(saved!=null)tab=Math.max(0,Math.min(3,saved.getInt("tab")));
        LinearLayout root=PreviewUi.column(this);root.setBackgroundColor(PreviewUi.BG);PreviewUi.insets(root);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);body=PreviewUi.column(this);body.setPadding(d(22),d(18),d(22),d(28));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=PreviewUi.row(this);nav.setBackgroundColor(PreviewUi.WHITE);nav.setPadding(d(8),d(8),d(8),d(4));root.addView(nav);setContentView(root);
        try{client=new PreviewClient(this,this);render();client.connect();}catch(Exception e){fatal=e.getMessage();render();}
    }
    @Override public void changed(){render();}
    @Override public void launch(Intent intent){startActivityForResult(intent,71);}
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);if(request==71&&client!=null){tab=2;positions[2]=0;client.result(code,data);}}
    private void render(){if(body==null)return;int y=scroll.getScrollY();body.removeAllViews();
        try{if(fatal!=null)noticeCard("工作区未打开",fatal);else if(tab==0)apps();else if(tab==1)spaces();else if(tab==2)records();else settings();}
        catch(Exception e){noticeCard("页面读取失败",String.valueOf(e.getMessage()));}
        navigation();scroll.post(()->scroll.scrollTo(0,Math.max(0,y)));
    }
    private void navigation(){nav.removeAllViews();String[] names={"应用","空间","记录","设置"},icons={"apps","spaces","records","settings"};
        for(int i=0;i<4;i++){final int next=i;int color=tab==i?PreviewUi.ACCENT:PreviewUi.MUTED;LinearLayout item=PreviewUi.column(this);item.setGravity(Gravity.CENTER);View icon=PreviewUi.icon(this,icons[i],color,tab==i?PreviewUi.TINT:PreviewUi.WHITE,42);item.addView(icon);TextView label=PreviewUi.text(this,names[i],11,color,tab==i);label.setGravity(Gravity.CENTER);PreviewUi.add(item,label,5);item.setContentDescription(names[i]+"标签");item.setSelected(tab==i);item.setOnClickListener(v->switchTab(next));nav.addView(item,new LinearLayout.LayoutParams(0,d(68),1));}
    }
    private void switchTab(int value){positions[tab]=scroll.getScrollY();tab=value;render();scroll.post(()->scroll.scrollTo(0,positions[tab]));}
    private void header(String title,String subtitle){LinearLayout line=PreviewUi.row(this);line.addView(PreviewUi.text(this,title,28,PreviewUi.INK,true),new LinearLayout.LayoutParams(0,-2,1));line.addView(PreviewUi.chip(this,"运行预览",PreviewUi.ACCENT,PreviewUi.TINT));PreviewUi.add(body,line,0);PreviewUi.add(body,PreviewUi.text(this,subtitle,13,PreviewUi.MUTED,false),9);}
    private void apps()throws Exception {
        header("Tripoder","让每个应用，都有自己的空间。");
        LinearLayout hero=PreviewUi.card(this);android.graphics.drawable.GradientDrawable gradient=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{0xff28375f,0xff303c78});gradient.setCornerRadius(d(26));hero.setBackground(gradient);
        LinearLayout top=PreviewUi.row(this),copy=PreviewUi.column(this);PreviewUi.add(copy,PreviewUi.text(this,"从一次真实运行开始",20,PreviewUi.WHITE,true),0);PreviewUi.add(copy,PreviewUi.text(this,"打开自研应用，\n试试数据保存与空间切换。",13,0xffced7f3,false),12);top.addView(copy,new LinearLayout.LayoutParams(0,-2,1));top.addView(new PreviewUi.Orbit(this),new LinearLayout.LayoutParams(d(94),d(104)));PreviewUi.add(hero,top,0);
        Button start=PreviewUi.button(this,client.busy?client.message:"体验自研应用  →",true,client::start);start.setEnabled(client.ready&&!client.busy&&client.run==null);PreviewUi.add(hero,start,20);PreviewUi.add(hero,PreviewUi.text(this,"独立 APK  ·  真实客体进程  ·  本地数据",11,0xffced7f3,false),13);PreviewUi.add(body,hero,24);
        LinearLayout current=PreviewUi.card(this);current.setPadding(d(16),d(12),d(16),d(12));current.addView(PreviewUi.entry(this,"spaces",client.selected.isEmpty()?"尚未创建体验空间":client.title(client.selected),client.selected.isEmpty()?"首次体验时创建，可在「空间」中管理":"当前选中 · 点击切换空间",()->switchTab(1)));PreviewUi.add(body,current,14);
        if(!client.error.isEmpty())noticeCard("这次操作未完成",client.error);
        PreviewUi.section(body,"应用探索","2 个待接入");
        LinearLayout targets=PreviewUi.row(this);targets.setGravity(Gravity.TOP);
        addTarget(targets,"微信","com.tencent.mm","社交与跨应用调用",0);
        addTarget(targets,"王者荣耀","com.tencent.tmgp.sgame","游戏与图形体验",1);PreviewUi.add(body,targets,14);
        PreviewUi.add(body,PreviewUi.text(this,"这两个应用尚未接入运行预览。适配完成后，入口将在这里开放。",12,PreviewUi.MUTED,false),14);
    }
    private void addTarget(LinearLayout row,String name,String pkg,String detail,int index){LinearLayout card=PreviewUi.card(this);card.setPadding(d(16),d(18),d(16),d(18));
        try{ImageView icon=new ImageView(this);icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));card.addView(icon,new LinearLayout.LayoutParams(d(42),d(42)));}catch(android.content.pm.PackageManager.NameNotFoundException absent){card.addView(PreviewUi.icon(this,"apps",PreviewUi.MUTED,PreviewUi.BG,42));}
        PreviewUi.add(card,PreviewUi.text(this,name,16,PreviewUi.INK,true),14);PreviewUi.add(card,PreviewUi.text(this,detail,11,PreviewUi.MUTED,false),7);TextView chip=PreviewUi.chip(this,"等待适配",0xff876b2f,0xfffff2d8);LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(-2,-2);c.topMargin=d(14);card.addView(chip,c);
        card.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(name+" · 等待适配").setMessage("当前版本还不能在 Tripoder 内运行"+name+"。\n\n你现在可以体验自研应用的空间切换、数据保存和运行记录。真实应用的登录与功能验收将在接入后进行。").setPositiveButton("知道了",null).show());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(index==0)lp.rightMargin=d(12);row.addView(card,lp);
    }
    private void spaces()throws Exception {
        header("我的空间","给不同的体验，保留各自的数据。");
        if(client.spaces.length()==0)empty("还没有空间","创建第一个体验空间，然后打开自研应用。","创建体验空间",client::create);
        for(int i=0;i<client.spaces.length();i++){
            JSONObject space=client.spaces.getJSONObject(i);String id=space.getString("id");boolean selected=id.equals(client.selected);LinearLayout card=PreviewUi.card(this);
            LinearLayout line=PreviewUi.row(this);line.addView(PreviewUi.icon(this,"spaces",PreviewUi.ACCENT,PreviewUi.TINT,46));TextView title=PreviewUi.text(this,client.title(id),17,PreviewUi.INK,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=d(12);line.addView(title,lp);if(selected)line.addView(PreviewUi.chip(this,"当前",PreviewUi.GREEN,0xffe2f2ed));PreviewUi.add(card,line,0);
            boolean installed=space.has("install");int running=space.getJSONArray("sessions").length();PreviewUi.add(card,PreviewUi.text(this,(installed?"已导入 1 个自研应用":"尚未导入应用")+"   ·   "+running+" 个活动进程",13,PreviewUi.MUTED,false),16);
            PreviewUi.add(card,PreviewUi.text(this,"应用数据随空间保存，切换空间不会清除数据。",12,PreviewUi.MUTED,false),9);
            Button choose=PreviewUi.button(this,selected?"进入当前空间":"切换到这个空间",selected,()->{client.select(id);switchTab(0);});choose.setEnabled(!client.busy&&client.run==null);PreviewUi.add(card,choose,20);PreviewUi.add(body,card,18);
        }
        if(client.spaces.length()>0){Button add=PreviewUi.button(this,"＋  新建体验空间",false,client::create);add.setEnabled(client.ready&&!client.busy&&client.run==null&&client.spaces.length()<8);PreviewUi.add(body,add,18);}
        if(!client.error.isEmpty())noticeCard("操作未完成",client.error);
        PreviewUi.add(body,PreviewUi.text(this,"预览最多可创建 8 个空间。当前属于逻辑数据分区，尚不具备对恶意 Native 代码的强安全隔离。",12,PreviewUi.MUTED,false),20);
    }
    private void records()throws Exception {
        header("运行记录","每一次启动，都留下真实结果。");List<JSONObject> all=client.records.all();
        if(client.busy){LinearLayout progress=PreviewUi.card(this);PreviewUi.add(progress,PreviewUi.text(this,client.message,15,PreviewUi.ACCENT,true),0);PreviewUi.add(body,progress,18);}
        if(!client.error.isEmpty())noticeCard("结果未确认",client.error);
        if(all.isEmpty())empty("第一条记录，等你开始","体验应用返回后，这里会显示实际运行结果。","去体验应用",()->switchTab(0));
        int shown=0;for(JSONObject record:all){if(shown++>=30)break;String state=record.optString("state");boolean ok=state.equals("SUCCEEDED"),cancel=state.equals("CANCELLED");LinearLayout card=PreviewUi.card(this);LinearLayout top=PreviewUi.row(this);top.addView(PreviewUi.text(this,"空间计数器",16,PreviewUi.INK,true),new LinearLayout.LayoutParams(0,-2,1));top.addView(PreviewUi.chip(this,ok?"已完成":cancel?"已返回":"待确认",ok?PreviewUi.GREEN:PreviewUi.MUTED,ok?0xffe2f2ed:PreviewUi.BG));PreviewUi.add(card,top,0);
            PreviewUi.add(card,PreviewUi.text(this,client.title(record.optString("space"))+"  ·  "+time(record.optLong("created")),12,PreviewUi.MUTED,false),11);
            if(ok){JSONObject result=record.getJSONObject("result");PreviewUi.add(card,PreviewUi.text(this,"计数 "+result.getInt("count")+"   ·   界面重建 "+result.getInt("recreations")+" 次",20,PreviewUi.INK,true),20);PreviewUi.add(card,PreviewUi.text(this,"客体结果已返回 · 运行进程已停止",12,PreviewUi.GREEN,false),9);}
            else PreviewUi.add(card,PreviewUi.text(this,cancel?"已退出应用，没有提交计数结果。":"尚无完整完成凭据，不能认定运行成功。",13,PreviewUi.MUTED,false),16);
            Button detail=PreviewUi.button(this,"查看运行详情",false,()->recordDetail(record));PreviewUi.add(card,detail,18);PreviewUi.add(body,card,16);
        }
        if(all.size()>30)PreviewUi.add(body,PreviewUi.text(this,"显示最近 30 条，历史记录保留在本地。",12,PreviewUi.MUTED,false),18);
    }
    private void recordDetail(JSONObject r){String result="未提交客体结果";JSONObject guest=r.optJSONObject("result");if(guest!=null)result="客体 PID："+guest.optInt("pid")+"\n计数："+guest.optInt("count")+"\n界面重建："+guest.optInt("recreations")+"\n数据目录："+guest.optString("data");new AlertDialog.Builder(this).setTitle("运行详情").setMessage("状态："+r.optString("state")+"\n空间："+client.title(r.optString("space"))+"\n时间："+time(r.optLong("created"))+"\n\n"+result+"\n\n运行 ID："+r.optString("id")+(r.has("error")?"\n错误："+r.optString("error"):"")).setPositiveButton("关闭",null).show();}
    private void settings(){header("设置","了解当前版本与运行环境。");LinearLayout card=PreviewUi.card(this);
        card.addView(PreviewUi.entry(this,"shield","运行状态",client.ready?"内核已连接 · Android "+android.os.Build.VERSION.RELEASE:client.message,()->new AlertDialog.Builder(this).setTitle("当前运行环境").setMessage("设备 API："+android.os.Build.VERSION.SDK_INT+"\n架构："+android.os.Build.SUPPORTED_ABIS[0]+"\n控制界面 PID："+android.os.Process.myPid()+"\n\n自研应用在 Runtime 的客体进程中运行，不需要 Root 或 ADB 激活。").setPositiveButton("关闭",null).show()));PreviewUi.divider(card);
        card.addView(PreviewUi.entry(this,"spaces","数据与边界","本地保存 · 独立空间目录",()->notice("数据与边界","体验应用的数据按空间保存。界面中的空间与安装状态来自实际运行内核。\n\n当前客体和运行内核共享 Android UID，逻辑分区不等于强安全沙箱。这个版本用于自研应用体验，不能作为任意第三方应用的安全隔离环境。")));PreviewUi.divider(card);
        card.addView(PreviewUi.entry(this,"records","关于这次预览","独立研发 · 自研 APK 真实运行",()->notice("Tripoder · 运行预览","已开放：空间创建与切换、自研 APK 导入、真实 Activity、数据保存、结果记录。\n\n待接入：项目与脚本工作台、微信、王者荣耀。\n\n当前预览沿用 Runtime 测试包，原有 Tripoder 工作台的数据与项目未迁移。")));PreviewUi.add(body,card,24);
        Button refresh=PreviewUi.button(this,"刷新运行状态",false,client::refresh);refresh.setEnabled(client.ready&&!client.busy);PreviewUi.add(body,refresh,18);
        PreviewUi.add(body,PreviewUi.text(this,"TRIPODER  /  UI PREVIEW 01\n普通打开页面不会启动自动化验收。",12,PreviewUi.MUTED,false),28);
    }
    private void notice(String title,String text){new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("知道了",null).show();}
    private void noticeCard(String title,String detail){LinearLayout card=PreviewUi.card(this);card.setBackground(PreviewUi.shape(this,0xfffff1e5,20));PreviewUi.add(card,PreviewUi.text(this,title,15,0xff8d551b,true),0);PreviewUi.add(card,PreviewUi.text(this,detail,12,0xff8d551b,false),8);PreviewUi.add(body,card,16);}
    private void empty(String title,String text,String action,Runnable callback){LinearLayout card=PreviewUi.card(this);PreviewUi.add(card,PreviewUi.icon(this,"spaces",PreviewUi.ACCENT,PreviewUi.TINT,56),0);PreviewUi.add(card,PreviewUi.text(this,title,20,PreviewUi.INK,true),24);PreviewUi.add(card,PreviewUi.text(this,text,14,PreviewUi.MUTED,false),12);Button b=PreviewUi.button(this,action,true,callback);b.setEnabled(client!=null&&client.ready&&!client.busy);PreviewUi.add(card,b,24);PreviewUi.add(body,card,24);}
    private static String time(long time){return new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(time));}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("tab",tab);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){if(client!=null)client.close();super.onDestroy();}
}
