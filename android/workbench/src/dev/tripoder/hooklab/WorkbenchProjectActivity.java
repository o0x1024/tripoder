package dev.tripoder.hooklab;

import android.app.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One project in three places: the script, its run target with deployment, and external Agent control. Activation needs a worker ACK. */
public final class WorkbenchProjectActivity extends Activity implements EngineConnection.Listener {
    private static final String[] TABS={"脚本","运行与部署","外部 Agent"};
    private WorkbenchApplication app;private String id,name="",savedSource="",lease="";private EditText editor;
    private TextView binding,meta,saveNote,recoveryText,title;private Button bind,save,launch;private LinearLayout tabBar,recovery;private View[] pages;private ProductUi.Status status;
    private WorkbenchCatalog catalog;private WorkbenchHookPanel hooks;private WorkbenchAgentPanel agents;
    private boolean destroyed,loading,tabDirty;private int settled,tab;private final Runnable operationObserver=this::operationChanged;
    @Override public void onCreate(Bundle saved){
        ProductUi.theme(this);super.onCreate(saved);app=(WorkbenchApplication)getApplication();
        try{
            id=WorkbenchProjects.id(getIntent().getStringExtra("project"));JSONObject project=app.projects.get(id);name=project.getString("name");savedSource=app.projects.script(id);tab=saved==null?0:saved.getInt("tab",0);
            LinearLayout root=ProductUi.root(this),toolbar=ProductUi.toolbar(this,name,true);title=ProductUi.title(toolbar);
            toolbar.addView(ProductUi.iconButton(this,"more","更多",this::more),new LinearLayout.LayoutParams(d(48),d(48)));root.addView(toolbar);
            LinearLayout tabWrap=ProductUi.column(this);tabWrap.setPadding(d(16),d(2),d(16),d(6));tabBar=ProductUi.column(this);tabWrap.addView(tabBar);root.addView(tabWrap);
            status=new ProductUi.Status(this);status.view.setOnClickListener(v->app.operations.query());root.addView(status.view);
            FrameLayout container=new FrameLayout(this);root.addView(container,new LinearLayout.LayoutParams(-1,0,1));
            pages=new View[]{scriptPage(saved),deployPage(),agentPage()};for(View page:pages)container.addView(page,new FrameLayout.LayoutParams(-1,-1));
            showTab(tab);app.operations.observe(operationObserver);app.engine.observe(this);operationChanged();
        }catch(Exception error){ProductUi.notice(this,"无法打开项目",UiText.error(error));}
    }
    private int d(int n){return ProductUi.dp(this,n);}
    /** The editor owns the page height and scrolls itself, so a long script never pushes other controls away. */
    private View scriptPage(Bundle saved){
        LinearLayout page=ProductUi.column(this);page.setPadding(d(16),d(4),d(16),d(12));
        editor=ProductUi.code(ProductUi.input(this,"在这里编写 Tripoder 脚本",saved==null?savedSource:saved.getString("source",savedSource),true));editor.setContentDescription("项目脚本编辑器");editor.setGravity(Gravity.TOP|Gravity.START);
        android.graphics.drawable.GradientDrawable bg=ProductUi.shape(ProductUi.SURFACE,14,this);bg.setStroke(d(1),ProductUi.LINE);editor.setBackground(bg);
        page.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout row=ProductUi.row(this);row.setPadding(d(4),d(8),d(4),0);meta=ProductUi.text(this,"",12,ProductUi.MUTED,false);row.addView(meta,new LinearLayout.LayoutParams(0,-2,1));saveNote=ProductUi.text(this,"",12,ProductUi.MUTED,false);row.addView(saveNote);page.addView(row);
        save=ProductUi.button(this,"脚本已保存",true,this::save);ProductUi.add(page,save,10);
        editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){updateSave();}public void afterTextChanged(Editable s){}});updateSave();
        return page;
    }
    private View deployPage(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout page=ProductUi.column(this);page.setPadding(d(16),d(4),d(16),d(28));scroll.addView(page);
        LinearLayout target=ProductUi.card(this);ProductUi.add(target,ProductUi.label(this,"运行目标"),0);binding=ProductUi.text(this,"正在确认运行目标…",15,ProductUi.INK,true);ProductUi.add(target,binding,10);
        bind=ProductUi.button(this,"选择运行目标",false,this::chooseTarget);launch=ProductUi.button(this,"启动应用",true,this::launch);ProductUi.pair(target,bind,launch);ProductUi.add(page,target,0);
        recovery=ProductUi.card(this);recovery.setBackground(ProductUi.shape(ProductUi.WARN_BG,18,this));recoveryText=ProductUi.text(this,"",13,ProductUi.WARN,false);recovery.addView(recoveryText);
        ProductUi.pair(recovery,ProductUi.button(this,"刷新结果",false,()->app.operations.query()),ProductUi.button(this,"结束等待",false,()->ProductUi.confirm(this,"结束等待？","引擎会先确认这次启动没有真正开始、应用也没有在运行。确认后可以重新启动；不会重试原来的启动。","结束等待",false,()->app.operations.acknowledgeUnknown(id))));
        recovery.setVisibility(View.GONE);ProductUi.add(page,recovery,12);
        hooks=new WorkbenchHookPanel(this,app,id,()->editor.getText().toString(),page);
        return scroll;
    }
    private View agentPage(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout page=ProductUi.column(this);page.setPadding(d(16),d(4),d(16),d(28));scroll.addView(page);
        agents=new WorkbenchAgentPanel(this,app,id,page);return scroll;
    }
    private void showTab(int index){
        tab=index;for(int i=0;i<pages.length;i++)pages[i].setVisibility(i==index?View.VISIBLE:View.GONE);
        String[] labels=TABS.clone();if(dirty())labels[0]="脚本 •";
        tabBar.removeAllViews();tabBar.addView(ProductUi.tabs(this,labels,index,this::selectTab));
        if(index!=0&&editor!=null){var input=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);input.hideSoftInputFromWindow(editor.getWindowToken(),0);}
    }
    private void selectTab(int index){if(index!=tab)showTab(index);}
    private void more(){
        new AlertDialog.Builder(this).setItems(new String[]{"重命名项目","处理旧的启动入口","删除项目"},(dialog,index)->{
            if(index==0)WorkbenchProjectActions.rename(this,app,id,name,this::reloadName);
            else if(index==1)WorkbenchEntryClosure.show(this,app,id);
            else WorkbenchProjectActions.delete(this,app,catalog,id,name,()->{if(!exists())super.finish();});
        }).show();
    }
    private void reloadName(){try{name=app.projects.get(id).getString("name");title.setText(name);}catch(Exception error){status.show(UiText.error(error),false);}}
    private boolean exists(){try{app.projects.get(id);return true;}catch(Exception gone){return false;}}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(editor!=null)out.putString("source",editor.getText().toString());out.putInt("tab",tab);}
    @Override protected void onResume(){super.onResume();if(binding!=null&&app!=null&&app.engine.ready()){app.operations.query();refresh();}}
    @Override public void changed(){if(destroyed||binding==null)return;updateActions();if(!app.engine.ready()){catalog=null;lease="";binding.setText(app.engine.failure.isEmpty()?"正在连接运行引擎…":"运行引擎未连接："+app.engine.failure);return;}if(!lease.equals(app.engine.lease)){lease=app.engine.lease;refresh();}}
    private boolean bound(){return catalog!=null&&catalog.bindings.containsKey(id)&&"COMMITTED".equals(catalog.bindings.get(id).optString("state"));}
    private void updateActions(){boolean busy=app.operations.busyProject(id);bind.setEnabled(!busy&&app.engine.ready());launch.setEnabled(!busy&&app.engine.ready()&&bound());launch.setText(busy?"正在处理…":"启动应用");}
    private void operationChanged(){
        if(destroyed||status==null)return;
        // A deletion receipt removes the source; the page has nothing left to show.
        if(!exists()){super.finish();return;}
        status.show(app.operations.message,app.operations.busy());int launches=app.operations.pendingLaunches(id);
        recovery.setVisibility(launches>0?View.VISIBLE:View.GONE);recoveryText.setText(launches+" 次启动的结果还没有确认。通常刷新即可；如果应用其实没有打开，可以结束等待后重新启动。");
        updateActions();boolean completed=settled!=app.operations.settled;settled=app.operations.settled;if(completed)refresh();
    }
    private void refresh(){
        if(loading||!app.engine.ready())return;loading=true;String current=app.engine.lease;
        app.engine.execute(ControlWire.request("catalog"),(value,error)->{loading=false;if(destroyed||!current.equals(app.engine.lease))return;if(error!=null){show(error);return;}try{
            catalog=new WorkbenchCatalog(value);JSONObject ref=catalog.bindings.get(id);bind.setText(ref==null?"选择运行目标":"更换目标");updateActions();
            if(ref==null){binding.setText("还没有选择运行目标\n选择一个已添加的应用后，才能启动和部署脚本。");return;}
            String appName=catalog.appName(app,ref.getString("package")),space=app.spaces.name(ref.getString("space"));
            if(!"COMMITTED".equals(ref.getString("state"))){
                JSONObject successor=successor(ref);
                if(successor!=null){bind.setText("改用新版本");binding.setText(appName+"\n应用已更新（空间："+space+" · 版本 "+successor.optLong("version")+"），确认后继续使用。");}
                else if(!catalog.installations(ref.getString("package")).isEmpty()){bind.setText("更换空间");binding.setText(appName+"\n应用已从「"+space+"」移除，可以改到它所在的其他空间。");}
                else binding.setText(appName+"\n应用已从所有空间移除。重新添加这个应用后可以继续使用本项目。");
                return;
            }
            binding.setText(appName+"\n空间："+space+" · 正在确认…");app.engine.execute(ControlWire.request("resolveProject","project",id),(target,failure)->{if(destroyed||!current.equals(app.engine.lease))return;if(failure!=null){binding.setText(appName+"\n"+UiText.error(failure));return;}binding.setText(appName+"\n空间："+space+" · 已就绪");});
        }catch(Exception invalid){show(invalid);}});
    }
    /** The same application's current installation in the bound space, once the bound one was updated. */
    private JSONObject successor(JSONObject ref){
        for(JSONObject install:catalog.installations(ref.optString("package")))if(ref.optString("space").equals(install.optString("space"))&&!ref.optString("generation").equals(install.optString("generation")))return install;
        return null;
    }
    /** A bound project only moves within its application (another space or a newer version); an unbound one may pick any application.
     *  An update in the bound space is offered as a single confirmation. */
    private void chooseTarget(){
        if(!app.engine.ready()){app.engine.connect(this);return;}if(catalog==null){refresh();return;}
        JSONObject prior=catalog.bindings.get(id);List<JSONObject> choices=new ArrayList<>();
        if(prior==null)choices.addAll(catalog.committed());
        else{
            String space=prior.optString("space"),generation=prior.optString("generation");
            for(JSONObject install:catalog.installations(prior.optString("package")))if(!generation.equals(install.optString("generation")))choices.add(install);
            choices.sort(Comparator.comparing(install->!space.equals(install.optString("space"))));
        }
        if(choices.isEmpty()){
            if(prior==null)ProductUi.notice(this,"还没有可以选择的应用","请先回到应用页添加一个应用。");
            else ProductUi.notice(this,"没有可以更换的目标",catalog.appName(app,prior.optString("package"))+" 不在其他空间中，也没有新版本。换一个应用请新建项目。");
            return;
        }
        if(choices.size()==1||(prior!=null&&successor(prior)!=null)){confirmTarget(choices.get(0),prior);return;}
        String[] labels=choices.stream().map(install->WorkbenchCatalog.label(app,install)).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(prior==null?"选择运行目标":"换到哪个空间？").setItems(labels,(dialog,index)->confirmTarget(choices.get(index),prior)).show();
    }
    private void confirmTarget(JSONObject install,JSONObject prior){
        ProductUi.confirm(this,"使用这个运行目标？",WorkbenchCatalog.label(app,install)+"\n\n应用以后更新时，需要回到这里重新确认。保存的脚本不会自动部署。","确认",false,()->{
            try{Bundle input=ControlWire.request("bindProject","operation",UUID.randomUUID().toString(),"project",id,"space",install.getString("space"),"package",install.getString("package"),"generation",install.getString("generation"));if(prior!=null)input.putString("expectedBinding",prior.getString("bindingOperation"));app.operations.submit(input,"设置运行目标",null);}catch(Exception error){show(error);}
        });
    }
    private void launch(){
        launch.setEnabled(false);app.engine.execute(ControlWire.request("launchTargets","project",id),(target,error)->{
            if(destroyed)return;updateActions();if(error!=null){WorkbenchAppActions.launchFailed(this,app,id,error);return;}try{
                JSONArray array=new JSONArray(target.getString("targets"));List<JSONObject> choices=new ArrayList<>(),launchers=new ArrayList<>();for(int i=0;i<array.length();i++){JSONObject value=array.getJSONObject(i);choices.add(value);if(value.optBoolean("launcher")&&value.optBoolean("supported"))launchers.add(value);}choices.sort(Comparator.comparing(value->!value.optBoolean("launcher")));
                if(launchers.size()==1){start(target,launchers.get(0));return;}
                if(choices.isEmpty()){ProductUi.notice(this,"没有可以打开的界面","这个应用没有允许直接打开的界面。");return;}
                String[] labels=choices.stream().map(value->WorkbenchAppActions.componentLabel(value)+(value.optBoolean("supported")?"":"\n暂不支持："+UiText.detail(value.optString("reason")))).toArray(String[]::new);
                new AlertDialog.Builder(this).setTitle("打开哪个界面？").setItems(labels,(dialog,index)->{
                    JSONObject component=choices.get(index);if(!component.optBoolean("supported")){ProductUi.notice(this,"这个界面暂时不能打开",UiText.detail(component.optString("reason")));return;}start(target,component);
                }).show();
            }catch(Exception invalid){show(invalid);}
        });
    }
    private void start(Bundle target,JSONObject component){
        try{Bundle input=ControlWire.request("prepareLaunch","operation",UUID.randomUUID().toString(),"project",id,"bindingOperation",target.getString("bindingOperation"),"generation",target.getString("generation"),"component",component.getString("component"));
            app.operations.submit(input,"启动应用",null,(prepared,failure)->{
                if(failure!=null){WorkbenchAppActions.launchFailed(this,app,id,failure);return;}
                if(prepared.getBoolean("woken",false))return;
                WorkbenchAppActions.enter(this,app,prepared,!destroyed&&!isFinishing()&&WorkbenchAppActions.visible(),"应用已准备好，但项目页不在前台，没有打开界面。请回到项目页后重新启动。");
            });
        }catch(Exception rejected){WorkbenchAppActions.launchFailed(this,app,id,rejected);}
    }
    private boolean dirty(){return editor!=null&&!editor.getText().toString().equals(savedSource);}
    private void updateSave(){
        if(hooks!=null)hooks.sourceChanged();if(save==null)return;boolean dirty=dirty();save.setText(dirty?"保存脚本 · 有未保存修改":"脚本已保存");save.setEnabled(dirty);
        String text=editor.getText().toString();int lines=1;for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')lines++;int bytes=text.getBytes(StandardCharsets.UTF_8).length;
        meta.setText(lines+" 行 · "+String.format(Locale.ROOT,"%.1f",bytes/1024f)+" / 64 KiB");meta.setTextColor(bytes>65536?ProductUi.DANGER:ProductUi.MUTED);
        if(dirty!=tabDirty){tabDirty=dirty;if(pages!=null)showTab(tab);}
    }
    private void save(){try{String source=editor.getText().toString();app.projects.script(id,source);savedSource=source;updateSave();saveNote.setText("已保存");}catch(Exception error){saveNote.setText("");ProductUi.notice(this,"脚本没有保存",UiText.error(error));}}
    private void show(Exception error){if(status!=null)status.show(UiText.error(error),false);}
    @Override public void finish(){if(dirty()){new AlertDialog.Builder(this).setTitle("脚本还有未保存的修改").setNegativeButton("继续编辑",null).setNeutralButton("放弃修改",(d,w)->super.finish()).setPositiveButton("保存并返回",(d,w)->{save();if(!dirty())super.finish();}).show();return;}super.finish();}
    @Override protected void onDestroy(){destroyed=true;if(hooks!=null)hooks.dispose();if(agents!=null)agents.dispose();if(app!=null){app.engine.remove(this);app.operations.remove(operationObserver);}super.onDestroy();}
}
