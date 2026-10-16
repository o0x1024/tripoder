package dev.tripoder.p2;

import android.app.Activity;
import android.content.*;
import android.os.*;
import org.json.JSONObject;

public final class ResultFlows {
    public static final class ControllerBoundary extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);try{
            JSONObject result=new JSONObject();
            java.io.File file=new java.io.File(getFilesDir(),"owned-result-fd");try(var out=new java.io.FileOutputStream(file)){out.write(1);}
            try(var fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
                setResult(RESULT_OK,new Intent().putExtra("result","invalid").putExtra("fd",fd));boolean blocked=false;try{finish();}catch(RuntimeException expected){blocked=String.valueOf(expected).contains("RESULT_EXTRAS_UNSUPPORTED");}result.put("fdBlocked",blocked).put("stillLive",!isFinishing());
            }
            Intent clip=new Intent();clip.setClipData(ClipData.newPlainText("owned","owned"));setResult(RESULT_OK,clip);boolean blocked=false;try{finish();}catch(RuntimeException expected){blocked=String.valueOf(expected).contains("RESULT_SHAPE_UNSUPPORTED");}result.put("clipBlocked",blocked);
            setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));finish();
        }catch(Exception error){throw new IllegalStateException("RESULT_BOUNDARY_TEST_FAILED",error);}}
    }
    public static final class Cancel extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);finish();}
    }
    public static final class Replace extends Activity {
        @Override public void onCreate(Bundle state){super.onCreate(state);setResult(RESULT_OK,new Intent().putExtra("result","stale"));setResult(RESULT_OK,new Intent().putExtra("result","fresh"));new Handler(Looper.getMainLooper()).postDelayed(this::finish,1200);}
    }
    public static final class Parent extends Activity {
        private boolean recreated;
        @Override public void onCreate(Bundle state){super.onCreate(state);recreated=state!=null&&state.getBoolean("owned-state");
            if(state==null){boolean cancel=getIntent().getBooleanExtra("cancel",false);startActivityForResult(new Intent().setComponent(new ComponentName(getPackageName(),(cancel?Cancel.class:Replace.class).getName())),7);if(!cancel)new Handler(Looper.getMainLooper()).postDelayed(this::recreate,100);}}
        @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("owned-state",true);super.onSaveInstanceState(state);}
        @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);try{
            if(request!=7)throw new IllegalStateException("FIXTURE_REQUEST_CODE_MISMATCH");
            JSONObject result=new JSONObject().put("challenge",getIntent().getStringExtra("challenge")).put("recreated",recreated).put("code",code).put("value",data==null?JSONObject.NULL:data.getStringExtra("result"));
            setResult(RESULT_OK,new Intent().putExtra("result",result.toString()));finish();
        }catch(Exception error){throw new IllegalStateException("FIXTURE_RESULT_FLOW_FAILED",error);}}
    }
}
