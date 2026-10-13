package dev.tripoder.hooklab;

import android.app.Activity;
import android.os.*;
import android.text.*;
import android.view.View;
import android.widget.*;
import java.util.concurrent.*;

/** Global provider settings do not connect to or restart a guest. */
public final class AiSettingsActivity extends Activity {
    private EditText endpoint,model,key;private TextView status;private Button save,clear;
    private ApiKeyVault vault;private boolean destroyed,setting;
    private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);vault=new ApiKeyVault(this);LinearLayout page=ProductUi.screen(this,"AI 服务","所有项目共用 · Chat Completions");page.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout card=ProductUi.card(this);android.content.SharedPreferences prefs=getSharedPreferences("ai-config",0);
        endpoint=ProductUi.field(card,"接口地址","完整 Chat Completions 地址",prefs.getString("endpoint","https://api.deepseek.com/chat/completions"),false);endpoint.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        model=ProductUi.field(card,"模型","模型 ID",prefs.getString("model","deepseek-flash"),false);
        key=ProductUi.field(card,"API Key","保存后自动读取，无需重复输入","",false);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setSaveEnabled(false);key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);key.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        status=ProductUi.text(this,"正在读取加密配置…",12,ProductUi.MUTED,false);ProductUi.add(card,status,14);
        save=ProductUi.button(this,"保存配置",true,this::save);clear=ProductUi.button(this,"清除密钥",false,this::clear);ProductUi.pair(card,save,clear);ProductUi.add(page,card,16);
        ProductUi.add(page,ProductUi.text(this,"密钥使用 Android Keystore 加密保存在本机，不进入项目文件。更换接口地址时需要填写对应服务的密钥。",12,ProductUi.MUTED,false),18);
        endpoint.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!setting){key.setText("");status.setText("接口地址已更改，请填写对应密钥并保存。");}}});
        page.setFocusableInTouchMode(true);page.requestFocus();busy(true);String address=endpoint.getText().toString();io.execute(()->{try{String credential=vault.load(address);main.post(()->{if(destroyed)return;setting=true;key.setText(credential);setting=false;busy(false);status.setText(credential.isEmpty()?"尚未保存密钥":"已读取加密保存的密钥");});}catch(Exception error){error("无法读取密钥，请重新填写或清除。");}});
    }
    private void busy(boolean value){endpoint.setEnabled(!value);model.setEnabled(!value);key.setEnabled(!value);save.setEnabled(!value);clear.setEnabled(!value);}
    private void error(String message){main.post(()->{if(!destroyed){busy(false);status.setText(message);}});}
    private void save(){String address=endpoint.getText().toString().trim(),name=model.getText().toString().trim(),credential=key.getText().toString().trim();if(name.isEmpty()||credential.isEmpty()){status.setText("请填写模型和 API Key");return;}busy(true);io.execute(()->{try{vault.save(address,credential);if(!getSharedPreferences("ai-config",0).edit().putString("endpoint",address).putString("model",name).commit())throw new java.io.IOException();main.post(()->{if(!destroyed){busy(false);status.setText("配置已保存，密钥已加密。下次自动读取。");}});}catch(Exception error){error("保存失败，请检查接口地址与密钥。");}});}
    private void clear(){busy(true);io.execute(()->{try{vault.clear();main.post(()->{if(!destroyed){key.setText("");busy(false);status.setText("密钥已清除，接口地址和模型保留。");}});}catch(Exception error){error("清除失败，请重试。");}});}
    @Override protected void onDestroy(){destroyed=true;if(key!=null)key.setText("");main.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}
