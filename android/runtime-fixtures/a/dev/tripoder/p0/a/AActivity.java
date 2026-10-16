package dev.tripoder.p0.a;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import dev.tripoder.p0.shared.*;
import org.json.JSONObject;

public final class AActivity extends Activity {
    private JSONObject report;
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        setContentView(getResources().getIdentifier("screen","layout",getPackageName()));
        report=Observation.read(this).put("recreated",state!=null&&state.getBoolean("saved"));
        report.put("nativeCanReadHostCanary",NativeProbe.canRead(getIntent().getStringExtra("hostCanary")));
        report.put("nativeRawCanReadHostCanary",NativeProbe.canReadRaw(getIntent().getStringExtra("hostCanary")));
        String mode=getIntent().getStringExtra("mode");
        if("capability-checks".equals(mode)){report.put("capabilities",CapabilityChecks.run(this));complete();return;}
        if("recreate".equals(mode)&&state==null){recreate();return;}
        Intent target=new Intent().setClassName("dev.tripoder.p0.b","dev.tripoder.p0.b.BActivity").putExtra("challenge",getIntent().getStringExtra("challenge")).putExtra("scenario",mode);
        try{
            if("context-denied".equals(mode)){getApplicationContext().startActivity(target,new Bundle());report.put("route","unexpected-context-start");complete();}
            else startActivityForResult(target,41);
        }catch(ActivityNotFoundException e){report.put("route","missing").put("error",e.getMessage());complete();}
        catch(UnsupportedOperationException e){if(!"context-denied".equals(mode))throw e;report.put("route","context-denied");complete();}
    }catch(Exception e){throw new IllegalStateException("FIXTURE_A_FAILED",e);}}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("saved",true);super.onSaveInstanceState(state);}
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{
        if(request==41&&code==RESULT_CANCELED&&"return-without-finish".equals(getIntent().getStringExtra("mode"))){report.put("route","cancelled");complete();return;}
        if(request!=41||code!=RESULT_OK||data==null)throw new IllegalStateException("BAD_COMPONENT_RESULT");
        // Give the finished target time to die before the source acknowledgement.
        if("finish-then-die".equals(getIntent().getStringExtra("mode")))Thread.sleep(1500);
        report.put("route","roundtrip").put("b",new JSONObject(data.getStringExtra("result")));complete();
    }catch(Exception e){throw new IllegalStateException("FIXTURE_RESULT_FAILED",e);}}
    private void complete(){setResult(RESULT_OK,new Intent().putExtra("result",report.toString()));finish();}
}
