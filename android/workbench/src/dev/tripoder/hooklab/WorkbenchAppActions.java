package dev.tripoder.hooklab;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import dev.tripoder.control.ControlWire;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tap launches through the bound project (asking only when several exist). Long press manages the application. */
final class WorkbenchAppActions {
    private final Activity page;private final WorkbenchApplication app;private final SharedPreferences choices;
    WorkbenchAppActions(Activity page,WorkbenchApplication app){this.page=page;this.app=app;choices=page.getSharedPreferences("application-launch",Context.MODE_PRIVATE);}
    /** Tap: the space comes first (the space the library is filtered to, else the remembered default, else the only one, else asked).
     *  Then one project bound in that space starts directly, several ask which project, none keeps the plain installation launch. */
    void launch(WorkbenchCatalog catalog,String pkg,String filter){
        if(!ready())return;
        List<JSONObject> installs=catalog.installations(pkg);String remembered=choices.getString(pkg,"");
        for(JSONObject install:installs)if(install.optString("space").equals(filter)){launchIn(catalog,install);return;}
        for(JSONObject install:installs)if(remembered.equals(install.optString("space"))){launchIn(catalog,install);return;}
        choose(installs,"在哪个空间运行？",install->{if(remember(pkg,install))launchIn(catalog,install);});
    }
    /** Only COMMITTED bindings to this installation count; a stale binding (its installation was removed or updated) never takes over the tap. */
    private void launchIn(WorkbenchCatalog catalog,JSONObject install){
        // Launch the requested installation; project ownership never adds a second choice.
        launch(install);
    }
    private void launchProject(JSONObject project){
        if(!ready())return;String id=project.optString("id");
        app.engine.execute(ControlWire.request("launchTargets","project",id),(target,error)->{
            if(page.isDestroyed()||page.isFinishing())return;
            if(error!=null){launchFailed(page,app,id,error);return;}
            try{
                JSONArray array=new JSONArray(target.getString("targets"));List<JSONObject> launchers=new ArrayList<>(),all=new ArrayList<>();
                for(int i=0;i<array.length();i++){JSONObject value=array.getJSONObject(i);all.add(value);if(value.optBoolean("launcher")&&value.optBoolean("supported"))launchers.add(value);}
                // A single supported application entry needs no further question; anything ambiguous is asked, never guessed.
                if(launchers.size()==1){startProject(id,target,launchers.get(0));return;}
                List<JSONObject> choices=all.stream().filter(value->value.optBoolean("supported")).collect(java.util.stream.Collectors.toList());
                if(choices.isEmpty()){ProductUi.notice(page,"没有可以打开的界面","这个应用没有允许直接打开的界面。");return;}
                String[] labels=choices.stream().map(WorkbenchAppActions::componentLabel).toArray(String[]::new);
                new AlertDialog.Builder(page).setTitle("打开哪个界面？").setItems(labels,(dialog,index)->startProject(id,target,choices.get(index))).setNegativeButton("取消",null).show();
            }catch(Exception invalid){launchFailed(page,app,id,invalid);}
        });
    }
    static String componentLabel(JSONObject value){return (value.optBoolean("launcher")?"应用入口 · ":"")+value.optString("component");}
    /** A dead earlier launch blocks new ones; the way out is offered where the failure is seen. */
    static void launchFailed(Activity page,WorkbenchApplication app,String project,Exception error){
        if(page.isDestroyed()||page.isFinishing())return;
        String message=error.getMessage()==null?"":error.getMessage();
        if(project!=null&&message.equals("LAUNCH_TARGET_REJECTED"))new AlertDialog.Builder(page).setTitle("应用没有启动").setMessage(UiText.error(error)+"。关闭旧入口不会影响应用数据。").setNegativeButton("取消",null).setPositiveButton("处理旧入口",(d,w)->WorkbenchEntryClosure.show(page,app,project)).show();
        else ProductUi.notice(page,"应用没有启动",UiText.error(error));
    }
    private void startProject(String id,Bundle target,JSONObject component){
        if(!ready())return;
        try{
            Bundle input=ControlWire.request("prepareLaunch","operation",UUID.randomUUID().toString(),"project",id,"bindingOperation",target.getString("bindingOperation"),"generation",target.getString("generation"),"component",component.getString("component"));
            app.operations.submit(input,"启动应用",null,(prepared,failure)->{
                if(failure!=null){launchFailed(page,app,id,failure);return;}
                if(prepared.getBoolean("woken",false))return;
                enter(page,app,prepared,!page.isDestroyed()&&!page.isFinishing()&&visible(),"应用已准备好，但 Tripoder 不在前台，没有打开界面。请回到 Tripoder 后重新启动。");
            });
        }catch(Exception rejected){launchFailed(page,app,id,rejected);}
    }
    /** Starts the guest root the engine prepared. An entry that is not started is handed back, so it never holds the application open. */
    static void enter(Activity page,WorkbenchApplication app,Bundle prepared,boolean canShow,String hidden){
        String operation=prepared.getString("operation");Intent entry=prepared.getParcelable("intent",Intent.class);
        if(canShow&&entry!=null){try{page.startActivity(entry);return;}catch(Exception rejected){app.operations.inform("应用界面没有打开："+UiText.error(rejected));}}
        else app.operations.inform(hidden);
        app.engine.execute(ControlWire.request("abandonLaunch","operation",operation),(value,error)->{
            if(error!=null)app.operations.inform("未打开的启动入口没有收回："+UiText.error(error));
            app.operations.query();
        });
    }
    /** Window focus can be held briefly by system overlays while this page is still on screen; what matters is that this app is visible. */
    static boolean visible(){
        ActivityManager.RunningAppProcessInfo state=new ActivityManager.RunningAppProcessInfo();ActivityManager.getMyMemoryState(state);
        return state.importance<=ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }
    void menu(WorkbenchCatalog catalog,String pkg,String name){
        new AlertDialog.Builder(page).setTitle(name).setItems(new String[]{"分析","选择默认空间","强行停止","从空间移除","删除应用"},(dialog,index)->{
            if(index==0){String id=catalog.project(pkg);if(id!=null)page.startActivity(new Intent(page,WorkbenchProjectActivity.class).putExtra("project",id));}
            else if(index==1)choose(catalog.installations(pkg),"轻点图标时使用的空间",install->{if(remember(pkg,install))app.operations.inform("「"+name+"」将在「"+app.spaces.name(install.optString("space"))+"」中运行");});
            else if(index==2)choose(catalog.installations(pkg),"停止哪个空间中的应用？",install->ProductUi.confirm(page,"强行停止「"+name+"」？","「"+app.spaces.name(install.optString("space"))+"」中正在运行的界面和后台任务会立即结束，尚未保存的内容可能丢失。","强行停止",true,()->stop(install,name)));
            else if(index==3)choose(catalog.installations(pkg),"从哪个空间移除？",install->{int bound=boundProjects(catalog,pkg,install.optString("space"));ProductUi.confirm(page,"从「"+app.spaces.name(install.optString("space"))+"」移除「"+name+"」？","这个空间中该应用的全部数据会被删除，无法恢复。"+"应用脚本会保留。","移除",true,()->uninstall(install,name));});
            else confirmDelete(catalog,pkg,name);
        }).show();
    }
    private int boundProjects(WorkbenchCatalog catalog,String pkg,String space){int count=0;for(JSONObject ref:catalog.bindings.values())if(pkg.equals(ref.optString("package"))&&(space==null||space.equals(ref.optString("space"))))count++;return count;}
    private void confirmDelete(WorkbenchCatalog catalog,String pkg,String name){
        int spaces=catalog.installations(pkg).size(),projects=boundProjects(catalog,pkg,null);
        ProductUi.confirm(page,"删除「"+name+"」？",(spaces>1?"将从 "+spaces+" 个空间中删除这个应用及其全部数据":"将删除这个应用及其全部数据")+"，并删除这个应用的脚本"+"。无法恢复。","删除",true,()->delete(pkg,name));
    }
    private void choose(List<JSONObject> installs,String title,Consumer<JSONObject> selected){
        if(installs.isEmpty()){ProductUi.notice(page,"应用暂时不可用","请刷新后重试。");return;}
        if(installs.size()==1){selected.accept(installs.get(0));return;}
        String[] labels=installs.stream().map(i->app.spaces.name(i.optString("space"))+" · 版本 "+i.optLong("version")).toArray(String[]::new);
        new AlertDialog.Builder(page).setTitle(title).setItems(labels,(dialog,index)->selected.accept(installs.get(index))).setNegativeButton("取消",null).show();
    }
    private boolean remember(String pkg,JSONObject install){
        if(choices.edit().putString(pkg,install.optString("space")).commit())return true;
        ProductUi.notice(page,"没有保存空间选择","请重试。");return false;
    }
    private boolean ready(){
        // Unconfirmed operations do not block; the engine rejects a conflicting request itself.
        if(app.engine.ready())return true;
        ProductUi.notice(page,"暂时不能操作","运行引擎还没有连接，请稍后再试。");return false;
    }
    private Bundle request(String command,JSONObject install){return ControlWire.request(command,"operation",UUID.randomUUID().toString(),"space",install.optString("space"),"package",install.optString("package"),"generation",install.optString("generation"));}
    private void launch(JSONObject install){
        if(!ready())return;
        try{app.operations.submit(request("prepareApplicationLaunch",install),"启动应用",null,(prepared,error)->{
            if(error!=null){launchFailed(page,app,null,error);return;}
            if(prepared.getBoolean("woken",false))return;
            enter(page,app,prepared,!page.isDestroyed()&&!page.isFinishing()&&visible(),"应用已准备好，但 Tripoder 不在前台，没有打开界面。请回到 Tripoder 后重新启动。");
        });}catch(Exception error){launchFailed(page,app,null,error);}
    }
    private void stop(JSONObject install,String name){
        if(!ready())return;
        try{app.operations.submit(request("stopApplication",install),"停止「"+name+"」",null);}
        catch(Exception error){ProductUi.notice(page,"没有停止",UiText.error(error));}
    }
    private void uninstall(JSONObject install,String name){
        if(!ready())return;
        try{app.operations.submit(request("uninstallApplication",install),"从「"+app.spaces.name(install.optString("space"))+"」移除「"+name+"」",null);}
        catch(Exception error){ProductUi.notice(page,"没有移除",UiText.error(error));}
    }
    /** Every installation, its data and the projects bound to it go; the confirmation states exactly that before anything is sent. */
    private void delete(String pkg,String name){
        if(!ready())return;
        try{
            app.operations.submit(ControlWire.request("deleteApplication","operation",UUID.randomUUID().toString(),"package",pkg),"删除应用「"+name+"」",null);
        }catch(Exception error){ProductUi.notice(page,"没有删除",UiText.error(error));}
    }
    void batchManage(WorkbenchCatalog catalog,String spaceFilter,Runnable onDone){
        if(!ready())return;
        List<String> pkgs=new ArrayList<>(catalog.applications(spaceFilter));
        if(pkgs.isEmpty()){ProductUi.notice(page,"没有可管理的应用","当前空间或筛选条件下没有应用。");return;}
        Set<String> selected=new LinkedHashSet<>();
        LinearLayout form=ProductUi.column(page);
        form.setPadding(ProductUi.dp(page,20),ProductUi.dp(page,12),ProductUi.dp(page,20),ProductUi.dp(page,16));

        LinearLayout subBar=ProductUi.row(page);subBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView countText=ProductUi.text(page,"已选择 0 / "+pkgs.size()+" 个应用",13,ProductUi.MUTED,false);
        subBar.addView(countText,new LinearLayout.LayoutParams(0,-2,1));
        TextView toggleAll=ProductUi.text(page,"全选",13,ProductUi.ACCENT,true);
        toggleAll.setPadding(ProductUi.dp(page,8),ProductUi.dp(page,4),ProductUi.dp(page,8),ProductUi.dp(page,4));
        toggleAll.setBackground(new RippleDrawable(ColorStateList.valueOf(ProductUi.TINT),ProductUi.shape(ProductUi.TINT,8,page),null));
        subBar.addView(toggleAll);ProductUi.add(form,subBar,0);
        ProductUi.divider(form);

        ScrollView scroll=new ScrollView(page);scroll.setFillViewport(true);
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,ProductUi.dp(page,Math.min(320,Math.max(120,pkgs.size()*56))));
        slp.topMargin=ProductUi.dp(page,8);slp.bottomMargin=ProductUi.dp(page,8);scroll.setLayoutParams(slp);
        LinearLayout list=ProductUi.column(page);scroll.addView(list);

