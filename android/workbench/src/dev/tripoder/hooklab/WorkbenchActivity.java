package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.*;

/** Application library and project index are distinct views over the existing authority. */
public final class WorkbenchActivity extends Activity implements EngineConnection.Listener {
    private WorkbenchApplication app;private EngineConnection engine;private WorkbenchCatalog catalog;private WorkbenchAppActions appActions;
    private LinearLayout body,nav;private TextView status,title;private String application,lease="";
    private int tab;private boolean destroyed,refreshing,wasPending;
    private final Map<String,Bundle> presentations=new HashMap<>();
    private final Set<String> presentationRequests=new HashSet<>();
    private final Runnable operationObserver=this::operationChanged;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);app=(WorkbenchApplication)getApplication();engine=app.engine;appActions=new WorkbenchAppActions(this,app);
        application=getIntent().getStringExtra("application");tab=saved==null?getIntent().getIntExtra("tab",0):saved.getInt("tab",0);
        LinearLayout root=WorkbenchLibraryUi.root(this),toolbar=WorkbenchLibraryUi.toolbar(this,application==null?"Tripoder":"应用项目",application==null?null:this::finish);
        title=(TextView)toolbar.getChildAt(application==null?0:1);
        toolbar.addView(WorkbenchLibraryUi.action(this,"⋮",()->new AlertDialog.Builder(this).setItems(new String[]{"刷新","创建空间","设置"},(dialog,index)->{if(index==0)refresh();else if(index==1)createSpace();else if(application!=null)startActivity(new Intent(this,WorkbenchActivity.class).putExtra("tab",2));else{tab=2;render();}}).show()));
        root.addView(toolbar);status=WorkbenchLibraryUi.text(this,"",12,WorkbenchLibraryUi.MUTED);status.setPadding(d(20),d(6),d(20),d(8));root.addView(status);status.setOnClickListener(v->app.operations.query());
        body=WorkbenchLibraryUi.content(this,root);nav=ProductUi.row(this);nav.setBackgroundColor(WorkbenchLibraryUi.LINE);root.addView(nav);
        app.operations.observe(operationObserver);engine.observe(this);operationChanged();render();if(saved==null)engine.connect(this);
    }
    private int d(int value){return WorkbenchLibraryUi.dp(this,value);}
    @Override protected void onResume(){super.onResume();if(engine!=null&&engine.ready()){app.operations.query();refresh();}else if(body!=null)render();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("tab",tab);}
    @Override public void changed(){if(destroyed)return;if(!engine.ready()){catalog=null;lease="";presentations.clear();render();return;}if(!lease.equals(engine.lease)){lease=engine.lease;app.operations.query();refresh();}render();}
    private void operationChanged(){if(destroyed)return;boolean pending=app.operations.pending();status.setText(app.operations.message);status.setVisibility(status.length()==0?View.GONE:View.VISIBLE);boolean complete=wasPending&&!pending;wasPending=pending;if(complete)refresh();}
    private void refresh(){if(refreshing||!engine.ready())return;refreshing=true;String current=engine.lease;engine.execute(ControlWire.request("catalog"),(value,error)->{refreshing=false;if(destroyed||!current.equals(engine.lease))return;if(error!=null){show(error);return;}try{catalog=new WorkbenchCatalog(value);render();}catch(Exception invalid){show(invalid);}});}
    private void render(){
        if(body==null||destroyed)return;body.removeAllViews();nav.removeAllViews();nav.setVisibility(application==null?View.VISIBLE:View.GONE);
        String[] names={"应用","项目","设置"};for(int i=0;i<names.length;i++){final int index=i;TextView item=WorkbenchLibraryUi.action(this,names[i],()->{tab=index;render();});item.setTextColor(tab==i?WorkbenchLibraryUi.ACCENT:WorkbenchLibraryUi.MUTED);item.setSelected(tab==i);item.setContentDescription(names[i]+"标签");nav.addView(item,new LinearLayout.LayoutParams(0,d(58),1));}
        title.setText(application!=null?appName(application):tab==0?"Tripoder":names[tab]);
        try{if(application!=null)renderApp();else if(tab==0)renderApps();else if(tab==1)renderProjects(null);else renderSettings();}catch(Exception error){WorkbenchLibraryUi.copy(body,"读取失败："+error.getMessage());}
    }
    private void renderApps()throws Exception{
        if(catalog==null){WorkbenchLibraryUi.copy(body,engine.ready()?"正在读取应用…":"连接引擎后查看已添加的应用");if(!engine.ready())ProductUi.add(body,WorkbenchLibraryUi.action(this,"连接引擎",()->engine.connect(this)),0);return;}
        var all=app.projects.all();WorkbenchLibraryUi.copy(body,catalog.applications().size()+" 个应用 · 点击运行，长按管理");List<View> tiles=new ArrayList<>();
        for(String pkg:catalog.applications()){
            int count=catalog.projects(all,pkg).size();var tile=new WorkbenchLibraryUi.Tile(this,appName(pkg),count+" 个项目",null,false,()->appActions.launch(catalog,pkg));
            tile.setOnLongClickListener(v->{appActions.menu(catalog,pkg,appName(pkg));return true;});
            tile.setContentDescription("应用："+pkg+"，"+count+" 个项目");loadPresentation(pkg,tile);tiles.add(tile);
        }
        var add=new WorkbenchLibraryUi.Tile(this,"添加应用","",new ProductUi.Icon("plus",WorkbenchLibraryUi.INK),false,this::addApplication);add.setContentDescription("添加应用");tiles.add(add);WorkbenchLibraryUi.grid(body,tiles);
        if(catalog.applications().isEmpty())WorkbenchLibraryUi.copy(body,"添加应用后点击图标运行。长按应用可创建和管理项目。");
    }
    private void renderApp()throws Exception{
        if(catalog==null){WorkbenchLibraryUi.copy(body,"正在确认应用与项目…");return;}
        var hero=new WorkbenchLibraryUi.Tile(this,appName(application),catalog.projects(app.projects.all(),application).size()+" 个项目",null,false,()->{});hero.setClickable(false);hero.setFocusable(false);loadPresentation(application,hero);ProductUi.add(body,hero,0);
        WorkbenchLibraryUi.copy(body,application+"\n"+catalog.installations(application).size()+" 个运行空间");
        ProductUi.add(body,WorkbenchLibraryUi.action(this,"＋ 新建项目",()->WorkbenchProjectCreation.show(this,app,catalog,application,this::openProject)),0);renderProjects(application);
    }
    private void renderProjects(String pkg)throws Exception{
        var all=app.projects.all();var list=pkg==null?all:catalog.projects(all,pkg);
        if(pkg==null){WorkbenchLibraryUi.copy(body,"项目独立保存脚本，每个项目绑定一个应用。");ProductUi.add(body,WorkbenchLibraryUi.action(this,"＋ 新建项目",()->WorkbenchProjectCreation.show(this,app,catalog,null,this::openProject)),0);}
        if(list.isEmpty()){WorkbenchLibraryUi.copy(body,pkg==null?"还没有项目，先从应用创建一个。":"这个应用还没有项目。点击上方新建项目。");return;}
        for(JSONObject project:list){String id=project.getString("id");JSONObject ref=catalog==null?null:catalog.bindings.get(id);
            String target=catalog==null?"连接后确认所属应用":ref==null?"待选择应用":appName(ref.getString("package"))+" · "+ref.getString("space")+("COMMITTED".equals(ref.getString("state"))?"":" · 需重新绑定");
            LinearLayout row=ProductUi.column(this);row.setPadding(d(20),d(20),d(20),d(20));row.setBackgroundColor(WorkbenchLibraryUi.TILE);ProductUi.add(row,WorkbenchLibraryUi.text(this,project.getString("name"),17,WorkbenchLibraryUi.INK),0);ProductUi.add(row,WorkbenchLibraryUi.text(this,target,12,WorkbenchLibraryUi.MUTED),9);row.setFocusable(true);row.setOnClickListener(v->openProject(id));ProductUi.add(body,row,1);
        }
    }
    private void openProject(String id){startActivity(new Intent(this,WorkbenchProjectActivity.class).putExtra("project",id));}
    private void renderSettings(){
        WorkbenchLibraryUi.copy(body,"运行引擎");ProductUi.add(body,WorkbenchLibraryUi.action(this,engine.ready()?"已连接 · 刷新应用":"连接引擎",()->{if(engine.ready())refresh();else engine.connect(this);}),0);
        if(!engine.failure.isEmpty())WorkbenchLibraryUi.copy(body,engine.failure);
        WorkbenchLibraryUi.copy(body,"应用空间");if(catalog!=null)for(String space:catalog.spaces){long count=catalog.installs.stream().filter(i->space.equals(i.optString("space"))).count();WorkbenchLibraryUi.copy(body,space+" · "+count+" 个应用");}
        ProductUi.add(body,WorkbenchLibraryUi.action(this,"＋ 创建空间",this::createSpace),0);
        if(app.operations.pending()){ProductUi.add(body,WorkbenchLibraryUi.action(this,"查询操作",()->app.operations.query()),0);ProductUi.add(body,WorkbenchLibraryUi.action(this,"取消未接收操作",()->new AlertDialog.Builder(this).setTitle("取消未接收操作").setMessage("已接收的操作仍需查询原结果。").setNegativeButton("返回",null).setPositiveButton("确认取消",(dialog,which)->app.operations.cancelIfNotAccepted()).show()),0);}
        WorkbenchLibraryUi.copy(body,"关于 Tripoder\n应用由引擎安装，项目脚本保存在工作台。相同空间内的项目共享该应用的数据；需要独立应用数据时，请选择不同空间。");
    }
    private void addApplication(){if(catalog==null||!engine.ready())return;if(catalog.spaces.isEmpty()){new AlertDialog.Builder(this).setTitle("先创建运行空间").setMessage("应用需要安装到一个空间中。").setNegativeButton("取消",null).setPositiveButton("创建空间",(dialog,which)->createSpace()).show();return;}startActivity(new Intent(this,WorkbenchAddActivity.class));}
    private void createSpace(){if(!engine.ready()||app.operations.pending()||app.imports.preparing){ProductUi.notice(this,"暂时无法创建","请连接引擎，并等待当前操作确认。");return;}
        LinearLayout form=ProductUi.card(this);EditText input=ProductUi.field(form,"空间标识","字母、数字、短横线","space-"+UUID.randomUUID().toString().substring(0,8),false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("创建应用空间").setView(form).setNegativeButton("取消",null).setPositiveButton("创建",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{String id=input.getText().toString().trim();if(!id.matches("[A-Za-z0-9_-]{1,80}")){input.setError("请输入 1–80 个字母、数字、下划线或短横线");return;}try{app.operations.submit(ControlWire.request("createSpace","space",id,"operation",UUID.randomUUID().toString()),"创建空间",null);dialog.dismiss();}catch(Exception error){show(error);}}));dialog.show();}
    private String appName(String pkg){if(catalog!=null)for(JSONObject install:catalog.installations(pkg)){Bundle meta=presentations.get(install.optString("generation"));if(meta!=null)return meta.getString("label");}return pkg;}
    private void loadPresentation(String pkg,WorkbenchLibraryUi.Tile tile){
        var installs=catalog.installations(pkg);if(installs.isEmpty()){tile.detail.setText("应用未安装 · 项目保留");return;}JSONObject install=installs.get(0);String generation=install.optString("generation");Bundle cached=presentations.get(generation);if(cached!=null){applyPresentation(tile,cached);return;}
        if(!presentationRequests.add(generation))return;String current=engine.lease;
        engine.execute(ControlWire.request("appPresentation","space",install.optString("space"),"package",pkg,"generation",generation),(value,error)->{presentationRequests.remove(generation);if(destroyed||!current.equals(engine.lease))return;if(error!=null){tile.detail.setText("应用信息读取失败");tile.setContentDescription(pkg+"，应用信息读取失败："+error.getMessage());return;}presentations.put(generation,value);render();});
    }
    private void applyPresentation(WorkbenchLibraryUi.Tile tile,Bundle value){tile.name.setText(value.getString("label"));byte[] icon=value.getByteArray("icon");if(icon!=null)tile.icon.setImageDrawable(new BitmapDrawable(getResources(),BitmapFactory.decodeByteArray(icon,0,icon.length)));}
    private void show(Exception error){app.operations.inform(error.getClass().getSimpleName()+": "+error.getMessage());}
    @Override protected void onDestroy(){destroyed=true;engine.remove(this);app.operations.remove(operationObserver);super.onDestroy();}
}
