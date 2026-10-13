package dev.tripoder.hooklab;

import android.app.Activity;
import android.os.*;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class ScriptActivity extends Activity {
    private GuestClient client;
    private ProjectStore projects;
    private String projectId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView state, output;
    private EditText source, endpoint, model, key, request;
    private String session, template;
    private JSONObject evidence;
    private LinearLayout buttons;
    private Button generateButton;
    private boolean destroyed, runtimeBusy, vaultBusy = true;
    private ApiKeyVault vault;
    private TextView credentialStatus;
    private Button saveConfigButton, clearKeyButton, configToggle;
    private boolean settingCredential;
    private JSONObject liveAiReport;
    private String generatedSource;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        projects=new ProjectStore(this);projectId=ProjectStore.id(this);
        try{projects.initialize();projects.get(projectId);}catch(Exception error){Toast.makeText(this,"项目不可用",Toast.LENGTH_LONG).show();finish();return;}
        LinearLayout column=ProductUi.screen(this,"脚本工作台",projects.title(projectId)+" · TypeScript");
        column.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout status=ProductUi.card(this);
        state=ProductUi.text(this,"正在连接应用…",15,ProductUi.INK,true);ProductUi.add(status,state,0);
        output=ProductUi.text(this,"连接后即可生成、检查与应用方案。",12,ProductUi.MUTED,false);output.setTextIsSelectable(true);ProductUi.add(status,output,8);ProductUi.add(column,status,16);
        LinearLayout ai=ProductUi.card(this);LinearLayout title=ProductUi.row(this);
        title.addView(ProductUi.icon(this,"spark",ProductUi.ACCENT,ProductUi.TINT,36),new LinearLayout.LayoutParams(ProductUi.dp(this,36),ProductUi.dp(this,36)));
        TextView aiTitle=ProductUi.text(this,"用一句话描述想法",18,ProductUi.INK,true);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=ProductUi.dp(this,10);title.addView(aiTitle,tp);ProductUi.add(ai,title,0);
        request=ProductUi.field(ai,"你想改变什么？","例如：让问候变为大写，并调整 Native 返回值",projects.read(projectId,"ai/prompt.txt","将 Java 问候改为大写，Native 返回入参乘 2 加 700"),true);
        android.content.SharedPreferences config=getSharedPreferences("ai-config",MODE_PRIVATE);
        LinearLayout configuration=ProductUi.column(this);
        configToggle=ProductUi.button(this,"服务配置  ·  "+config.getString("model","deepseek-flash")+"  ⌄",false,()->{
            toggleSection(configuration,column);
        });ProductUi.add(ai,configToggle,12);
        endpoint=ProductUi.field(configuration,"接口地址","https://服务/v1/chat/completions",config.getString("endpoint","https://api.deepseek.com/chat/completions"),false);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        model=ProductUi.field(configuration,"模型","服务支持的模型 ID",config.getString("model","deepseek-flash"),false);
        key=ProductUi.field(configuration,"API Key","保存后加密保留，无需重复输入","",false);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false);key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        credentialStatus=ProductUi.text(this,"正在读取已保存的密钥…",12,ProductUi.MUTED,false);ProductUi.add(configuration,credentialStatus,10);
        saveConfigButton=ProductUi.button(this,"保存配置",true,this::saveConfiguration);
        clearKeyButton=ProductUi.button(this,"清除密钥",false,this::clearCredential);
        ProductUi.pair(configuration,saveConfigButton,clearKeyButton);
        endpoint.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                if(!settingCredential){key.setText("");credentialStatus.setText("地址已更改，请填写此服务的密钥并保存。");}
            }
            public void afterTextChanged(android.text.Editable s){}
        });
        key.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(!settingCredential)credentialStatus.setText("密钥尚未保存；保存配置或生成草稿时会加密保存。");}
            public void afterTextChanged(android.text.Editable s){}
        });
        ProductUi.add(ai,configuration,0);configuration.setVisibility(getIntent().getBooleanExtra("configure",false)?android.view.View.VISIBLE:android.view.View.GONE);
        generateButton=ProductUi.button(this,"生成并检查草稿",true,()->{
            if(key.getText().toString().trim().isEmpty()){configuration.setVisibility(android.view.View.VISIBLE);state.setText("请先配置 AI 服务");output.setText("在服务配置中填写 API Key，然后生成草稿。");Toast.makeText(this,"请填写 API Key",Toast.LENGTH_SHORT).show();key.post(()->key.requestRectangleOnScreen(new android.graphics.Rect(0,0,key.getWidth(),key.getHeight()),false));return;}generate();
        });ProductUi.add(ai,generateButton,16);
        ProductUi.add(ai,ProductUi.text(this,"点击后发送需求与目标签名。生成结果不会自动应用。",11,ProductUi.MUTED,false),12);ProductUi.add(column,ai,16);
        LinearLayout editor=ProductUi.card(this);LinearLayout code=ProductUi.column(this);
        Button codeToggle=ProductUi.button(this,"TypeScript 草稿  ·  展开 / 收起",false,()->toggleSection(code,column));
        ProductUi.add(editor,codeToggle,0);
        source=ProductUi.field(code,"高级编辑","在这里编写 TypeScript Hook 方案","",true);source.setTypeface(Typeface.MONOSPACE);source.setTextSize(12);
        source.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);source.setMinLines(10);source.setMaxLines(18);
        source.setHorizontallyScrolling(false);source.setSaveEnabled(false);
        ProductUi.add(code,ProductUi.text(this,"可用目标：GuestProbe.greet(String) 与 guest_delay_ms(int)。仅开放已观察目标和 SDK。",11,ProductUi.MUTED,false),10);
        ProductUi.add(editor,code,0);code.setVisibility(getIntent().getBooleanExtra("showCode",false)?android.view.View.VISIBLE:android.view.View.GONE);ProductUi.add(column,editor,16);
        buttons=ProductUi.column(this);
        ProductUi.pair(buttons,ProductUi.button(this,"检查草稿",false,()->operate(GuestService.CHECK_SCRIPT)),ProductUi.button(this,"应用方案",true,()->operate(GuestService.DEPLOY_SCRIPT)));
        ProductUi.pair(buttons,ProductUi.button(this,"回滚上个版本",false,()->simple(GuestService.ROLLBACK)),ProductUi.button(this,"停用 Hook",false,()->simple(GuestService.DISABLE)));
        ProductUi.add(buttons,ProductUi.button(this,"调整当前运行参数",false,this::parameters),10);
        ProductUi.add(buttons,ProductUi.button(this,"打开应用，查看效果",false,()->startActivity(ProjectStore.intent(this,GuestStubActivity.class,projectId))),10);ProductUi.add(column,buttons,8);
        ProductUi.add(column,ProductUi.text(this,"草稿会自动保存在本机。应用方案只影响当前客体；重启后需重新应用。",12,ProductUi.MUTED,false),18);
        column.setFocusableInTouchMode(true);column.requestFocus();
        vault = new ApiKeyVault(this);
        client = new GuestClient(this); busy(true);
        loadCredential();
        client.connect(() -> client.send(GuestService.EDITOR, new Bundle(), result -> {
            if (result.has("error")) { fail(result.optString("error")); return; }
            try {
                evidence = result.getJSONObject("evidence"); session = evidence.getString("id");
                template = result.getString("template"); source.setText(projects.read(projectId,"scripts/main.ts",result.getString("draft")));
                state.setText(result.optBoolean("enabled")?"Hook 正在运行":"工作区已就绪"); output.setText("Java 与 Native 目标已连接，可检查或应用草稿。当前版本："+result.optInt("revision")); busy(false);
                if (getIntent().getBooleanExtra("selfTest", false)) {
                    int seconds = Math.max(0, Math.min(60, getIntent().getIntExtra("autoDelaySeconds", 0)));
                    busy(true); state.setText(seconds + " 秒后运行工作台自检，可拔掉 USB"); main.postDelayed(this::selfTest, seconds * 1000L);
                }
            } catch (Exception error) { fail(error.toString()); }
        }));
    }
    private void toggleSection(LinearLayout section,LinearLayout page){
        boolean show=section.getVisibility()==android.view.View.GONE;
        if(!show){android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(section.getWindowToken(),0);section.clearFocus();page.requestFocus();}
        section.setVisibility(show?android.view.View.VISIBLE:android.view.View.GONE);
    }
    private void enable(android.view.View view,boolean enabled) {
        view.setEnabled(enabled);
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)enable(group.getChildAt(i),enabled);}
    }
    private void busy(boolean value) {
        runtimeBusy=value;refreshControls();
    }
    private void refreshControls(){
        boolean ready=!runtimeBusy&&evidence!=null;
        enable(buttons,ready);generateButton.setEnabled(ready&&!vaultBusy);source.setEnabled(ready);
        boolean editable=!runtimeBusy&&!vaultBusy;
        endpoint.setEnabled(editable);model.setEnabled(editable);key.setEnabled(editable);
        saveConfigButton.setEnabled(editable);clearKeyButton.setEnabled(editable);
    }
    private void loadCredential(){
        String address=endpoint.getText().toString().trim();
        io.execute(()->{
            try{
                String stored=vault.load(address);
                main.post(()->{if(destroyed)return;settingCredential=true;key.setText(stored);settingCredential=false;
                    credentialStatus.setText(stored.isEmpty()?"尚未保存密钥。保存后将在本机加密保留。":"已读取加密保存的密钥，可直接生成草稿。");vaultBusy=false;refreshControls();});
            }catch(Exception error){main.post(()->credentialFailure("无法读取已保存的密钥，请重新填写并保存，或清除密钥。"));}
        });
    }
    private void storeConfiguration(String address,String name,String credential) throws Exception{
        if(name.isEmpty())throw new IllegalArgumentException("Missing model");
        vault.save(address,credential);
        if(!getSharedPreferences("ai-config",MODE_PRIVATE).edit().putString("endpoint",address).putString("model",name).commit())
            throw new IOException("Configuration save failed");
    }
    private void configurationSaved(){
        configToggle.setText("服务配置  ·  "+model.getText().toString().trim()+"  ⌄");
        credentialStatus.setText("配置已保存，密钥已加密。下次打开自动读取。");
    }
    private void credentialFailure(String message){
        if(destroyed)return;vaultBusy=false;refreshControls();credentialStatus.setText(message);
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
    private void saveConfiguration(){
        String address=endpoint.getText().toString().trim(),name=model.getText().toString().trim(),credential=key.getText().toString().trim();
        if(credential.isEmpty()||name.isEmpty()){credentialStatus.setText("请填写模型和 API Key 后保存。");return;}
        vaultBusy=true;refreshControls();credentialStatus.setText("正在加密保存…");
        io.execute(()->{try{storeConfiguration(address,name,credential);main.post(()->{if(destroyed)return;vaultBusy=false;configurationSaved();refreshControls();Toast.makeText(this,"配置已保存",Toast.LENGTH_SHORT).show();});}
            catch(Exception error){main.post(()->credentialFailure("保存失败，请检查接口地址和密钥后重试。"));}});
    }
    private void clearCredential(){
        vaultBusy=true;refreshControls();
        io.execute(()->{try{vault.clear();main.post(()->{if(destroyed)return;settingCredential=true;key.setText("");settingCredential=false;vaultBusy=false;refreshControls();credentialStatus.setText("密钥已清除；接口地址和模型保留。");Toast.makeText(this,"密钥已清除",Toast.LENGTH_SHORT).show();});}
            catch(Exception error){main.post(()->credentialFailure("清除密钥失败，请重试。"));}});
    }
    private void fail(String error) { if (destroyed) return; state.setText("操作失败"); output.setText(error); busy(false); Toast.makeText(this,"操作失败："+error,Toast.LENGTH_LONG).show(); }
    private void parameters(){
        busy(true);client.send(GuestService.EDITOR,new Bundle(),result->{
            busy(false);if(result.has("error")){fail(result.optString("error"));return;}
            JSONObject params=result.optJSONObject("params");if(params==null||params.length()==0){ProductUi.notice(this,"当前方案没有参数","可以直接展开 TypeScript 草稿修改代码。");return;}
            LinearLayout fields=ProductUi.card(this);java.util.LinkedHashMap<String,EditText> editors=new java.util.LinkedHashMap<>();java.util.Iterator<String> names=params.keys();
            while(names.hasNext()){String name=names.next();EditText editor=ProductUi.field(fields,name,"参数值",String.valueOf(params.opt(name)),false);editors.put(name,editor);}
            ProductUi.add(fields,ProductUi.text(this,"编辑的是当前运行版本的参数。类型和范围由方案校验；应用后会开启 Hook。",12,ProductUi.MUTED,false),12);
            ScrollView scroll=new ScrollView(this);scroll.addView(fields);
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("运行参数").setView(scroll).setNegativeButton("取消",null).setPositiveButton("应用参数",null).create();
            dialog.setOnShowListener(v->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
                try{JSONObject next=new JSONObject();for(java.util.Map.Entry<String,EditText> entry:editors.entrySet()){
                    Object old=params.get(entry.getKey());String value=entry.getValue().getText().toString();
                    if(old instanceof Number)next.put(entry.getKey(),Double.parseDouble(value));
                    else if(old instanceof Boolean){if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("布尔参数需要 true 或 false");next.put(entry.getKey(),Boolean.parseBoolean(value));}
                    else next.put(entry.getKey(),value);
                }
                Bundle data=new Bundle();data.putString("params",next.toString());dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                client.send(GuestService.UPDATE,data,sample->{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);if(sample.has("error")){ProductUi.notice(this,"参数未应用",sample.optString("error"));return;}try{projects.write(projectId,"config/parameters.json",next.toString(2));}catch(Exception error){ProductUi.notice(this,"参数已应用，但保存失败",error.getMessage());}dialog.dismiss();state.setText("参数已应用 · Hook 已开启");output.setText("Java → "+sample.optString("java")+"\nNative → "+sample.optInt("native"));});
                }catch(Exception error){ProductUi.notice(this,"参数格式不正确",error.getMessage());}
            }));dialog.show();
        });
    }
    private void simple(int op) {
        busy(true); client.send(op, new Bundle(), result -> {
            busy(false); if (result.has("error")) { fail(result.optString("error")); return; }
            state.setText(op==GuestService.DISABLE?"Hook 已停用":"已回滚上个版本"); Toast.makeText(this,state.getText(),Toast.LENGTH_SHORT).show(); output.setText("Java → " + result.optString("java") + "\nNative → " + result.optInt("native"));
        });
    }
    private void operate(int op) {
        Bundle data = new Bundle(); data.putString("source", source.getText().toString()); data.putString("session", session); busy(true);
        client.send(op, data, result -> {
            if (result.has("error")) { fail(result.optString("error")); return; }
            if (op == GuestService.CHECK_SCRIPT) {
                busy(false);
                state.setText("草稿检查通过，尚未部署"); Toast.makeText(this,"草稿检查通过，等待应用",Toast.LENGTH_SHORT).show(); output.setText("语法与目标检查通过。点击「应用方案」后生效。");
                if (liveAiReport != null && source.getText().toString().equals(generatedSource)) { try { liveAiReport.put("draftChecked",true); saveAi(); } catch(Exception error) { fail(error.toString()); } }
            } else {
                state.setText("方案已应用"); Toast.makeText(this,"方案已应用",Toast.LENGTH_SHORT).show(); client.send(GuestService.SAMPLE, new Bundle(), sample -> {
                    busy(false);
                    if (sample.has("error")) { fail(sample.optString("error")); return; }
                    output.setText("Java → " + sample.optString("java") + "\nNative → " + sample.optInt("native") + "\n版本 → " + result.optInt("revision"));
                    if (liveAiReport != null && source.getText().toString().equals(generatedSource)) {
                        try { liveAiReport.put("deployed",true).put("sample",sample); saveAi(); } catch(Exception error) { fail(error.toString()); }
                    }
                });
            }
        });
    }
    private void generate() {
        if (evidence == null) return;
        String url = endpoint.getText().toString().trim(), name = model.getText().toString().trim(), credential = key.getText().toString().trim(), prompt = request.getText().toString().trim();
        if(prompt.isEmpty()){request.setError("请先描述需求");request.requestFocus();return;}
        state.setText("AI 正在生成草稿…"); output.setText("正在请求 "+name+"，完成后会自动检查。此时不会更改运行中的方案。"); busy(true);
        io.execute(() -> {
            try {
                try { storeConfiguration(url,name,credential); }
                catch(Exception error){main.post(()->{busy(false);credentialFailure("加密保存失败，请检查配置后重试；尚未发送请求。");state.setText("配置未保存");output.setText("保存成功后再生成草稿。");});return;}
                main.post(()->{if(!destroyed)configurationSaved();});
                String draft = AiClient.generate(url, name, credential, prompt, evidence, template);
                main.post(() -> {
                    if (destroyed) return;
                    try {
                        java.net.URI uri = new java.net.URI(url);
                        generatedSource = draft; liveAiReport = new JSONObject().put("liveModelVerified",true).put("model",name)
                            .put("providerHost",uri.getHost()).put("generatedAtMs",System.currentTimeMillis()).put("source",draft)
                            .put("sourceSha256",GuestStore.hex(java.security.MessageDigest.getInstance("SHA-256").digest(draft.getBytes(StandardCharsets.UTF_8))))
                            .put("hostApkSha256",GuestStore.sha256(new File(getApplicationInfo().sourceDir))).put("evidence",evidence);
                        source.setText(draft); saveAi(); operate(GuestService.CHECK_SCRIPT);
                    } catch(Exception error) { fail(error.toString()); }
                });
            } catch (Exception error) { main.post(() -> fail(error.toString())); }
        });
    }
    private void saveAi() throws Exception {
        projects.write(projectId,"ai/latest-generation.json",liveAiReport.toString(2));
        try (FileOutputStream out = new FileOutputStream(new File(getExternalFilesDir(null), "live-ai-result.json"))) { out.write(liveAiReport.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }
    @Override protected void onPause(){
        if(source!=null&&source.length()<=32768)try{projects.write(projectId,"scripts/main.ts",source.getText().toString());projects.write(projectId,"ai/prompt.txt",request.getText().toString());}catch(Exception error){Toast.makeText(this,"草稿保存失败",Toast.LENGTH_LONG).show();}
        super.onPause();
    }

    private void selfTest() {
        busy(true); state.setText("正在运行脚本与接口自检…");
        Bundle input = new Bundle(); input.putInt("hostPid", android.os.Process.myPid());
        client.send(GuestService.SCRIPT_TEST, input, report -> {
            if (!report.optBoolean("passed")) { fail(report.toString()); return; }
            io.execute(() -> {
                try {
                    JSONArray checks = report.getJSONArray("checks"), ai = AiClient.selfTest(evidence, template);
                    for (int i = 0; i < ai.length(); i++) checks.put(ai.get(i));
                    JSONArray credentials=ApiKeyVault.selfTest(this);
                    for(int i=0;i<credentials.length();i++)checks.put(credentials.get(i));
                    report.put("liveModelVerified", false).put("finishedAtMs", System.currentTimeMillis());
                    try (FileOutputStream out = new FileOutputStream(new File(getExternalFilesDir(null), "workbench-result.json"))) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
                    main.post(() -> { if (destroyed) return; busy(false); state.setText("PASS · " + checks.length() + " 项工作台检查"); output.setText("真实代码热更新、回滚、HTTP 接口契约及加密存储检查通过。本次自检不调用真实模型。"); });
                } catch (Exception error) { main.post(() -> fail(error.toString())); }
            });
        });
    }
    @Override protected void onDestroy() { destroyed = true; if(key!=null)key.setText(""); main.removeCallbacksAndMessages(null); if (client != null) client.close(); io.shutdownNow(); super.onDestroy(); }
}
