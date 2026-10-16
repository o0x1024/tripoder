package dev.tripoder.hooklab;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** App library and durable projects; ordinary startup never runs diagnostics. */
public final class MainActivity extends Activity {
    private ProjectStore store;
    private LinearLayout content,nav;
    private ScrollView scroll;
    private int selected;
    private final int[] positions=new int[3];
    private GuestClient launching;
    private boolean preparing;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(routeRequested())return;
        store=new ProjectStore(this);try{store.initialize();}catch(Exception error){ProductUi.notice(this,"工作区无法打开",error.getMessage());return;}
        if(saved!=null)selected=Math.min(2,saved.getInt("tab",0));
        LinearLayout root=ProductUi.column(this);root.setBackgroundColor(ProductUi.BG);UiInsets.apply(root);
        scroll=new ScrollView(this);scroll.setFillViewport(true);content=ProductUi.column(this);content.setPadding(d(20),d(18),d(20),d(28));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ProductUi.divider(root);nav=ProductUi.row(this);nav.setBackgroundColor(ProductUi.WHITE);nav.setPadding(d(8),d(8),d(8),d(8));root.addView(nav);setContentView(root);render();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);routeRequested();}
    private boolean routeRequested(){
        if(getIntent().getBooleanExtra("game",false)){route(GameLaunchActivity.class);return true;}
        if(getIntent().getBooleanExtra("projectTest",false)){route(ProjectVerificationActivity.class);return true;}
        if(getIntent().getBooleanExtra("workbench",false)){route(ScriptActivity.class);return true;}
        if(getIntent().getBooleanExtra("guest",false)){route(GuestActivity.class);return true;}
        if(getIntent().hasExtra("autoDelaySeconds")){route(DiagnosticsActivity.class);return true;}
        return false;
    }
    private int d(int n){return ProductUi.dp(this,n);}
    private void route(Class<?> type){Intent next=new Intent(this,type);if(getIntent().getExtras()!=null)next.putExtras(getIntent().getExtras());startActivity(next);finish();}
    private void header(String title,String description,Runnable add){
        LinearLayout row=ProductUi.row(this);row.addView(ProductUi.text(this,title,28,ProductUi.INK,true),new LinearLayout.LayoutParams(0,-2,1));
        if(add!=null){Button button=ProductUi.button(this,"＋",false,add);button.setContentDescription("添加"+title);row.addView(button,new LinearLayout.LayoutParams(d(48),d(48)));}ProductUi.add(content,row,0);ProductUi.add(content,ProductUi.text(this,description,13,ProductUi.MUTED,false),10);
    }
    private void render(){
        content.removeAllViews();try{if(selected==0)apps();else if(selected==1)projects();else settings();}catch(Exception error){ProductUi.add(content,ProductUi.text(this,"读取失败："+error.getMessage(),14,ProductUi.MUTED,false),18);}
        nav.removeAllViews();String[] names={"应用","项目","设置"},icons={"grid","file","settings"};
        for(int i=0;i<3;i++){int index=i,color=selected==i?ProductUi.ACCENT:ProductUi.MUTED;LinearLayout item=ProductUi.column(this);item.setGravity(Gravity.CENTER);item.addView(ProductUi.icon(this,icons[i],color,selected==i?ProductUi.TINT:ProductUi.WHITE,32),new LinearLayout.LayoutParams(d(44),d(32)));ProductUi.add(item,ProductUi.text(this,names[i],12,color,selected==i),5);((TextView)item.getChildAt(1)).setGravity(Gravity.CENTER);item.setContentDescription(names[i]+"标签");item.setSelected(selected==i);item.setOnClickListener(v->{positions[selected]=scroll.getScrollY();selected=index;render();});nav.addView(item,new LinearLayout.LayoutParams(0,d(64),1));}
        scroll.post(()->scroll.scrollTo(0,positions[selected]));
    }
    private View appIcon(JSONObject app){
        try{ImageView v=new ImageView(this);v.setImageDrawable(getPackageManager().getApplicationIcon(app.getString("packageName")));return v;}catch(Exception ignored){return ProductUi.icon(this,"brand",ProductUi.ACCENT,ProductUi.TINT,48);}
    }
    private void apps() throws Exception {
        JSONArray apps=store.apps();header("我的应用",apps.length()+" 个已添加 · 选择应用开始",this::addApplication);
        for(int i=0;i<=apps.length();i+=2){LinearLayout row=ProductUi.row(this);for(int j=i;j<Math.min(i+2,apps.length()+1);j++){
            LinearLayout tile=ProductUi.card(this);tile.setMinimumHeight(d(188));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(j%2==0)lp.rightMargin=d(10);
            if(j==apps.length()){tile.setGravity(Gravity.CENTER);tile.addView(ProductUi.icon(this,"plus",ProductUi.ACCENT,ProductUi.TINT,48),new LinearLayout.LayoutParams(d(48),d(48)));ProductUi.add(tile,ProductUi.text(this,"添加应用",15,ProductUi.ACCENT,true),16);tile.setContentDescription("添加应用");tile.setOnClickListener(v->addApplication());}
            else{JSONObject app=apps.getJSONObject(j);tile.addView(appIcon(app),new LinearLayout.LayoutParams(d(48),d(48)));ProductUi.add(tile,ProductUi.text(this,app.getString("name"),15,ProductUi.INK,true),13);boolean legacy=ProjectStore.legacyFixtureApp(app);ProductUi.add(tile,ProductUi.text(this,(legacy?"legacy 示例实验 · ":ProjectStore.hostSourceApp(app)?"本机来源 · 未导入 · ":"旧来源 · 未绑定 · ")+app.optString("versionName"),11,ProductUi.MUTED,false),7);
                ProductUi.add(tile,ProductUi.button(this,"进入项目",false,()->chooseProject(app)),14);tile.setOnClickListener(v->openApp(app));tile.setOnLongClickListener(v->{chooseProject(app);return true;});}
            row.addView(tile,lp);
        }if(row.getChildCount()==1)row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));ProductUi.add(content,row,16);}
        ProductUi.add(content,ProductUi.text(this,"本机来源记录不代表虚拟安装。此工作台保留 legacy 实验入口，新 runtime 安装与空间尚未接入。",12,ProductUi.MUTED,false),18);
    }
    private void projects() throws Exception {
        header("我的项目","脚本、测试和文件，都跟随项目保存",this::selectAppForProject);
        for(JSONObject project:store.projects()){LinearLayout card=ProductUi.card(this);JSONObject app=project.getJSONObject("app");card.addView(ProductUi.entry(this,"file",project.getString("name"),app.getString("name")+" · "+app.optString("versionName"),()->openProject(project.optString("id"))));ProductUi.add(content,card,14);}
    }
    private void settings(){
        header("设置","连接与偏好",null);LinearLayout card=ProductUi.card(this);
        card.addView(ProductUi.entry(this,"spark","AI 服务","Chat Completions · 密钥加密保存",()->startActivity(new Intent(this,AiSettingsActivity.class))));ProductUi.divider(card);
        card.addView(ProductUi.entry(this,"shield","运行与权限","本地运行，无需 Root 或 ADB 激活",()->ProductUi.notice(this,"运行与权限","此工作台保留 legacy 自有示例实验。实验使用旧项目数据；本机来源记录未导入新 runtime 安装与空间。\n\n项目与客体仍共享宿主 UID，不是独立安全沙箱。AI 密钥由 Android Keystore 加密保存。")));ProductUi.add(content,card,18);
        LinearLayout diagnostics=ProductUi.card(this);diagnostics.addView(ProductUi.entry(this,"tools","开发者诊断","引擎自检与运行证据",this::diagnostics));ProductUi.add(content,diagnostics,16);ProductUi.add(content,ProductUi.text(this,"Tripoder 0.6.0-preview · ARM64\n旧示例与游戏为 legacy 实验入口，主工作台尚未接入新 runtime。",12,ProductUi.MUTED,false),24);
    }
    private void addApplication(){
        List<ApplicationInfo> infos=new ArrayList<>();Set<String> seen=new HashSet<>();Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for(ResolveInfo r:getPackageManager().queryIntentActivities(query,0)){ApplicationInfo info=r.activityInfo.applicationInfo;if(!info.packageName.equals(getPackageName())&&seen.add(info.packageName))infos.add(info);}
        infos.sort((a,b)->{if(a.packageName.equals("com.tencent.tmgp.sgame"))return -1;if(b.packageName.equals("com.tencent.tmgp.sgame"))return 1;return a.loadLabel(getPackageManager()).toString().compareTo(b.loadLabel(getPackageManager()).toString());});
        String[] names=new String[infos.size()];for(int i=0;i<names.length;i++)names[i]=infos.get(i).loadLabel(getPackageManager())+"\n"+infos.get(i).packageName;
        new AlertDialog.Builder(this).setTitle("从本机添加应用").setItems(names,(dialog,index)->{try{JSONObject app=store.addInstalled(infos.get(index).packageName);render();new AlertDialog.Builder(this).setTitle("已保存本机来源记录").setMessage(app.getString("name")+" 的名称与版本快照已保存，可以建立项目。尚未复制安装包或创建虚拟安装。")
            .setPositiveButton("创建项目",(d,w)->createProject(app)).setNegativeButton("完成",null).show();}catch(Exception error){ProductUi.notice(this,"添加失败",error.getMessage());}}).setNegativeButton("取消",null).show();
    }
    private void selectAppForProject(){try{JSONArray apps=store.apps();String[] titles=new String[apps.length()];for(int i=0;i<titles.length;i++)titles[i]=apps.getJSONObject(i).getString("name");new AlertDialog.Builder(this).setTitle("选择目标应用").setItems(titles,(d,index)->{try{createProject(apps.getJSONObject(index));}catch(Exception error){ProductUi.notice(this,"无法创建",error.getMessage());}}).show();}catch(Exception error){ProductUi.notice(this,"无法读取应用",error.getMessage());}}
    private void createProject(JSONObject app){LinearLayout form=ProductUi.card(this);EditText name=ProductUi.field(form,"项目名称","给这次工作起个名字",app.optString("name")+"项目",false);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("新建项目").setView(form).setNegativeButton("取消",null).setPositiveButton("创建",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(b->{try{String id=store.create(name.getText().toString(),app);dialog.dismiss();openProject(id);}catch(Exception error){name.setError(error.getMessage());}}));dialog.show();}
    private void chooseProject(JSONObject app){try{List<JSONObject> matches=new ArrayList<>();for(JSONObject p:store.projects())if(p.getJSONObject("app").getString("id").equals(app.getString("id")))matches.add(p);String[] titles=new String[matches.size()+1];for(int i=0;i<matches.size();i++)titles[i]=matches.get(i).getString("name");titles[titles.length-1]="＋ 新建项目";new AlertDialog.Builder(this).setTitle(app.optString("name")+"的项目").setItems(titles,(d,index)->{if(index==matches.size())createProject(app);else openProject(matches.get(index).optString("id"));}).show();}catch(Exception error){ProductUi.notice(this,"项目读取失败",error.getMessage());}}
    private void openProject(String id){startActivity(ProjectStore.intent(this,ProjectActivity.class,id));}
    private void openApp(JSONObject app){if(!ProjectStore.legacyFixtureApp(app)){chooseProject(app);return;}if(preparing)return;preparing=true;launching=ProjectLauncher.launch(this,getSharedPreferences("recent-project",0).getString(GuestStore.PACKAGE,ProjectStore.DEFAULT),null,message->{preparing=message.equals("正在准备项目…");});}
    private void diagnostics(){new AlertDialog.Builder(this).setTitle("开发者诊断").setItems(new String[]{"宿主 Java / Native","独立 APK 与进程","脚本与 AI 接口","真实界面与交互"},(dialog,item)->{
        if(item==0)startActivity(new Intent(this,DiagnosticsActivity.class));else if(item==1)startActivity(new Intent(this,GuestActivity.class));else if(item==2)startActivity(new Intent(this,ScriptActivity.class).putExtra("selfTest",true));else startActivity(new Intent(this,GuestActivity.class).putExtra("component",true));}).setNegativeButton("取消",null).show();}
    @Override protected void onResume(){super.onResume();if(content!=null)render();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("tab",selected);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){if(launching!=null)launching.close();super.onDestroy();}
}
