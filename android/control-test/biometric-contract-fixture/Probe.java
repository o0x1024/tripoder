package dev.tripoder.biometriccontract;

import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.*;
import android.widget.*;
import org.json.JSONObject;

/** Uses real system authentication and cancels without collecting a biometric. */
public final class Probe extends Activity {
    private static volatile String receipt="{}";
    private final JSONObject report=new JSONObject();
    private TextView status;
    public static String result(){return receipt;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(40,120,40,40);
        Button begin=new Button(this);begin.setText("Run biometric boundary probe");begin.setOnClickListener(v->run());root.addView(begin);
        status=new TextView(this);root.addView(status);setContentView(root);
    }
    private void run(){
        try{
            Context context=getIntent().getBooleanExtra("applicationContext",false)?getApplicationContext():this;
            String logical=context.getPackageName();
            report.put("logicalPackage",logical).put("opPackage",context.getOpPackageName())
                .put("permissionDeclared",checkSelfPermission("android.permission.USE_BIOMETRIC")==0)
                .put("contextKind",context==this?"activity":"application");
            CancellationSignal cancel=new CancellationSignal();
            BiometricPrompt prompt=new BiometricPrompt.Builder(context).setTitle("Owned boundary probe")
                .setNegativeButton("Cancel",getMainExecutor(),(dialog,which)->{}).build();
            prompt.authenticate(cancel,getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationError(int code,CharSequence message){
                    try{report.put("systemCallback",true).put("errorCode",code);publish();}catch(Exception error){throw new IllegalStateException(error);}
                }
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){
                    try{report.put("unexpectedAuthentication",true);publish();}catch(Exception error){throw new IllegalStateException(error);}
                }
            });
            report.put("requestReturned",true).put("originalContextUnchanged",logical.equals(context.getPackageName()));
            // Cancellation exercises the same prompt's real platform identity without a user's biometric.
            new Handler(Looper.getMainLooper()).postDelayed(()->{cancel.cancel();try{report.put("cancelSent",true);publish();}catch(Exception error){throw new IllegalStateException(error);}},700);
        }catch(Throwable error){try{report.put("exception",error.getClass().getName()).put("message",String.valueOf(error.getMessage()));}catch(Exception failure){throw new IllegalStateException(failure);}}
        publish();
    }
    private void publish(){receipt=report.toString();status.setText(receipt);}
}
