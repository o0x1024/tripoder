package dev.tripoder.hooklab;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONObject;

public final class ProjectFilesActivity extends Activity {
    private ProjectStore store;private String projectId;private EditText path,editor;private TextView status;private LinearLayout entries;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);store=new ProjectStore(this);projectId=ProjectStore.id(this);
        LinearLayout page=ProductUi.screen(this,"项目文件",store.title(projectId));
        LinearLayout card=ProductUi.card(this);status=ProductUi.text(this,"脚本、配置和测试记录保存在此项目下。",12,ProductUi.MUTED,false);ProductUi.add(card,status,0);
        String initial=getIntent().getStringExtra("path");if(initial==null)initial="scripts/main.ts";
        path=ProductUi.field(card,"项目内路径","例如：scripts/main.ts",saved==null?initial:saved.getString("path",initial),false);
        editor=ProductUi.field(card,"内容","输入文本或选择下面的文件",saved==null?"":saved.getString("content",""),true);editor.setTypeface(android.graphics.Typeface.MONOSPACE);editor.setTextSize(12);editor.setMinLines(7);editor.setMaxLines(16);editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ProductUi.pair(card,ProductUi.button(this,"读取文件",false,this::read),ProductUi.button(this,"保存文件",true,this::save));ProductUi.add(page,card,16);
        LinearLayout files=ProductUi.card(this);ProductUi.add(files,ProductUi.text(this,"文件列表",17,ProductUi.INK,true),0);entries=ProductUi.column(this);ProductUi.add(files,entries,10);ProductUi.add(page,files,16);refresh();if(saved==null)read();page.setFocusableInTouchMode(true);page.requestFocus();
    }
    private void read(){try{editor.setText(store.read(projectId,path.getText().toString().trim(),""));status.setText("已打开 · "+path.getText());}catch(Exception error){status.setText(error.getMessage());}}
    private void save(){try{store.write(projectId,path.getText().toString().trim(),editor.getText().toString());status.setText("文件已保存到当前项目");refresh();}catch(Exception error){status.setText("保存失败："+error.getMessage());}}
    private void refresh(){entries.removeAllViews();try{for(String file:store.files(projectId))entries.addView(ProductUi.entry(this,file.endsWith(".ts")?"code":"file",file,file.equals("project.json")?"项目元数据 · 只读":"点击读取",()->{path.setText(file);read();}));}catch(Exception error){status.setText(error.getMessage());}}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("path",path.getText().toString());out.putString("content",editor.getText().toString());super.onSaveInstanceState(out);}
}
