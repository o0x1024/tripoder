package dev.tripoder.resultcontract;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.widget.TextView;
import org.json.*;

/** Same-package Android results, including a real cross-process Serializable round trip. */
public final class Probe extends Activity {
    private static volatile String receipt="{}";
    private final JSONArray cases=new JSONArray();
    private TextView status;
    private int next,rejectedCases;
    private boolean crossProcess,controllerBlocked;
    public static String result(){return receipt;}
    static void blocked(String type){try{receipt=new JSONObject().put("passed",false).put("rejectedResult",type).toString();}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);status=new TextView(this);status.setText("Result contract running");setContentView(status);
        if(state==null)new Handler(Looper.getMainLooper()).post(this::launch);
    }
    private void launch(){startActivityForResult(new Intent(this,next==0?Child.class:Child.Remote.class).putExtra("mode",next),100+next);}
    @Override protected void onActivityResult(int request,int code,Intent data){
        super.onActivityResult(request,code,data);
        try{
            Model value=data==null?null:data.getSerializableExtra("model",Model.class);
            int expected=next==0?RESULT_OK:next==1?RESULT_CANCELED:RESULT_FIRST_USER+7;
            boolean valid=request==100+next&&code==expected&&value!=null&&value.outcome==Model.Outcome.values()[next]
                &&value.detail.precise==9007199254740993L&&"owned-result".equals(value.detail.marker)
                &&value.bytes.length==2&&value.bytes[0]==4&&value.bytes[1]==2
                &&getPackageName().equals(value.caller)&&value.getClass().getClassLoader()==getClassLoader();
            if(!valid)throw new IllegalStateException("RESULT_ROUND_TRIP_MISMATCH");
            if(next==1)rejectedCases=value.rejectedCases;
            if(next>0)crossProcess|=value.senderPid!=android.os.Process.myPid();
            cases.put(new JSONObject().put("case",value.outcome.name()).put("resultCode",code).put("sameReceiverLoader",true));
            if(++next<3){new Handler(Looper.getMainLooper()).post(this::launch);return;}
            setResult(RESULT_OK,new Intent().putExtra("model",new Model(0,getPackageName())));
            try{finish();}catch(IllegalArgumentException expectedRejection){controllerBlocked=String.valueOf(expectedRejection.getMessage()).startsWith("RESULT_EXTRAS_UNSUPPORTED");}
            setResult(RESULT_CANCELED,null);
            receipt=new JSONObject().put("passed",cases.length()==3&&crossProcess&&controllerBlocked&&rejectedCases==31)
                .put("cases",cases).put("crossProcess",crossProcess).put("controllerSerializableBlocked",controllerBlocked)
                .put("rejectedCases",rejectedCases).put("logicalComponent",getComponentName().flattenToString()).toString();
            status.setText(receipt);
        }catch(Exception error){blocked(error.getClass().getName()+":"+error.getMessage());status.setText(receipt);}
    }
}
