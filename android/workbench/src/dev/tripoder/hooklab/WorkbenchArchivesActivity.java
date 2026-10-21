package dev.tripoder.hooklab;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Historical source is recoverable without reintroducing runnable duplicate projects. */
public final class WorkbenchArchivesActivity extends Activity {
    private WorkbenchApplication app;private String project,pkg,export;
    @Override public void onCreate(Bundle saved){ProductUi.theme(this);super.onCreate(saved);app=(WorkbenchApplication)getApplication();project=getIntent().getStringExtra("project");render();}
    private void render(){try{
        pkg=project==null?null:app.projects.get(project).getString("package");LinearLayout root=ProductUi.root(this);root.addView(ProductUi.toolbar(this,"历史脚本与恢复",true));LinearLayout body=WorkbenchLibraryUi.content(this,root);
        WorkbenchLibraryUi.copy(body,"旧项目的脚本在这里保留，不参与运行。恢复前会备份当前脚本；恢复不会部署 Hook。");
        if(project!=null){String current=app.projects.script(project);ProductUi.add(body,ProductUi.button(this,"查看当前脚本",false,()->preview("当前脚本",current,null)),8);ProductUi.add(body,ProductUi.button(this,"保留当前版本并完成迁移",true,()->{try{app.projects.acceptMigration(project);finish();}catch(Exception error){show(error);}}),8);}
        for(JSONObject archive:app.projects.archived(pkg)){String id=archive.getString("id"),name=archive.optString("name",id);ProductUi.add(body,ProductUi.button(this,name+" · "+archive.optString("package","未绑定应用"),false,()->{try{preview(name,app.projects.archivedScript(id),id);}catch(Exception error){show(error);}}),8);}
    }catch(Exception error){show(error);}}
    private void preview(String title,String source,String archive){TextView text=ProductUi.text(this,source,12,ProductUi.INK,false);text.setTextIsSelectable(true);text.setTypeface(android.graphics.Typeface.MONOSPACE);int padding=ProductUi.dp(this,16);text.setPadding(padding,padding,padding,padding);ScrollView scroll=new ScrollView(this);scroll.addView(text);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton("返回",null).setNeutralButton("导出脚本",(d,w)->{export=source;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"tripoder-script.ts"),1);});
        if(project!=null&&archive!=null)dialog.setPositiveButton("恢复此版本",(d,w)->ProductUi.confirm(this,"恢复这个脚本版本？","当前脚本会先保存为历史备份，运行中的 Hook 不会改变。","恢复",false,()->{try{app.projects.restore(project,archive);app.projects.acceptMigration(project);finish();}catch(Exception error){show(error);}}));dialog.show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1&&result==RESULT_OK&&data!=null&&data.getData()!=null&&export!=null)try(var output=getContentResolver().openOutputStream(data.getData())){if(output==null)throw new java.io.IOException("无法写入所选文件");output.write(export.getBytes(StandardCharsets.UTF_8));export=null;}catch(Exception error){show(error);}}
    private void show(Exception error){ProductUi.notice(this,"恢复未完成",UiText.error(error));}
}
