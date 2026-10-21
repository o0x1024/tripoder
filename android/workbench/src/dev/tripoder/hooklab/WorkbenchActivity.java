package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.util.*;

/** Application library and project index are distinct views over the existing authority. */
public final class WorkbenchActivity extends Activity implements EngineConnection.Listener {
    private WorkbenchApplication app;private EngineConnection engine;private WorkbenchCatalog catalog;private WorkbenchAppActions appActions;
    private WorkbenchAgentPanel globalAgent;
    private LinearLayout body,nav;private ScrollView scroll;private TextView title,spaceChip;private ProductUi.Status status;
    private String application,lease="",spaceFilter="",renderedPage="",busySignature="";
    private int tab,settled;private boolean destroyed,refreshing,refreshAgain;
    private final Set<String> presentationRequests=new HashSet<>();
    private final Runnable operationObserver=this::operationChanged;
    private static final String[] TABS={"应用","设置"},TAB_ICONS={"grid","settings"};
    @Override public void onCreate(Bundle saved){
        ProductUi.theme(this);super.onCreate(saved);app=(WorkbenchApplication)getApplication();engine=app.engine;appActions=new WorkbenchAppActions(this,app);
        application=getIntent().getStringExtra("application");tab=saved==null?getIntent().getIntExtra("tab",0):saved.getInt("tab",0);spaceFilter=saved==null?"":saved.getString("spaceFilter","");tab=tab==0?0:1;
        LinearLayout root=ProductUi.root(this),toolbar=ProductUi.toolbar(this,application==null?"Tripoder":"应用",application!=null);title=ProductUi.title(toolbar);
        spaceChip=ProductUi.text(this,"全部空间 ▾",13,ProductUi.MUTED,true);spaceChip.setGravity(Gravity.CENTER);spaceChip.setMinHeight(d(40));spaceChip.setPadding(d(12),0,d(12),0);spaceChip.setBackground(new RippleDrawable(ColorStateList.valueOf(ProductUi.TINT),ProductUi.shape(ProductUi.night?ProductUi.SURFACE:0xffe8eaf2,20,this),null));spaceChip.setOnClickListener(v->selectSpace());
        if(application==null)toolbar.addView(spaceChip);
        toolbar.addView(ProductUi.iconButton(this,"more","更多",this::more),new LinearLayout.LayoutParams(d(48),d(48)));
        root.addView(toolbar);status=new ProductUi.Status(this);status.view.setOnClickListener(v->app.operations.query());root.addView(status.view);
        scroll=new ScrollView(this);scroll.setFillViewport(true);body=ProductUi.column(this);body.setPadding(0,0,0,d(24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        View line=new View(this);line.setBackgroundColor(ProductUi.LINE);root.addView(line,new LinearLayout.LayoutParams(-1,d(1)));nav=ProductUi.row(this);nav.setBackgroundColor(ProductUi.SURFACE);root.addView(nav);line.setVisibility(application==null?View.VISIBLE:View.GONE);
        app.operations.observe(operationObserver);engine.observe(this);operationChanged();render();if(saved==null)engine.connect(this);
    }
    private int d(int value){return ProductUi.dp(this,value);}
    @Override protected void onResume(){super.onResume();if(engine!=null&&engine.ready()){app.operations.query();refresh();}else if(body!=null)render();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("tab",tab);out.putString("spaceFilter",spaceFilter);}
    @Override public void changed(){if(destroyed)return;if(!engine.ready()){catalog=null;lease="";render();return;}if(!lease.equals(engine.lease)){lease=engine.lease;app.operations.query();refresh();}render();}
    private void operationChanged(){
        if(destroyed)return;status.show(app.operations.message,app.operations.busy());boolean complete=settled!=app.operations.settled;settled=app.operations.settled;
        String busy=app.operations.busySignature();boolean redraw=!busy.equals(busySignature);busySignature=busy;
        if(complete)refresh();else if(redraw)render();
    }
    private void refresh(){
        if(!engine.ready())return;if(refreshing){refreshAgain=true;return;}refreshing=true;refreshAgain=false;String current=engine.lease;
        engine.execute(ControlWire.request("catalog"),(value,error)->{refreshing=false;try{
            if(destroyed||!current.equals(engine.lease))return;if(error!=null){show(error);return;}
            catalog=new WorkbenchCatalog(value);catalog.sync(app);if(!spaceFilter.isEmpty()&&!catalog.spaces.contains(spaceFilter))spaceFilter="";
            Set<String> active=new HashSet<>();for(JSONObject install:catalog.installs)active.add(install.optString("generation"));app.presentations.prune(active);app.spaces.ensureDefault(catalog,app.operations,engine);render();
        }catch(Exception invalid){show(invalid);}finally{if(refreshAgain&&!destroyed&&engine.ready()){refreshAgain=false;refresh();}}});
    }
    private void more(){
        List<String> items=new ArrayList<>();items.add("刷新");items.add("新建空间");
        boolean hasApps=catalog!=null&&!catalog.applications(spaceFilter).isEmpty();
        if(tab==0&&application==null&&hasApps)items.add("批量管理应用");
        new AlertDialog.Builder(this).setItems(items.toArray(String[]::new),(dialog,index)->{
            String choice=items.get(index);
            if("刷新".equals(choice)){app.operations.query();refresh();}
            else if("新建空间".equals(choice))createSpace();
            else if("批量管理应用".equals(choice))appActions.batchManage(catalog,spaceFilter,this::refresh);
        }).show();
    }
    private void selectSpace(){
        if(catalog==null||catalog.spaces.isEmpty()){createSpace();return;}
        List<String> items=new ArrayList<>();items.add("全部空间");for(String space:catalog.spaces)items.add(app.spaces.name(space));items.add("＋ 新建空间");
        new AlertDialog.Builder(this).setTitle("只看某个空间的应用").setItems(items.toArray(String[]::new),(dialog,index)->{
            if(index==0){spaceFilter="";render();}
            else if(index==items.size()-1)createSpace();
            else{spaceFilter=catalog.spaces.get(index-1);render();}
        }).show();
    }
    private void render(){
        if(body==null||destroyed)return;
        String page=application!=null?"app:"+application:"tab:"+tab;int y=page.equals(renderedPage)?scroll.getScrollY():0;renderedPage=page;
        if(globalAgent!=null){globalAgent.dispose();globalAgent=null;}body.removeAllViews();nav.removeAllViews();nav.setVisibility(application==null?View.VISIBLE:View.GONE);
        spaceChip.setVisibility(application==null&&tab==0&&catalog!=null&&catalog.spaces.size()>1?View.VISIBLE:View.GONE);spaceChip.setText((spaceFilter.isEmpty()?"全部空间":app.spaces.name(spaceFilter))+" ▾");spaceChip.setContentDescription("空间筛选："+(spaceFilter.isEmpty()?"全部空间":app.spaces.name(spaceFilter)));
        for(int i=0;i<TABS.length;i++){final int index=i;LinearLayout item=WorkbenchLibraryUi.navItem(this,TAB_ICONS[i],TABS[i],tab==i,()->{tab=index;render();});item.setContentDescription(TABS[i]+"标签");nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));}
        title.setText(application!=null?(catalog==null?application:catalog.appName(app,application)):tab==0?"Tripoder":TABS[tab]);
        try{if(application!=null)renderApp();else if(tab==0)renderApps();else renderSettings();}catch(Exception error){WorkbenchLibraryUi.copy(body,"读取失败："+UiText.error(error));}
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private boolean waiting(){
        if(catalog!=null)return false;
        if(engine.ready())WorkbenchLibraryUi.copy(body,"正在读取…");
        else if(engine.failure.isEmpty())body.addView(ProductUi.empty(this,"box","正在启动运行引擎","引擎就绪后会显示你的应用。",null,null));
        else body.addView(ProductUi.empty(this,"box","运行引擎没有连接",engine.failure,"重新连接",()->engine.connect(this)));
        return true;
    }
    private void renderApps()throws Exception{
        if(waiting())return;
        var all=app.projects.all();var visibleApps=catalog.applications(spaceFilter);
        LinearLayout header=ProductUi.row(this);header.setPadding(d(20),d(10),d(20),d(6));
        header.addView(ProductUi.text(this,visibleApps.isEmpty()?"":visibleApps.size()+" 个应用 · 轻点运行，长按分析与管理",13,ProductUi.MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
        if(app.agents.listening)header.addView(ProductUi.chip(this,"AI 入口已开启",ProductUi.GREEN,ProductUi.TINT));
        ProductUi.add(body,header,0);
        if(visibleApps.isEmpty()){
            if(spaceFilter.isEmpty())body.addView(ProductUi.empty(this,"grid","还没有应用","点下方「添加应用」，从手机里已安装的应用或 APK 文件中选择。应用会在 Tripoder 的独立空间中运行，不影响手机上的原应用。",null,null));
            else WorkbenchLibraryUi.copy(body,"这个空间还没有应用。");
        }
        List<View> tiles=new ArrayList<>();
        for(String pkg:visibleApps){
            List<JSONObject> projects=catalog.projects(all,pkg);int count=projects.size();var installs=catalog.installations(pkg);Bundle cached=installs.isEmpty()?null:app.presentations.get(installs.get(0).optString("generation"));Drawable icon=app.presentations.iconDrawable(this,cached);
            boolean busy=app.operations.busyPackage(pkg);for(JSONObject project:projects)busy|=app.operations.busyProject(project.optString("id"));
            String name=catalog.appName(app,pkg),detail=busy?"正在启动…":installs.isEmpty()?"未安装 · 脚本保留":"";
            var tile=new WorkbenchLibraryUi.Tile(this,name,detail,icon,false,()->appActions.launch(catalog,pkg,spaceFilter));tile.packageName(pkg);
            tile.setOnLongClickListener(v->{appActions.menu(catalog,pkg,catalog.appName(app,pkg));return true;});
            tile.setContentDescription(name+(busy?"，正在启动":""));if(cached==null)loadPresentation(pkg,tile);tiles.add(tile);
        }
        var add=new WorkbenchLibraryUi.Tile(this,"添加应用","",new ProductUi.Icon("plus",ProductUi.ACCENT),false,this::addApplication);add.setContentDescription("添加应用");tiles.add(add);WorkbenchLibraryUi.grid(body,tiles);
    }
    private void renderApp()throws Exception{
        if(waiting())return;
        var installs=catalog.installations(application);Bundle cached=installs.isEmpty()?null:app.presentations.get(installs.get(0).optString("generation"));Drawable icon=app.presentations.iconDrawable(this,cached);
        LinearLayout hero=ProductUi.row(this);hero.setPadding(d(20),d(12),d(20),d(8));ImageView image=new ImageView(this);image.setImageDrawable(icon==null?new ProductUi.Icon("grid",ProductUi.MUTED):icon);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);hero.addView(image,new LinearLayout.LayoutParams(d(56),d(56)));
        LinearLayout copy=ProductUi.column(this);ProductUi.add(copy,ProductUi.text(this,catalog.appName(app,application),18,ProductUi.INK,true),0);ProductUi.add(copy,ProductUi.text(this,application,12,ProductUi.MUTED,false),4);
        List<String> spaces=new ArrayList<>();for(JSONObject install:installs)spaces.add(app.spaces.name(install.optString("space")));ProductUi.add(copy,ProductUi.text(this,installs.isEmpty()?"应用已不在任何空间中，项目仍然保留":"安装在 "+String.join("、",spaces),12,ProductUi.MUTED,false),4);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=d(14);hero.addView(copy,lp);ProductUi.add(body,hero,0);
        if(cached==null&&!installs.isEmpty())loadPresentation(application,null);
        ProductUi.add(body,ProductUi.button(this,"分析",true,()->openAnalysis(application)),12);
    }
    private void openAnalysis(String pkg){String id=catalog.project(pkg);if(id==null){ProductUi.notice(this,"项目正在初始化","请刷新后重试。");return;}startActivity(new Intent(this,WorkbenchProjectActivity.class).putExtra("project",id));}
    private void renderSettings(){
        globalAgent=new WorkbenchAgentPanel(this,app,"",body);
        ProductUi.add(body,ProductUi.button(this,"历史脚本与恢复",false,()->startActivity(new Intent(this,WorkbenchArchivesActivity.class))),12);
        WorkbenchLibraryUi.section(body,"运行引擎");
        LinearLayout box=ProductUi.column(this);box.setPadding(d(16),0,d(16),0);LinearLayout card=ProductUi.card(this);
        LinearLayout row=ProductUi.row(this);row.addView(ProductUi.text(this,engine.ready()?"已连接":"未连接",15,ProductUi.INK,true),new LinearLayout.LayoutParams(0,-2,1));
        row.addView(ProductUi.chip(this,engine.ready()?"正常":"需要处理",engine.ready()?ProductUi.GREEN:ProductUi.WARN,engine.ready()?ProductUi.TINT:ProductUi.WARN_BG));card.addView(row);
        if(!engine.failure.isEmpty())ProductUi.add(card,ProductUi.text(this,engine.failure,12,ProductUi.MUTED,false),8);
        ProductUi.add(card,ProductUi.button(this,engine.ready()?"刷新应用":"重新连接",false,()->{if(engine.ready())refresh();else engine.connect(this);}),12);ProductUi.add(box,card,0);ProductUi.add(body,box,0);
        if(app.operations.pending()){
            WorkbenchLibraryUi.section(body,"未确认的操作");LinearLayout pending=ProductUi.card(this);
            ProductUi.add(pending,ProductUi.text(this,app.operations.pendingIds().size()+" 项操作的结果还没有确认。通常刷新即可；如果启动一直卡住，可以结束等待。",13,ProductUi.INK,false),0);
            ProductUi.add(pending,ProductUi.button(this,"刷新结果",true,()->app.operations.query()),12);
            ProductUi.add(pending,ProductUi.button(this,"结束卡住的启动",false,()->ProductUi.confirm(this,"结束卡住的启动？","引擎会先确认这些启动没有真正开始。确认后结束等待，之后可以重新启动应用；不会重试原来的启动。","结束等待",false,()->app.operations.acknowledgeUnknown(null))),8);
            ProductUi.add(pending,ProductUi.button(this,"取消尚未开始的操作",false,()->ProductUi.confirm(this,"取消尚未开始的操作？","只有引擎还没有接收的操作会被取消；已经开始的操作仍需等待结果。","取消操作",false,()->app.operations.cancelIfNotAccepted())),8);
            LinearLayout wrap=ProductUi.column(this);wrap.setPadding(d(16),0,d(16),0);ProductUi.add(wrap,pending,0);ProductUi.add(body,wrap,0);
        }
        WorkbenchLibraryUi.section(body,"空间");
        WorkbenchLibraryUi.copy(body,"每个应用在每个空间里都有自己的数据；同一空间里的应用可以互相访问。需要互不影响的账号或数据时，把应用添加到不同空间。");
        if(catalog!=null){LinearLayout list=ProductUi.column(this);for(String space:catalog.spaces){long count=catalog.installs.stream().filter(i->space.equals(i.optString("space"))).count();list.addView(ProductUi.entry(this,"box",app.spaces.name(space),count+" 个应用 · 轻点管理",()->manageSpace(space)));}ProductUi.add(body,list,0);}
        LinearLayout add=ProductUi.column(this);add.setPadding(d(16),d(4),d(16),0);ProductUi.add(add,ProductUi.button(this,"＋ 新建空间",false,this::createSpace),0);ProductUi.add(body,add,0);
        WorkbenchLibraryUi.section(body,"关于");
        WorkbenchLibraryUi.copy(body,"Tripoder 在独立空间中运行应用。应用和数据由运行引擎保存；项目脚本保存在工作台中，不会自动部署。");
    }
    private void addApplication(){if(!engine.ready()){ProductUi.notice(this,"暂时不能添加","运行引擎还没有连接，请稍后再试。");return;}startActivity(new Intent(this,WorkbenchAddActivity.class));}
    private void createSpace(){if(!engine.ready()){ProductUi.notice(this,"暂时不能新建","运行引擎还没有连接，请稍后再试。");return;}
        LinearLayout form=ProductUi.card(this);EditText input=ProductUi.field(form,"空间名称","例如：工作、测试账号","",false);input.setContentDescription("空间名称");
        ProductUi.add(form,ProductUi.text(this,"不同空间中的同一个应用拥有各自独立的数据。",12,ProductUi.MUTED,false),10);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("新建空间").setView(form).setNegativeButton("取消",null).setPositiveButton("创建",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{String name=input.getText().toString().trim();if(name.isEmpty()||name.length()>24){input.setError("请输入 1–24 个字符的名称");return;}try{app.spaces.create(app.operations,name);dialog.dismiss();}catch(Exception error){input.setError(UiText.error(error));}}));dialog.show();}
    /** The last space stays: without one nothing can be added, and a fresh default would only be created again. */
    private void manageSpace(String space){
        if(catalog==null||catalog.spaces.size()<2){renameSpace(space);return;}
        new AlertDialog.Builder(this).setTitle(app.spaces.name(space)).setItems(new String[]{"重命名","删除空间"},(dialog,index)->{if(index==0)renameSpace(space);else confirmDeleteSpace(space);}).show();
    }
    /** Every application in the space, its data and the projects bound to it go; the confirmation states exactly that before anything is sent. */
    private void confirmDeleteSpace(String space){
        String name=app.spaces.name(space);long apps=catalog.installs.stream().filter(i->space.equals(i.optString("space"))).count(),projects=catalog.bindings.values().stream().filter(ref->space.equals(ref.optString("space"))).count();
        String consequence=(apps>0?"将删除这个空间中的 "+apps+" 个应用及其全部数据":"这个空间中没有应用")+"。应用脚本会保留"+"。无法恢复。";
        ProductUi.confirm(this,"删除空间「"+name+"」？",consequence,"删除",true,()->{
            if(!engine.ready()){ProductUi.notice(this,"暂时不能删除","运行引擎还没有连接，请稍后再试。");return;}
            try{app.operations.submit(ControlWire.request("deleteSpace","operation",UUID.randomUUID().toString(),"space",space),"删除空间「"+name+"」",null);}
            catch(Exception error){ProductUi.notice(this,"没有删除",UiText.error(error));}
        });
    }
    private void renameSpace(String space){
        LinearLayout form=ProductUi.card(this);EditText input=ProductUi.field(form,"空间名称","",app.spaces.name(space),false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("重命名空间").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{String name=input.getText().toString().trim();if(name.isEmpty()||name.length()>24){input.setError("请输入 1–24 个字符的名称");return;}app.spaces.rename(space,name);dialog.dismiss();render();}));dialog.show();
    }
    private void loadPresentation(String pkg,WorkbenchLibraryUi.Tile tile){
        var installs=catalog.installations(pkg);if(installs.isEmpty()){if(tile!=null)tile.detail("应用未安装 · 项目保留");return;}JSONObject install=installs.get(0);String generation=install.optString("generation");Bundle cached=app.presentations.get(generation);if(cached!=null){applyPresentation(tile,cached);return;}
        if(!presentationRequests.add(generation))return;String current=engine.lease;
        engine.execute(ControlWire.request("appPresentation","space",install.optString("space"),"package",pkg,"generation",generation),(value,error)->{presentationRequests.remove(generation);if(destroyed||!current.equals(engine.lease))return;if(error!=null){if(tile!=null)tile.detail("应用信息读取失败");return;}app.presentations.put(generation,value);if(tile==null||application!=null)render();else applyPresentation(tile,value);});
    }
    private void applyPresentation(WorkbenchLibraryUi.Tile tile,Bundle value){if(tile==null)return;String label=value.getString("label");tile.name.setText(label);CharSequence description=tile.getContentDescription();if(description!=null){String text=description.toString();int comma=text.indexOf('，');tile.setContentDescription(label+(comma<0?"":text.substring(comma)));}Drawable icon=app.presentations.iconDrawable(this,value);if(icon!=null)tile.icon.setImageDrawable(icon);}
    private void show(Exception error){app.operations.inform(UiText.error(error));}
    @Override protected void onDestroy(){destroyed=true;if(globalAgent!=null)globalAgent.dispose();engine.remove(this);app.operations.remove(operationObserver);super.onDestroy();}
}
