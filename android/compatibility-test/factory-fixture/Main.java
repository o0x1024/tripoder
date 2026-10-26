package dev.tripoder.factorycontract;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONObject;

/** Deliberately absent declared factory; only the real SDK decides its contract. */
public final class Main extends Activity {
    static volatile JSONObject lastResult;
    public static final class OwnedSavedFragment extends android.app.Fragment {
        public OwnedSavedFragment(){android.util.Log.i("TripoderFactoryContract","OWNED_FRAGMENT_CONSTRUCTED");}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{
            String declared=getApplicationInfo().appComponentFactory;
            boolean absent=false;try{Class.forName(declared,false,getClassLoader());}catch(ClassNotFoundException expected){absent=true;}
            boolean application=OwnedApplication.creates.get()==1;
            boolean identity=getPackageName().equals("dev.tripoder.factorycontract")&&getApplication() instanceof OwnedApplication;
            boolean unchanged="dev.tripoder.factorycontract.NotInOuterDex".equals(declared),observed=DeliveryObservation.installed&&DeliveryObservation.launches>0&&DeliveryObservation.failureType.isEmpty();
            boolean factory=Main.class.getName().equals(DeliveryObservation.factoryName);
            boolean alias=getIntent().getComponent()!=null&&getIntent().getComponent().getClassName().equals("dev.tripoder.factorycontract.Entry")&&getComponentName().equals(getIntent().getComponent());
            JSONObject result=new JSONObject().put("passed",absent&&application&&identity&&unchanged&&observed&&DeliveryObservation.executableLoadable&&DeliveryObservation.executableIsMain&&factory&&alias)
                .put("checks",9).put("factoryDeclarationUnchanged",unchanged).put("factoryNotLoadable",absent).put("applicationOnCreateOnce",application).put("ownedActivityCreated",identity)
                .put("earlyLaunchObserved",observed).put("earlyExecutableLoadable",DeliveryObservation.executableLoadable).put("earlyExecutableIsMain",DeliveryObservation.executableIsMain)
                .put("factoryReceivesMain",factory).put("aliasIntentAndActivityIdentityPreserved",alias).put("delivery",DeliveryObservation.snapshot());
            result.put("publicFactoryAndAliasChecksPassed",absent&&application&&identity&&unchanged&&alias).put("privateDeliveryObservationStatus",observed?"OBSERVED":"UNAVAILABLE_NOT_ACCEPTED");
            lastResult=result;
            android.util.Log.i("TripoderFactoryContract",result.toString());
            TextView view=new TextView(this);view.setText(result.toString());view.setTextSize(19);setContentView(view);
            if(state==null)getFragmentManager().beginTransaction().add(new OwnedSavedFragment(),"owned-saved-fragment").commitNow();
        }catch(Exception error){throw new IllegalStateException("OWNED_FACTORY_CONTRACT_OBSERVATION_FAILED",error);}
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);android.util.Log.i("TripoderFactoryContract","OWNED_FRAGMENT_STATE_SAVED="+state.containsKey("android:fragments"));}
}
