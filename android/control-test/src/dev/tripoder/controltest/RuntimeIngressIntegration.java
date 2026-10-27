package dev.tripoder.controltest;

import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Real platform parcels and receiver dispatch, without starting a commercial guest. */
public final class RuntimeIngressIntegration extends ControlTestBase {
    public static final class Model implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        final String value="guest-model";
    }
    public static final class LazyModel implements Parcelable {
        static final AtomicInteger reads=new AtomicInteger();
        public int describeContents(){return 0;}
        public void writeToParcel(Parcel out,int flags){out.writeInt(42);}
        public static final Parcelable.Creator<LazyModel> CREATOR=new Parcelable.Creator<>(){
            public LazyModel createFromParcel(Parcel in){if(in.readInt()!=42)throw new IllegalStateException("payload");reads.incrementAndGet();return new LazyModel();}
            public LazyModel[] newArray(int size){return new LazyModel[size];}
        };
    }
    private static Object invoke(Class<?> type,String name,Class<?>[] types,Object...values)throws Exception{
        Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(null,values);}catch(InvocationTargetException error){throw new IllegalStateException(name,error.getCause());}
    }
    @Override void test()throws Exception{
        report.put("scope","actionless-filter-lazy-bundle-explicit-receiver-proxy");
        Class<?> payload=Class.forName("dev.tripoder.runtime.RuntimeGuestPayload");
        Bundle nested=new Bundle();nested.putSerializable("model",new Model());nested.putParcelable("lazy",new LazyModel());
        Bundle source=new Bundle();source.putString("route","metadata");source.putBundle("nested",nested);
        source.putParcelableArrayList("list",new ArrayList<>(List.of(new Bundle(nested))));
        IBinder binder=new Binder();source.putBinder("binder",binder);
        Bundle wrong;Parcel parcel=Parcel.obtain();try{parcel.writeBundle(source);parcel.setDataPosition(0);wrong=parcel.readBundle(null);}finally{parcel.recycle();}
        wrong.getString("route"); // capture the boot loader in the lazy entries
        Bundle copy=(Bundle)invoke(payload,"copy",new Class<?>[]{Bundle.class,ClassLoader.class},new Bundle(wrong),getClass().getClassLoader());
        check("normalization-does-not-instantiate-guest-parcelables",LazyModel.reads.get()==0,LazyModel.reads.get());
        Bundle child=copy.getBundle("nested");
        check("nested-serializable-resolves-in-guest-loader",child.getSerializable("model",Model.class).value.equals("guest-model"),true);
        check("nested-lazy-parcelable-resolves-on-demand",child.getParcelable("lazy",LazyModel.class)!=null&&LazyModel.reads.get()==1,LazyModel.reads.get());
        check("copied-bundle-retains-loader",new Bundle(child).getSerializable("model",Model.class)!=null,true);
        check("bundle-in-list-retains-loader",copy.getParcelableArrayList("list",Bundle.class).get(0).getSerializable("model",Model.class)!=null,true);
        check("binder-identity-survives-normalization",copy.getBinder("binder")==binder,true);
        Intent incoming=new Intent().putExtras(wrong);
        invoke(payload,"prepare",new Class<?>[]{Intent.class,ClassLoader.class},incoming,getClass().getClassLoader());
        check("intent-extras-copy-retains-nested-loader",new Intent(incoming).getBundleExtra("nested").getSerializable("model",Model.class)!=null,true);

        Class<?> broadcasts=Class.forName("dev.tripoder.runtime.RuntimeBroadcastIntent");
        IntentFilter filter=new IntentFilter();filter.addCategory("fixture.category");filter.addDataScheme("fixture");filter.addDataType("text/plain");
        byte[] bytes=(byte[])invoke(broadcasts,"filter",new Class<?>[]{IntentFilter.class},filter);
        IntentFilter decoded=(IntentFilter)invoke(broadcasts,"filter",new Class<?>[]{byte[].class},bytes);
        check("actionless-filter-keeps-data-and-category",decoded.countActions()==0&&decoded.hasCategory("fixture.category")&&decoded.hasDataScheme("fixture")&&decoded.hasDataType("text/plain"),true);
        AtomicInteger emptyDeliveries=new AtomicInteger();BroadcastReceiver empty=new BroadcastReceiver(){public void onReceive(Context c,Intent i){emptyDeliveries.incrementAndGet();}};
        getTargetContext().registerReceiver(empty,filter,Context.RECEIVER_NOT_EXPORTED);
        try{
            getTargetContext().sendBroadcast(new Intent("fixture.action").setPackage(enginePackage()).setDataAndType(android.net.Uri.parse("fixture://test"),"text/plain").addCategory("fixture.category"));
            Thread.sleep(100);
            check("platform-actionless-registration-has-no-action-delivery",emptyDeliveries.get()==0,emptyDeliveries.get());
            check("runtime-actionless-match-agrees",!(Boolean)invoke(broadcasts,"matches",new Class<?>[]{IntentFilter.class,Intent.class},decoded,new Intent("fixture.action")),true);
        }finally{getTargetContext().unregisterReceiver(empty);}

        System.loadLibrary("tripoderselfhook");
        Class.forName("dev.tripoder.selfhook.FrameworkAccess").getMethod("prepare").invoke(null);
        Class.forName("dev.tripoder.selfhook.ComponentFrameworkAccess").getMethod("prepare").invoke(null);
        Class<?> hooks=Class.forName("dev.tripoder.selfhook.ManagedHook");
        JSONObject before=new JSONObject((String)hooks.getMethod("publicationMetrics").invoke(null));
        receiverProxy(false);receiverProxy(true);
        JSONObject after=new JSONObject((String)hooks.getMethod("publicationMetrics").invoke(null));
        check("receiver-proxy-publishes-no-art-hooks",before.getLong("toggleCalls")==after.getLong("toggleCalls")&&after.getInt("bindings")==0,after);
    }
    private void receiverProxy(boolean async)throws Exception{
        String action="dev.tripoder.INGRESS_"+run+"_"+async;CountDownLatch called=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicInteger deliveries=new AtomicInteger(),afters=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicReference<BroadcastReceiver.PendingResult> held=new AtomicReference<>();
        BroadcastReceiver guest=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){try{
            if(!action.equals(intent.getAction())||!isOrderedBroadcast())throw new IllegalStateException("delivery-state");
            setResultCode(17);setResultData("guest-result");deliveries.incrementAndGet();
            if(async)held.set(goAsync());
        }catch(Throwable error){failure.set(error);}finally{called.countDown();}}};
        Class<?> type=Class.forName("dev.tripoder.runtime.RuntimeReceiverProxy"),route=Class.forName("dev.tripoder.runtime.RuntimeReceiverProxy$Delivery");
        Object adapter=Proxy.newProxyInstance(route.getClassLoader(),new Class<?>[]{route},(proxy,method,args)->switch(method.getName()){
            case "before"->{if(args[2]==null)throw new IllegalStateException("missing-pending");yield args[1];}
            case "context"->args[0];
            case "after"->{if(!Boolean.TRUE.equals(args[1]))throw new IllegalStateException("delivery-failed");afters.incrementAndGet();yield null;}
            default->throw new UnsupportedOperationException(method.getName());
        });
        Constructor<?> constructor=type.getDeclaredConstructor(BroadcastReceiver.class,route);constructor.setAccessible(true);
        BroadcastReceiver wrapper=(BroadcastReceiver)constructor.newInstance(guest,adapter);
        getTargetContext().registerReceiver(wrapper,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        try{
            getTargetContext().sendOrderedBroadcast(new Intent(action).setPackage(enginePackage()),null,new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
                if(getResultCode()!=17||!"guest-result".equals(getResultData()))failure.set(new IllegalStateException("ordered-result"));finished.countDown();
            }},null,0,null,null);
            check("proxy-delivery-"+async,called.await(5,TimeUnit.SECONDS)&&failure.get()==null,Objects.toString(failure.get()));
            if(async){check("goAsync-keeps-platform-result-pending",held.get()!=null&&!finished.await(100,TimeUnit.MILLISECONDS),true);held.get().finish();}
            check("proxy-platform-completion-"+async,finished.await(5,TimeUnit.SECONDS)&&failure.get()==null&&deliveries.get()==1&&afters.get()==1,Objects.toString(failure.get()));
        }finally{getTargetContext().unregisterReceiver(wrapper);}
    }
}