        Map<String,CheckBox> checkBoxes=new LinkedHashMap<>();
        List<Button> actionButtons=new ArrayList<>();
        Runnable updateState=()->{
            countText.setText("已选择 "+selected.size()+" / "+pkgs.size()+" 个应用");
            toggleAll.setText(selected.size()==pkgs.size()?"全不选":"全选");
            boolean hasSelected=!selected.isEmpty();
            for(Button b:actionButtons)b.setEnabled(hasSelected);
        };

        for(String pkg:pkgs){
            List<JSONObject> installs=catalog.installations(pkg);
            Bundle cached=installs.isEmpty()?null:app.presentations.get(installs.get(0).optString("generation"));
            Drawable icon=app.presentations.iconDrawable(page,cached);
            String name=catalog.appName(app,pkg);
            LinearLayout row=ProductUi.row(page);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0,ProductUi.dp(page,6),0,ProductUi.dp(page,6));

            CheckBox cb=new CheckBox(page);cb.setClickable(false);cb.setFocusable(false);
            checkBoxes.put(pkg,cb);row.addView(cb);

            ImageView iconView=new ImageView(page);
            iconView.setImageDrawable(icon==null?new ProductUi.Icon("grid",ProductUi.MUTED):icon);
            iconView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(ProductUi.dp(page,32),ProductUi.dp(page,32));
            ilp.leftMargin=ProductUi.dp(page,4);ilp.rightMargin=ProductUi.dp(page,10);
            row.addView(iconView,ilp);

