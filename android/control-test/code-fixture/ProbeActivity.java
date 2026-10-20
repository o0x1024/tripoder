package dev.tripoder.probefixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(32,128,32,64);
        TextView status=new TextView(this);status.setText("startup="+ProbeApplication.startup+" memory="+ProbeApplication.memoryValue+" file="+ProbeApplication.fileValue+" error="+ProbeApplication.failure);root.addView(status);
        TextView result=new TextView(this);root.addView(result);Button invoke=new Button(this);invoke.setText("Invoke payload");invoke.setOnClickListener(v->{try{result.setText("payload="+ProbeApplication.memoryMethod.invoke(null));}catch(Throwable error){result.setText(error.toString());}});root.addView(invoke);setContentView(root);
    }
}
