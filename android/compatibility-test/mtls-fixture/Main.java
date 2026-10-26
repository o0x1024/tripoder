package dev.tripoder.mtlsfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import java.util.UUID;

public final class Main extends Activity {
    private static volatile String result="{\"state\":\"READY\"}";
    public static String summary(){return result;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        Button run=new Button(this);run.setText("Run owned mTLS");TextView text=new TextView(this);text.setText(result);layout.addView(run);layout.addView(text);setContentView(layout);
        run.setOnClickListener(view->{
            run.setEnabled(false);result="{\"state\":\"RUNNING\"}";text.setText(result);
            new Thread(()->{
                try{result=MtlsChecks.run(new TlsMaterial(name->getAssets().open(name)),UUID.randomUUID().toString());}
                catch(Exception error){result="{\"passed\":false,\"error\":\""+error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()).replace("\"", "'").replace("\n"," ")+"\"}";}
                runOnUiThread(()->{text.setText(result);run.setEnabled(true);});
            },"owned-mtls-client").start();
        });
    }
}
