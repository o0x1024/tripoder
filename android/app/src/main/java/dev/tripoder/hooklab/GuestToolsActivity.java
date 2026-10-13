package dev.tripoder.hooklab;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import org.json.*;

public final class GuestToolsActivity extends Activity {
    private GuestClient client;
    private ProjectStore projects;private String projectId;
    private TextView output;
    private EditText path, content, model,brand,manufacturer;
    private LinearLayout files,environment,entries;
    private Button fileTab,envTab;
    private boolean ready,environmentPage;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        projects=new ProjectStore(this);projectId=ProjectStore.id(this);
        LinearLayout column=ProductUi.screen(this,"应用工具",projects.title(projectId)+" · 运行数据与环境");
        environmentPage=saved!=null?saved.getBoolean("environment"):getIntent().getBooleanExtra("environment",false);
        fileTab=ProductUi.button(this,"文件编辑",!environmentPage,()->select(false));envTab=ProductUi.button(this,"环境配置",environmentPage,()->select(true));ProductUi.pair(column,fileTab,envTab);
        LinearLayout status=ProductUi.card(this);output=ProductUi.text(this,"正在连接应用…",13,ProductUi.MUTED,false);ProductUi.add(status,output,0);ProductUi.add(column,status,16);
        files=ProductUi.column(this);LinearLayout list=ProductUi.card(this);LinearLayout head=ProductUi.row(this);
        head.addView(ProductUi.text(this,"客体文件",17,ProductUi.INK,true),new LinearLayout.LayoutParams(0,-2,1));head.addView(ProductUi.button(this,"刷新",false,()->file("list")));ProductUi.add(list,head,0);
        entries=ProductUi.column(this);ProductUi.add(list,entries,10);ProductUi.add(files,list,16);
        LinearLayout edit=ProductUi.card(this);ProductUi.add(edit,ProductUi.text(this,"文本编辑器",17,ProductUi.INK,true),0);
        path=ProductUi.field(edit,"相对路径","例如：editor-test.txt",saved==null?"editor-test.txt":saved.getString("path","editor-test.txt"),false);
        content=ProductUi.field(edit,"文件内容","读取文件，或填写新内容",saved==null?"":saved.getString("content",""),true);content.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);content.setMinLines(6);content.setMaxLines(12);content.setTypeface(android.graphics.Typeface.MONOSPACE);content.setTextSize(13);
        ProductUi.pair(edit,ProductUi.button(this,"读取",false,()->file("read")),ProductUi.button(this,"保存文件",true,()->file("write")));
        ProductUi.add(edit,ProductUi.text(this,"仅支持客体目录内的文本，单个文件最多 64 KiB。保存会覆盖当前路径的内容。",11,ProductUi.MUTED,false),14);ProductUi.add(files,edit,16);ProductUi.add(column,files,0);
        environment=ProductUi.column(this);LinearLayout profile=ProductUi.card(this);ProductUi.add(profile,ProductUi.text(this,"设备字段",18,ProductUi.INK,true),0);
        ProductUi.add(profile,ProductUi.text(this,"只改变客体进程中的 Java Build 字段，不修改手机系统身份。",13,ProductUi.MUTED,false),10);
        JSONObject prefs;try{prefs=new JSONObject(projects.read(projectId,"config/environment.json","{}"));}catch(Exception error){prefs=new JSONObject();}
        model=ProductUi.field(profile,"型号 · MODEL","留空使用原值",saved==null?prefs.optString("MODEL",""):saved.getString("model",""),false);
        brand=ProductUi.field(profile,"品牌 · BRAND","留空使用原值",saved==null?prefs.optString("BRAND",""):saved.getString("brand",""),false);
        manufacturer=ProductUi.field(profile,"厂商 · MANUFACTURER","留空使用原值",saved==null?prefs.optString("MANUFACTURER",""):saved.getString("manufacturer",""),false);
        ProductUi.pair(profile,ProductUi.button(this,"恢复原值",false,()->applyEnvironment(true)),ProductUi.button(this,"应用字段",true,()->applyEnvironment(false)));
        ProductUi.add(profile,ProductUi.text(this,"字段不会自动应用。修改后回到示例应用，再次调用即可观察结果。",11,ProductUi.MUTED,false),14);ProductUi.add(environment,profile,16);ProductUi.add(column,environment,0);
        select(environmentPage);column.setFocusableInTouchMode(true);column.requestFocus();
        client=new GuestClient(this);client.connect(()->{ready=true;file("list");});
    }
    private void select(boolean env){environmentPage=env;files.setVisibility(env?View.GONE:View.VISIBLE);environment.setVisibility(env?View.VISIBLE:View.GONE);fileTab.setText(env?"文件编辑":"● 文件编辑");envTab.setText(env?"● 环境配置":"环境配置");fileTab.setBackground(ProductUi.shape(env?ProductUi.TINT:ProductUi.ACCENT,13,this));fileTab.setTextColor(env?ProductUi.ACCENT:ProductUi.WHITE);envTab.setBackground(ProductUi.shape(env?ProductUi.ACCENT:ProductUi.TINT,13,this));envTab.setTextColor(env?ProductUi.WHITE:ProductUi.ACCENT);
        if(ready)output.setText(env?"编辑字段后点击应用；留空将使用原值。":"点击文件读取，或在编辑器中输入相对路径。");
        View parent=(View)files.getParent();if(parent!=null&&parent.getParent() instanceof ScrollView)parent.post(()->((ScrollView)parent.getParent()).scrollTo(0,0));
    }
    private void file(String op){
        if(!ready){output.setText("应用连接尚未就绪，请稍后重试。");return;}
        if(!op.equals("list")&&path.getText().toString().trim().isEmpty()){path.setError("请输入相对路径");return;}
        Bundle b=new Bundle();b.putString("operation",op);b.putString("path",path.getText().toString().trim());b.putString("content",content.getText().toString());output.setText(op.equals("list")?"正在读取文件列表…":"正在处理文件…");
        client.send(GuestService.FILE,b,r->{if(r.has("error")){output.setText("操作失败："+r.optString("error"));return;}
            if(r.has("content"))content.setText(r.optString("content"));
            if(r.has("entries"))showFiles(r.optJSONArray("entries"));
            output.setText(op.equals("list")?(environmentPage?"环境工作区已就绪":"文件列表已更新"):op.equals("write")?"文件已保存 · "+r.optString("path"):"已读取 · "+r.optString("path"));
        });
    }
    private void showFiles(JSONArray list){entries.removeAllViews();if(list==null||list.length()==0){ProductUi.add(entries,ProductUi.text(this,"这里还没有文件。可在下方创建文本文件。",13,ProductUi.MUTED,false),8);return;}
        for(int i=0;i<list.length();i++){JSONObject f=list.optJSONObject(i);if(f==null)continue;String name=f.optString("name");boolean directory=f.optBoolean("directory");
            entries.addView(ProductUi.entry(this,"file",name,directory?"目录 · 可手动输入相对文件路径":f.optLong("bytes")+" 字节 · 点击读取",()->{path.setText(directory?name+"/":name);if(directory){path.requestFocus();output.setText("请在相对路径后补全文件名，再点击读取。");}else file("read");}));if(i<list.length()-1)ProductUi.divider(entries);
        }
    }
    private void applyEnvironment(boolean reset){
        if(!ready){output.setText("应用连接尚未就绪，请稍后重试。");return;}
        try{JSONObject profile=new JSONObject();if(!reset){if(model.length()>0)profile.put("MODEL",model.getText().toString());if(brand.length()>0)profile.put("BRAND",brand.getText().toString());if(manufacturer.length()>0)profile.put("MANUFACTURER",manufacturer.getText().toString());}
            Bundle b=new Bundle();b.putString("profile",profile.toString());output.setText("正在更新字段…");
            client.send(GuestService.ENVIRONMENT,b,r->{if(r.has("error")){output.setText("应用失败："+r.optString("error"));return;}
                if(reset){model.setText("");brand.setText("");manufacturer.setText("");}
                try{projects.write(projectId,"config/environment.json",profile.toString(2));}catch(Exception error){output.setText("字段已应用，项目保存失败");return;}
                JSONObject actual=r.optJSONObject("environment");output.setText((reset?"已恢复原始字段":"字段已应用")+(actual==null?"":"\nMODEL  "+actual.optString("MODEL")+"\nBRAND  "+actual.optString("BRAND")));
            });
        }catch(Exception error){output.setText(error.getMessage());}
    }
    @Override protected void onSaveInstanceState(Bundle saved){saved.putBoolean("environment",environmentPage);saved.putString("path",path.getText().toString());saved.putString("content",content.getText().toString());saved.putString("model",model.getText().toString());saved.putString("brand",brand.getText().toString());saved.putString("manufacturer",manufacturer.getText().toString());super.onSaveInstanceState(saved);}
    @Override protected void onDestroy(){if(client!=null)client.close();super.onDestroy();}
}
