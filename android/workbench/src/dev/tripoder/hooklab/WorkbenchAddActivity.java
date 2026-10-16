package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.content.pm.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import java.util.*;

/** Explicit single-app selection. Copies the complete installed APK set, never launches the host app. */
public final class WorkbenchAddActivity extends Activity implements EngineConnection.Listener {
    private static final int APK=4102;
    private WorkbenchApplication app;private WorkbenchCatalog catalog;private LinearLayout body,tabs;
    private TextView install,spaceView,message;private String space,selected,importSpace,initialMessage;private int tab;
    private boolean destroyed,wasBusy,loading;private final List<ApplicationInfo> phoneApps=new ArrayList<>();
    private final Runnable observer=this::operationChanged;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);app=(WorkbenchApplication)getApplication();initialMessage=app.operations.message;if(saved!=null){space=saved.getString("space");selected=saved.getString("selected");importSpace=saved.getString("importSpace");tab=saved.getInt("tab");}
        LinearLayout root=WorkbenchLibraryUi.root(this);root.addView(WorkbenchLibraryUi.toolbar(this,"添加应用",this::finish));
        spaceView=WorkbenchLibraryUi.action(this,"选择运行空间",this::chooseSpace);root.addView(spaceView);tabs=ProductUi.row(this);root.addView(tabs);
        message=WorkbenchLibraryUi.text(this,"每次选择一个应用，安装到当前空间",12,WorkbenchLibraryUi.MUTED);message.setPadding(d(16),d(10),d(16),d(10));root.addView(message);
        body=WorkbenchLibraryUi.content(this,root);install=WorkbenchLibraryUi.primary(this,"安装 (0)",this::submit);
        LinearLayout.LayoutParams bottom=new LinearLayout.LayoutParams(-1,d(56));bottom.setMargins(d(14),d(12),d(14),d(12));root.addView(install,bottom);
        app.operations.observe(observer);app.engine.observe(this);operationChanged();readPhoneApps();refresh();
    }
    private int d(int n){return WorkbenchLibraryUi.dp(this,n);}
    private void readPhoneApps(){
        loading=true;render();new Thread(()->{List<ApplicationInfo> found=new ArrayList<>();Exception failure=null;
            try{Set<String> seen=new HashSet<>();for(ResolveInfo result:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)){var info=result.activityInfo.applicationInfo;if(!info.packageName.startsWith("dev.tripoder.")&&seen.add(info.packageName))found.add(info);}found.sort(Comparator.comparing(info->info.loadLabel(getPackageManager()).toString(),String.CASE_INSENSITIVE_ORDER));}catch(Exception error){failure=error;}
            Exception error=failure;runOnUiThread(()->{if(destroyed)return;loading=false;if(error!=null){message.setText("读取手机应用失败："+error.getMessage());return;}phoneApps.clear();phoneApps.addAll(found);render();});
        },"workbench-app-list").start();
    }
    @Override public void changed(){if(destroyed)return;if(!app.engine.ready()){catalog=null;message.setText("引擎未连接，不能安装应用");render();}else refresh();}
    private void refresh(){if(!app.engine.ready())return;String lease=app.engine.lease;app.engine.execute(ControlWire.request("catalog"),(value,error)->{if(destroyed||!lease.equals(app.engine.lease))return;try{if(error!=null)throw error;catalog=new WorkbenchCatalog(value);if(space==null&&!catalog.spaces.isEmpty())space=catalog.spaces.get(0);if(!catalog.spaces.contains(space))space=null;render();}catch(Exception invalid){message.setText("读取空间失败："+invalid.getMessage());}});}
    private void chooseSpace(){if(catalog==null||busy())return;new AlertDialog.Builder(this).setTitle("安装到哪个空间").setItems(catalog.spaces.toArray(String[]::new),(dialog,index)->{space=catalog.spaces.get(index);selected=null;render();}).show();}
    private boolean busy(){return app.imports.preparing||app.operations.pending();}
    private boolean installed(String pkg){if(catalog==null)return false;return catalog.installs.stream().anyMatch(value->pkg.equals(value.optString("package"))&&Objects.equals(space,value.optString("space")));}
    private void render(){
        if(body==null||destroyed)return;body.removeAllViews();tabs.removeAllViews();spaceView.setText(space==null?"请选择运行空间":"空间："+space+"  ▾");
        String[] labels={"手机应用","APK 文件"};for(int i=0;i<2;i++){final int index=i;TextView item=WorkbenchLibraryUi.action(this,labels[i],()->{tab=index;render();});item.setTextColor(i==tab?WorkbenchLibraryUi.ACCENT:WorkbenchLibraryUi.MUTED);item.setSelected(i==tab);tabs.addView(item,new LinearLayout.LayoutParams(0,d(48),1));}
        if(selected!=null&&installed(selected))selected=null;
        if(tab==1){WorkbenchLibraryUi.copy(body,"从手机存储选择原始 APK 文件。安装成功后显示在应用页，可继续创建多个项目。");ProductUi.add(body,WorkbenchLibraryUi.action(this,"选择 APK 文件",this::chooseFile),0);}
        else if(loading)WorkbenchLibraryUi.copy(body,"正在读取手机应用…");
        else{List<View> tiles=new ArrayList<>();for(var info:phoneApps){String pkg=info.packageName;boolean exists=installed(pkg);var tile=new WorkbenchLibraryUi.Tile(this,info.loadLabel(getPackageManager()).toString(),exists?"当前空间已添加":"",info.loadIcon(getPackageManager()),true,()->{selected=pkg.equals(selected)?null:pkg;render();});tile.setEnabled(!exists&&!busy()&&catalog!=null);tile.selection(pkg.equals(selected));tile.setContentDescription("选择应用："+pkg);tiles.add(tile);}WorkbenchLibraryUi.grid(body,tiles);if(tiles.isEmpty())WorkbenchLibraryUi.copy(body,"没有可显示的手机应用，可从 APK 文件添加。");}
        install.setVisibility(tab==0?View.VISIBLE:View.GONE);install.setText(busy()?"正在处理…":"安装 ("+(selected==null?0:1)+")");install.setEnabled(!busy()&&space!=null&&selected!=null&&app.engine.ready());install.setAlpha(install.isEnabled()?1f:.45f);
    }
    private void chooseFile(){if(busy()||space==null||!app.engine.ready()){message.setText("请选择空间，并等待当前操作确认");return;}importSpace=space;try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive").addCategory(Intent.CATEGORY_OPENABLE),APK);}catch(Exception error){importSpace=null;message.setText(error.getMessage());}}
    private void submit(){if(selected==null||space==null||busy()||!app.engine.ready())return;app.imports.importInstalled(selected,space);}
    private void operationChanged(){if(destroyed)return;boolean current=busy();if(!app.operations.message.isEmpty()&&(current||!app.operations.message.equals(initialMessage)))message.setText(app.operations.message);boolean complete=wasBusy&&!current;wasBusy=current;render();if(complete)refresh();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==APK){String target=importSpace;importSpace=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&target!=null)app.imports.importUri(data.getData(),target);}}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("space",space);out.putString("selected",selected);out.putString("importSpace",importSpace);out.putInt("tab",tab);}
    @Override protected void onDestroy(){destroyed=true;app.operations.remove(observer);app.engine.remove(this);super.onDestroy();}
}
