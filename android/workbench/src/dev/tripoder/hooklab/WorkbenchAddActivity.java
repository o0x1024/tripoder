package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.content.pm.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import java.util.*;

/** Multiple application selection with a serial import batch. Copies the complete installed APK set, never launches the host app. */
public final class WorkbenchAddActivity extends Activity implements EngineConnection.Listener {
    private static final int APK=4102;
    /** Label and icon are read once, off the main thread; redraws only filter. */
    private record PhoneApp(String pkg,String label,Drawable icon){}
    private WorkbenchApplication app;private WorkbenchCatalog catalog;private LinearLayout body,tabs,searchBox;private ScrollView scroll;private EditText search;
    private TextView install,spaceView;private ProductUi.Status status;private final Set<String> selected=new LinkedHashSet<>();private String space,importSpace,initialMessage,handledBatch="",query="";private int tab;
    private boolean destroyed,wasBusy,loading;private int settled;private final List<PhoneApp> phoneApps=new ArrayList<>();
    private final Runnable observer=this::operationChanged;
    @Override public void onCreate(Bundle saved){
        ProductUi.theme(this);super.onCreate(saved);app=(WorkbenchApplication)getApplication();initialMessage=app.operations.message;if(saved!=null){space=saved.getString("space");var selections=saved.getStringArrayList("selectedPackages");if(selections!=null)selected.addAll(selections);else if(saved.getString("selected")!=null)selected.add(saved.getString("selected"));handledBatch=saved.getString("handledBatch","");importSpace=saved.getString("importSpace");tab=saved.getInt("tab");query=saved.getString("query","");}
        LinearLayout root=ProductUi.root(this);root.addView(ProductUi.toolbar(this,"添加应用",true));
        spaceView=ProductUi.text(this,"",13,ProductUi.MUTED,true);spaceView.setMinHeight(d(40));spaceView.setGravity(Gravity.CENTER_VERTICAL);spaceView.setPadding(d(20),0,d(20),0);spaceView.setBackground(new RippleDrawable(ColorStateList.valueOf(ProductUi.TINT),null,new ColorDrawable(0xffffffff)));spaceView.setOnClickListener(v->chooseSpace());root.addView(spaceView);
        LinearLayout tabWrap=ProductUi.column(this);tabWrap.setPadding(d(16),d(4),d(16),d(8));tabs=ProductUi.column(this);tabWrap.addView(tabs);root.addView(tabWrap);
        searchBox=ProductUi.column(this);searchBox.setPadding(d(16),0,d(16),d(4));search=ProductUi.input(this,"搜索应用名称或包名",query,false);search.setContentDescription("搜索应用");search.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setCompoundDrawablePadding(d(8));Drawable glass=new ProductUi.Icon("search",ProductUi.MUTED);glass.setBounds(0,0,d(18),d(18));search.setCompoundDrawablesRelative(glass,null,null,null);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable s){if(!query.equals(s.toString())){query=s.toString();render();}}});
        searchBox.addView(search);root.addView(searchBox);
        status=new ProductUi.Status(this);root.addView(status.view);
        scroll=new ScrollView(this);scroll.setFillViewport(true);body=ProductUi.column(this);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        install=ProductUi.button(this,"安装",true,this::submit);install.setContentDescription("安装所选应用");
        LinearLayout.LayoutParams bottom=new LinearLayout.LayoutParams(-1,d(52));bottom.setMargins(d(16),d(10),d(16),d(12));root.addView(install,bottom);
        app.operations.observe(observer);app.imports.batch.observe(observer);app.engine.observe(this);operationChanged();readPhoneApps();refresh();
    }
    private int d(int n){return ProductUi.dp(this,n);}
    private void readPhoneApps(){
        loading=true;render();new Thread(()->{List<PhoneApp> found=new ArrayList<>();Exception failure=null;
            try{PackageManager pm=getPackageManager();Set<String> seen=new HashSet<>();for(ResolveInfo result:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)){var info=result.activityInfo.applicationInfo;if(!info.packageName.startsWith("dev.tripoder.")&&seen.add(info.packageName))found.add(new PhoneApp(info.packageName,info.loadLabel(pm).toString(),info.loadIcon(pm)));}found.sort(Comparator.comparing(PhoneApp::label,String.CASE_INSENSITIVE_ORDER));}catch(Exception error){failure=error;}
            Exception error=failure;runOnUiThread(()->{if(destroyed)return;loading=false;if(error!=null){status.show("读取手机应用失败："+UiText.error(error),false);render();return;}phoneApps.clear();phoneApps.addAll(found);Set<String> available=new HashSet<>();for(PhoneApp entry:found)available.add(entry.pkg);selected.retainAll(available);render();});
        },"workbench-app-list").start();
    }
    @Override public void changed(){if(destroyed)return;if(!app.engine.ready()){catalog=null;render();}else refresh();}
    private void refresh(){if(!app.engine.ready())return;String lease=app.engine.lease;app.engine.execute(ControlWire.request("catalog"),(value,error)->{if(destroyed||!lease.equals(app.engine.lease))return;try{if(error!=null)throw error;catalog=new WorkbenchCatalog(value);catalog.sync(app);if(space==null&&!catalog.spaces.isEmpty())space=catalog.spaces.contains(WorkbenchSpaces.DEFAULT)?WorkbenchSpaces.DEFAULT:catalog.spaces.get(0);if(!catalog.spaces.contains(space))space=null;app.spaces.ensureDefault(catalog,app.operations,app.engine);render();}catch(Exception invalid){status.show("读取空间失败："+UiText.error(invalid),false);}});}
    private void chooseSpace(){if(catalog==null||catalog.spaces.isEmpty())return;new AlertDialog.Builder(this).setTitle("安装到哪个空间？").setItems(catalog.spaces.stream().map(app.spaces::name).toArray(String[]::new),(dialog,index)->{space=catalog.spaces.get(index);render();}).show();}
    private boolean busy(){return app.imports.preparing||app.operations.busyUnscoped()||app.imports.batch.active();}
    private boolean installed(String pkg){if(catalog==null)return false;return catalog.installs.stream().anyMatch(value->pkg.equals(value.optString("package"))&&Objects.equals(space,value.optString("space")));}
    private String label(String pkg){for(PhoneApp entry:phoneApps)if(entry.pkg.equals(pkg))return entry.label;return pkg;}
    private void render(){
        if(body==null||destroyed)return;int y=scroll.getScrollY();body.removeAllViews();tabs.removeAllViews();
        if(!app.engine.ready())spaceView.setText("运行引擎未连接，暂时不能安装");
        else if(catalog==null)spaceView.setText("正在读取空间…");
        else if(space==null)spaceView.setText("正在准备默认空间…");
        else spaceView.setText("安装到："+app.spaces.name(space)+(catalog.spaces.size()>1?"  ▾":""));
        spaceView.setContentDescription(spaceView.getText()+(catalog!=null&&catalog.spaces.size()>1?"，轻点切换空间":""));spaceView.setClickable(catalog!=null&&catalog.spaces.size()>1);
        tabs.addView(ProductUi.tabs(this,new String[]{"手机应用","APK 文件"},tab,index->{tab=index;render();}));
        if(!app.imports.batch.active())selected.removeIf(this::installed);
        if(app.imports.batch.hasResults()){WorkbenchLibraryUi.copy(body,app.imports.batch.summary());ProductUi.add(body,ProductUi.button(this,"查看添加结果",false,()->ProductUi.notice(this,"添加结果",app.imports.batch.details())),0);}
        if(app.imports.batch.active())ProductUi.add(body,ProductUi.button(this,"放弃本批",false,()->new AlertDialog.Builder(this).setTitle("放弃本批添加？").setMessage("未完成的应用会标记为“结果待确认”，不会自动重试。请稍后在应用列表中核对。").setPositiveButton("放弃",(dialog,which)->app.imports.batch.abandon()).setNegativeButton("取消",null).show()),0);
        searchBox.setVisibility(tab==0?View.VISIBLE:View.GONE);
        if(tab==1){body.addView(ProductUi.empty(this,"file","从文件添加","可以一次选择多个独立 APK，逐个添加到当前空间。每个应用只保留一个项目。","选择 APK 文件",this::chooseFile));}
        else if(loading)WorkbenchLibraryUi.copy(body,"正在读取手机应用…");
        else{
            String filter=query.trim().toLowerCase(Locale.ROOT);List<View> tiles=new ArrayList<>();
            List<String> visible=new ArrayList<>();for(PhoneApp entry:phoneApps)if(!installed(entry.pkg)&&(filter.isEmpty()||entry.label.toLowerCase(Locale.ROOT).contains(filter)||entry.pkg.toLowerCase(Locale.ROOT).contains(filter)))visible.add(entry.pkg);
            LinearLayout selectionBar=ProductUi.row(this);selectionBar.setPadding(d(16),0,d(16),d(8));TextView count=ProductUi.text(this,"已选 "+selected.size()+" 个",13,ProductUi.MUTED,true);selectionBar.addView(count,new LinearLayout.LayoutParams(0,-2,1));boolean all=!visible.isEmpty()&&selected.containsAll(visible);Button selectAll=ProductUi.button(this,all?"取消全选":"全选",false,()->{if(all)selected.removeAll(visible);else selected.addAll(visible);render();});selectAll.setEnabled(!visible.isEmpty());selectionBar.addView(selectAll);Button clear=ProductUi.button(this,"清空",false,()->{selected.clear();render();});clear.setEnabled(!selected.isEmpty());selectionBar.addView(clear);ProductUi.add(body,selectionBar,0);
            for(PhoneApp entry:phoneApps){if(!filter.isEmpty()&&!entry.label.toLowerCase(Locale.ROOT).contains(filter)&&!entry.pkg.toLowerCase(Locale.ROOT).contains(filter))continue;String pkg=entry.pkg;boolean exists=installed(pkg),waiting=space!=null&&app.imports.batch.queued(space,pkg);
                var tile=new WorkbenchLibraryUi.Tile(this,entry.label,exists?"已在此空间":waiting?"添加中":"",entry.icon,true,()->{if(!selected.remove(pkg))selected.add(pkg);render();});tile.packageName(pkg);
                tile.setEnabled(!exists&&!waiting&&catalog!=null);tile.selection(selected.contains(pkg));tile.setContentDescription(entry.label+(exists?"，已在此空间":""));tiles.add(tile);}
            if(!tiles.isEmpty())WorkbenchLibraryUi.grid(body,tiles);
            else if(!filter.isEmpty())WorkbenchLibraryUi.copy(body,"没有找到「"+query.trim()+"」。");
            else WorkbenchLibraryUi.copy(body,"没有可以显示的手机应用，可以从 APK 文件添加。");
        }
        WorkbenchImportBatch batch=app.imports.batch;install.setVisibility(tab==0||batch.active()?View.VISIBLE:View.GONE);install.setText(selected.isEmpty()?batch.active()?batch.running()?"后台添加中，可继续选择":"继续添加剩余应用":"选择要添加的应用":(batch.active()?"加入队列 ":"添加所选 ")+selected.size()+" 个应用");install.setEnabled(app.engine.ready()&&space!=null&&(!selected.isEmpty()||batch.active()&&!batch.running()));
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private void chooseFile(){if(space==null||!app.engine.ready()){status.show(space==null?"空间还没有准备好，请稍候":"运行引擎未连接",false);return;}importSpace=space;try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addCategory(Intent.CATEGORY_OPENABLE),APK);}catch(Exception error){importSpace=null;status.show(UiText.error(error),false);}}
    private void submit(){if(selected.isEmpty()){if(app.imports.batch.active())app.imports.batch.resume();return;}if(space==null||!app.engine.ready())return;try{List<String> packages=new ArrayList<>(selected);app.imports.batch.start(space,packages,packages.stream().map(this::label).toList(),"phone");selected.clear();render();}catch(Exception error){status.show(UiText.error(error),false);}}
    private void operationChanged(){if(destroyed)return;if(app.imports.batch.finished()&&!handledBatch.equals(app.imports.batch.id())){handledBatch=app.imports.batch.id();selected.removeAll(app.imports.batch.phonePackages());}boolean current=busy();String message=app.operations.message;status.show(!message.isEmpty()&&(current||!message.equals(initialMessage))?message:"",current||app.operations.busy());boolean complete=wasBusy&&!current||settled!=app.operations.settled;wasBusy=current;settled=app.operations.settled;render();if(complete)refresh();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==APK){String target=importSpace;importSpace=null;if(result==RESULT_OK&&data!=null&&target!=null){try{
            Set<android.net.Uri> chosen=new LinkedHashSet<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)chosen.add(data.getClipData().getItemAt(i).getUri());if(data.getData()!=null)chosen.add(data.getData());chosen.remove(null);
            List<String> sources=new ArrayList<>(),labels=new ArrayList<>();for(android.net.Uri uri:chosen){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException transientGrant){}sources.add(uri.toString());String path=uri.getLastPathSegment();labels.add(path==null?"APK "+(labels.size()+1):path.substring(path.lastIndexOf('/')+1));}
            if(!sources.isEmpty())app.imports.batch.start(target,sources,labels,"uri");
        }catch(Exception error){status.show(UiText.error(error),false);}}}}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("space",space);out.putStringArrayList("selectedPackages",new ArrayList<>(selected));out.putString("handledBatch",handledBatch);out.putString("importSpace",importSpace);out.putInt("tab",tab);out.putString("query",query);}
    @Override protected void onDestroy(){destroyed=true;app.operations.remove(observer);app.imports.batch.remove(observer);app.engine.remove(this);super.onDestroy();}
}