            LinearLayout copy=ProductUi.column(page);
            ProductUi.add(copy,ProductUi.text(page,name,14,ProductUi.INK,true),0);
            String detail;
            if(spaceFilter==null||spaceFilter.isEmpty()){
                int spaces=installs.size(),bound=boundProjects(catalog,pkg,null);
                detail=spaces+" 个空间"+"";
            }else{
                int bound=boundProjects(catalog,pkg,spaceFilter);
                detail=app.spaces.name(spaceFilter)+"";
            }
            ProductUi.add(copy,ProductUi.text(page,detail,11,ProductUi.MUTED,false),2);
            row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));

            row.setBackground(new RippleDrawable(ColorStateList.valueOf(ProductUi.TINT),null,null));
            row.setFocusable(true);
            row.setOnClickListener(v->{
                if(selected.contains(pkg)){selected.remove(pkg);cb.setChecked(false);}
                else{selected.add(pkg);cb.setChecked(true);}
                updateState.run();
            });
            list.addView(row);
        }

        toggleAll.setOnClickListener(v->{
            if(selected.size()==pkgs.size()){selected.clear();for(CheckBox cb:checkBoxes.values())cb.setChecked(false);}
            else{selected.addAll(pkgs);for(CheckBox cb:checkBoxes.values())cb.setChecked(true);}
            updateState.run();
        });

        ProductUi.add(form,scroll,0);
        ProductUi.divider(form);

        AlertDialog dialog=new AlertDialog.Builder(page)
            .setTitle("批量管理应用"+(spaceFilter!=null&&!spaceFilter.isEmpty()?" · "+app.spaces.name(spaceFilter):""))
            .setView(form).setNegativeButton("取消",null).create();

        LinearLayout buttonBox=ProductUi.column(page);buttonBox.setPadding(0,ProductUi.dp(page,10),0,0);
        LinearLayout rowActions=ProductUi.row(page);

        Button stopBtn=ProductUi.button(page,"批量停止",false,()->{
            if(selected.isEmpty())return;dialog.dismiss();
            List<JSONObject> installsToStop=new ArrayList<>();
            for(String p:selected)for(JSONObject inst:catalog.installations(p))if(spaceFilter==null||spaceFilter.isEmpty()||spaceFilter.equals(inst.optString("space")))installsToStop.add(inst);
            String msg="将强行停止选中的 "+selected.size()+" 个应用在"+(spaceFilter==null||spaceFilter.isEmpty()?"所有空间":"「"+app.spaces.name(spaceFilter)+"」")+"中的运行进程。未保存内容可能丢失。";
            ProductUi.confirm(page,"批量强行停止？",msg,"强行停止",true,()->batchStopStep(catalog,installsToStop,0,onDone));
        });
        actionButtons.add(stopBtn);rowActions.addView(stopBtn,new LinearLayout.LayoutParams(0,-2,1));

        if(spaceFilter!=null&&!spaceFilter.isEmpty()){
            Button uninstallBtn=ProductUi.button(page,"从空间移除",false,()->{
                if(selected.isEmpty())return;dialog.dismiss();
                List<JSONObject> installsToRemove=new ArrayList<>();int bound=0;
                for(String p:selected)for(JSONObject inst:catalog.installations(p))if(spaceFilter.equals(inst.optString("space"))){installsToRemove.add(inst);bound+=boundProjects(catalog,p,spaceFilter);}
                String msg="将从「"+app.spaces.name(spaceFilter)+"」移除选中的 "+selected.size()+" 个应用及其数据。"+"应用脚本会保留。";
                ProductUi.confirm(page,"从「"+app.spaces.name(spaceFilter)+"」批量移除？",msg,"移除",true,()->batchUninstallStep(catalog,installsToRemove,0,onDone));
            });
            LinearLayout.LayoutParams ulp=new LinearLayout.LayoutParams(0,-2,1);ulp.leftMargin=ProductUi.dp(page,8);
            rowActions.addView(uninstallBtn,ulp);actionButtons.add(uninstallBtn);
        }
        ProductUi.add(buttonBox,rowActions,0);

        Button deleteBtn=ProductUi.danger(page,"批量删除应用（彻底删除）",()->{
            if(selected.isEmpty())return;dialog.dismiss();
            int totalProjects=0;for(String p:selected)totalProjects+=boundProjects(catalog,p,null);
            String msg="将从所有空间删除选中的 "+selected.size()+" 个应用及其全部数据"+"，并删除这些应用的脚本"+"。无法恢复。";
            ProductUi.confirm(page,"批量删除 "+selected.size()+" 个应用？",msg,"删除",true,()->batchDeleteStep(catalog,new ArrayList<>(selected),0,onDone));
        });
        actionButtons.add(deleteBtn);ProductUi.add(buttonBox,deleteBtn,8);
        ProductUi.add(form,buttonBox,0);

        updateState.run();
        dialog.show();
    }
    private void batchDeleteStep(WorkbenchCatalog catalog,List<String> pkgs,int index,Runnable onDone){
        if(index>=pkgs.size()){app.operations.inform("批量删除完成，已删除 "+pkgs.size()+" 个应用");if(onDone!=null)onDone.run();return;}
        String pkg=pkgs.get(index),name=catalog.appName(app,pkg);
        app.operations.inform("正在删除 ("+(index+1)+"/"+pkgs.size()+")「"+name+"」…");
        try{
            app.operations.submit(ControlWire.request("deleteApplication","operation",UUID.randomUUID().toString(),"package",pkg),"删除应用「"+name+"」",null,(res,err)->{
                batchDeleteStep(catalog,pkgs,index+1,onDone);
            });
        }catch(Exception error){
            app.operations.inform("删除「"+name+"」失败："+UiText.error(error));
            batchDeleteStep(catalog,pkgs,index+1,onDone);
        }
    }
    private void batchStopStep(WorkbenchCatalog catalog,List<JSONObject> installs,int index,Runnable onDone){
        if(index>=installs.size()){app.operations.inform("批量停止完成，共停止 "+installs.size()+" 个应用实例");if(onDone!=null)onDone.run();return;}
        JSONObject install=installs.get(index);String pkg=install.optString("package"),space=install.optString("space");
        String name=catalog.appName(app,pkg),spaceName=app.spaces.name(space);
        app.operations.inform("正在停止 ("+(index+1)+"/"+installs.size()+")「"+name+"」("+spaceName+")…");
        try{
            app.operations.submit(request("stopApplication",install),"停止「"+name+"」",null,(res,err)->{
                batchStopStep(catalog,installs,index+1,onDone);
            });
        }catch(Exception error){
            app.operations.inform("停止「"+name+"」失败："+UiText.error(error));
            batchStopStep(catalog,installs,index+1,onDone);
        }
    }
    private void batchUninstallStep(WorkbenchCatalog catalog,List<JSONObject> installs,int index,Runnable onDone){
        if(index>=installs.size()){app.operations.inform("批量移除完成，已从空间移除 "+installs.size()+" 个应用");if(onDone!=null)onDone.run();return;}
        JSONObject install=installs.get(index);String pkg=install.optString("package"),space=install.optString("space");
        String name=catalog.appName(app,pkg),spaceName=app.spaces.name(space);
        app.operations.inform("正在移除 ("+(index+1)+"/"+installs.size()+")「"+name+"」…");
        try{
            app.operations.submit(request("uninstallApplication",install),"从「"+spaceName+"」移除「"+name+"」",null,(res,err)->{
                batchUninstallStep(catalog,installs,index+1,onDone);
            });
        }catch(Exception error){
            app.operations.inform("移除「"+name+"」失败："+UiText.error(error));
            batchUninstallStep(catalog,installs,index+1,onDone);
        }
    }
}
