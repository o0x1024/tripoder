package dev.tripoder.resultcontract;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.widget.TextView;
import java.io.Serializable;

public class Child extends Activity {
    public static final class Remote extends Child {}
    private static final class SerializableBinder extends Binder implements Serializable {private static final long serialVersionUID=1L;}
    public static final class HiddenBinder implements Parcelable {
        public int describeContents(){return 0;}
        public void writeToParcel(Parcel parcel,int flags){parcel.writeStrongBinder(new Binder());}
        public static final Creator<HiddenBinder> CREATOR=new Creator<>(){
            public HiddenBinder createFromParcel(Parcel parcel){parcel.readStrongBinder();return new HiddenBinder();}
            public HiddenBinder[] newArray(int count){return new HiddenBinder[count];}
        };
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);new Handler(Looper.getMainLooper()).postDelayed(this::complete,400);}
    private boolean rejected(Intent result){
        setResult(RESULT_OK,result);
        try{finish();return false;}catch(RuntimeException expected){return !isFinishing();}
    }
    private void complete(){try{
        int mode=getIntent().getIntExtra("mode",0),checks=0;
        // The first round trip is the pre-fix negative gate. Capability rejection
        // checks run only after that round trip succeeds on the repaired runtime.
        if(mode==1){
            if(rejected(new Intent().putExtra("foreign",new java.util.Date(1))))checks|=1;
            Model large=new Model(0,getPackageName());large.bytes=new byte[64*1024];
            if(rejected(new Intent().putExtra("model",large)))checks|=2;
            java.io.File path=new java.io.File(getFilesDir(),"result-fd-test");
            try(var out=new java.io.FileOutputStream(path)){out.write(1);}
            try(var descriptor=ParcelFileDescriptor.open(path,ParcelFileDescriptor.MODE_READ_ONLY)){
                if(rejected(new Intent().putExtra("fd",descriptor)))checks|=4;
            }
            if(rejected(new Intent().putExtra("binder",(Serializable)new SerializableBinder())))checks|=8;
            if(rejected(new Intent().putExtra("hiddenBinder",new HiddenBinder())))checks|=16;
        }
        Model model=new Model(mode,getCallingPackage());model.rejectedCases=checks;
        setResult(mode==0?RESULT_OK:mode==1?RESULT_CANCELED:RESULT_FIRST_USER+7,new Intent().putExtra("model",model));
        try{finish();}catch(RuntimeException rejected){
            // The pre-fix run deliberately stays visible and records its negative
            // gate. It never substitutes a successful result for the rejection.
            Probe.blocked(rejected.getClass().getName()+":"+rejected.getMessage());
            TextView view=new TextView(this);view.setText(Probe.result());setContentView(view);
        }
    }catch(Exception error){throw new IllegalStateException("RESULT_CONTRACT_SETUP_FAILED",error);}}
}
