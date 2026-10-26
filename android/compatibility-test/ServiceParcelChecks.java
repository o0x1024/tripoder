package dev.tripoder.runtime;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BiConsumer;

/** Actual Android Parcel mutation and capability checks; no Service is dispatched. */
final class ServiceParcelChecks {
    public static final class Payload implements Parcelable {
        int value,writes;
        Payload(int value){this.value=value;}
        public int describeContents(){return 0;}
        public void writeToParcel(Parcel parcel,int flags){writes++;parcel.writeInt(value);}
        public static final Parcelable.Creator<Payload> CREATOR=new Parcelable.Creator<>(){
            public Payload createFromParcel(Parcel parcel){return new Payload(parcel.readInt());}
            public Payload[] newArray(int size){return new Payload[size];}
        };
    }
    private interface Action{void run()throws Exception;}
    private static void denied(BiConsumer<String,Boolean> check,String name,Action action,String code)throws Exception{
        boolean denied=false;try{action.run();}catch(IllegalArgumentException error){denied=code.equals(error.getMessage());}
        check.accept(name,denied);
    }
    static void run(BiConsumer<String,Boolean> check)throws Exception{
        ComponentName component=new ComponentName("dev.tripoder.owned.parcel","dev.tripoder.owned.parcel.Service");
        Payload payload=new Payload(7);Bundle nested=new Bundle();nested.putIntArray("numbers",new int[]{3,5,8});
        Intent source=new Intent("owned.PARCEL").setComponent(component).putExtra("payload",payload).putExtra("nested",nested).putExtra("nullable",(String)null);
        RuntimeServiceIntent.Request request=RuntimeServiceIntent.snapshot(source);
        source.setAction("owned.MUTATED").setComponent(new ComponentName("dev.tripoder.other","dev.tripoder.other.Service"));payload.value=99;nested.putIntArray("numbers",new int[]{99});
        byte[] encoded=request.payload();
        check.accept("service-parcel-digest-identifies-exact-transport-bytes",request.digest.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded))));
        check.accept("service-parcel-metadata-retains-captured-selector",request.intent.getComponent().equals(component)&&"owned.PARCEL".equals(request.intent.getAction())&&request.intent.getExtras()==null);
        Intent restored=RuntimeServiceIntent.restore(encoded,ServiceParcelChecks.class.getClassLoader());
        check.accept("service-parcel-owned-loader-restores-custom-payload",restored.getParcelableExtra("payload",Payload.class).value==7);
        check.accept("service-parcel-nested-mutation-cannot-change-captured-request",Arrays.equals(restored.getBundleExtra("nested").getIntArray("numbers"),new int[]{3,5,8}));
        check.accept("service-parcel-keeps-null-extra-and-original-action",restored.hasExtra("nullable")&&restored.getStringExtra("nullable")==null&&restored.getAction().equals("owned.PARCEL"));
        check.accept("service-parcel-user-payload-serialized-once",payload.writes==1);
        encoded[0]^=1;check.accept("service-parcel-copy-cannot-change-held-snapshot",!Arrays.equals(encoded,request.payload())&&RuntimeServiceIntent.restore(request.payload(),ServiceParcelChecks.class.getClassLoader()).getComponent().equals(component));
        byte[] trailing=Arrays.copyOf(request.payload(),request.payload().length+4);
        denied(check,"service-parcel-trailing-data-rejected",()->RuntimeServiceIntent.restore(trailing,ServiceParcelChecks.class.getClassLoader()),"SERVICE_PAYLOAD_TRAILING_DATA");
        denied(check,"service-parcel-missing-envelope-rejected",()->RuntimeServiceIntent.restore(null,ServiceParcelChecks.class.getClassLoader()),"SERVICE_PAYLOAD_INVALID");
        denied(check,"service-parcel-reserved-key-rejected",()->RuntimeServiceIntent.snapshot(new Intent().setComponent(component).putExtra(RuntimeServiceIntent.PAYLOAD,new byte[]{1})),"SERVICE_EXTRA_KEY_UNSUPPORTED");
        denied(check,"service-parcel-uri-shape-guard-retained",()->RuntimeServiceIntent.snapshot(new Intent().setComponent(component).setData(Uri.parse("content://owned/item"))),"SERVICE_INTENT_SHAPE_UNSUPPORTED");
        denied(check,"service-parcel-size-bound-uses-encoded-bytes",()->RuntimeServiceIntent.snapshot(new Intent().setComponent(component).putExtra("large","x".repeat(26000))),"SERVICE_INTENT_TOO_LARGE");
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        try{denied(check,"service-parcel-fd-not-converted-or-dropped",()->RuntimeServiceIntent.snapshot(new Intent().setComponent(component).putExtra("fd",pipe[0])),"SERVICE_INTENT_TOO_LARGE");}
        finally{pipe[0].close();pipe[1].close();}
        Binder binder=new Binder();RuntimeException nativeFailure=null,requestFailure=null;Parcel nativeParcel=Parcel.obtain();
        try{nativeParcel.writeStrongBinder(binder);try{nativeParcel.marshall();}catch(RuntimeException expected){nativeFailure=expected;}}
        finally{nativeParcel.recycle();}
        Bundle capability=new Bundle();capability.putBinder("binder",binder);
        try{RuntimeServiceIntent.snapshot(new Intent().setComponent(component).putExtras(capability));}catch(RuntimeException expected){requestFailure=expected;}
        check.accept("service-parcel-binder-keeps-exact-native-refusal",nativeFailure!=null&&requestFailure!=null&&requestFailure.getClass()==nativeFailure.getClass()&&Objects.equals(requestFailure.getMessage(),nativeFailure.getMessage())&&capability.getBinder("binder")==binder);
        ComponentName physicalComponent=new ComponentName("dev.tripoder.owned.host","dev.tripoder.owned.host.Stub");
        Intent physical=new Intent(request.intent).setComponent(physicalComponent).putExtra(RuntimeServiceIntent.PAYLOAD,request.payload()).addFlags(Intent.FLAG_FROM_BACKGROUND);
        check.accept("service-parcel-delivery-retains-native-selector-and-opaque-payload",RuntimeServiceIntent.delivery(physical,component,ServiceParcelChecks.class.getClassLoader()).getParcelableExtra("payload",Payload.class).value==7);
        boolean selectorDenied=false;try{RuntimeServiceIntent.delivery(new Intent(physical).setAction("owned.FORGED"),component,ServiceParcelChecks.class.getClassLoader());}catch(SecurityException expected){selectorDenied="SERVICE_PAYLOAD_SELECTOR_MISMATCH".equals(expected.getMessage());}
        check.accept("service-parcel-modified-outer-selector-rejected",selectorDenied);
        boolean componentDenied=false;try{RuntimeServiceIntent.delivery(physical,physicalComponent,ServiceParcelChecks.class.getClassLoader());}catch(SecurityException expected){componentDenied="SERVICE_PAYLOAD_COMPONENT_MISMATCH".equals(expected.getMessage());}
        check.accept("service-parcel-wrong-inner-component-rejected",componentDenied);
    }
}
