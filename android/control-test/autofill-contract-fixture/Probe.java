package dev.tripoder.autofillcontract;

import android.app.Activity;
import android.os.*;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.widget.*;
import org.json.JSONObject;

/** Exercises the real system Autofill callback with an owned guest's logical component. */
public final class Probe extends Activity {
    private EditText account;
    private TextView status;
    private boolean enabled,completed;
    private long duration=-1,lastTick,maxGap;
    private int ticks;
    private static volatile String receipt="{}";
    public static String result(){return receipt;}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48,120,48,48);root.setFocusableInTouchMode(true);
        account=new EditText(this);account.setHint("Owned empty input");account.setSingleLine(true);
        account.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        account.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        account.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        account.setId(1001);root.addView(account);
        Button begin=new Button(this);begin.setText("Start Autofill probe");
        begin.setOnClickListener(view->runProbe());root.addView(begin);
        status=new TextView(this);status.setTextSize(14);root.addView(status);
        setContentView(root);root.requestFocus();
        Handler handler=new Handler(Looper.getMainLooper());lastTick=SystemClock.uptimeMillis();
        handler.post(new Runnable(){public void run(){
            long now=SystemClock.uptimeMillis();maxGap=Math.max(maxGap,now-lastTick);lastTick=now;ticks++;
            publish();handler.postDelayed(this,100);
        }});
    }
    private void runProbe(){
        AutofillManager manager=getSystemService(AutofillManager.class);
        enabled=manager!=null&&manager.isEnabled();
        long start=SystemClock.uptimeMillis();
        if(enabled)manager.notifyViewEntered(account);
        duration=SystemClock.uptimeMillis()-start;completed=true;publish();
    }
    private void publish(){try{
        receipt=new JSONObject().put("logicalComponent",getComponentName().flattenToString())
            .put("enabled",enabled).put("completed",completed).put("durationMs",duration)
            .put("mainTicks",ticks).put("maxMainGapMs",maxGap).put("focused",account.hasFocus())
            .put("inputLength",account.length()).put("passed",enabled&&completed&&duration<1000&&maxGap<1000).toString();
        status.setText(receipt);
    }catch(Exception error){throw new IllegalStateException(error);}}
}
