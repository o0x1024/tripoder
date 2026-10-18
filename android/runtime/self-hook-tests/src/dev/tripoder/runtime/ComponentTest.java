package dev.tripoder.runtime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ComponentTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    @FunctionalInterface private interface Action{void run()throws Throwable;}
    private static void sameFailure(Action action,Throwable expected)throws Throwable {
        try{action.run();throw new AssertionError("failure lost");}catch(Throwable actual){check(actual==expected);}
    }
    private static void rejects(Action action,String code)throws Throwable {
        try{action.run();throw new AssertionError("accepted "+code);}
        catch(IllegalArgumentException error){check(error.getMessage().equals(code));}
    }
    public static class Base {public Object lifecycle(Object intent,int flags,int id){return null;}public void receive(Object context,Object intent){}}
    public static final class Child extends Base {@Override public Object lifecycle(Object intent,int flags,int id){return null;}@Override public void receive(Object context,Object intent){}}
    private static final Object PHYSICAL=new Object(),LOGICAL=new Object(),CONTEXT=new Object(),GUEST_CONTEXT=new Object(),PENDING=new Object(),RESULT=new Object();

    private static final class ServiceRoute implements RuntimeSelfHookLifecycle.ServiceRoute {
        boolean owned=true;final List<String> events=new ArrayList<>();Throwable completionFailure;
        public boolean owns(Object value){events.add("owns");return owned;}
        public Object intent(Object value,Object intent){events.add("intent");check(intent==PHYSICAL);return LOGICAL;}
        public void completed(Object value,String event)throws Throwable {events.add("event:"+event);if(completionFailure!=null)throw completionFailure;}
    }
    private static final class ReceiverRoute implements RuntimeSelfHookLifecycle.ReceiverRoute {
        boolean owned=true,closed,local;final List<String> events=new ArrayList<>();Throwable afterFailure;RuntimeException contextFailure;
        public boolean owns(Object value){events.add("owns");return owned;}
        public Object pending(Object value){events.add("pending");return local?null:PENDING;}
        public Object before(Object value,Object intent,Object pending){events.add("before");check(intent==PHYSICAL&&pending==PENDING);return closed?null:LOGICAL;}
        public Object context(Object value){events.add("context");check(value==CONTEXT);if(contextFailure!=null)throw contextFailure;return GUEST_CONTEXT;}
        public void after(Object value,boolean success)throws Throwable {events.add("after:"+success);if(afterFailure!=null)throw afterFailure;}
    }
    private static final class ProviderRoute implements RuntimeSelfHookProviderCalls.Route {
        boolean owned=true,missing,stable,existing,nullHandle;Object handle=new Object(),provider=new Object(),settings=new Object();
        Throwable handleFailure,rememberFailure,closeFailure;final List<String> events=new ArrayList<>();
        public boolean owns(Object resolver){events.add("owns");return owned;}
        public Object settings(){events.add("settings");return settings;}
        public Object acquire(String authority,boolean stable,boolean existing){events.add("acquire:"+authority);this.stable=stable;this.existing=existing;return missing?null:provider;}
        public Object handle(Object provider)throws Throwable {events.add("handle");check(provider==this.provider||provider==settings);if(handleFailure!=null)throw handleFailure;return nullHandle?null:handle;}
        public void remember(Object handle,Object provider)throws Throwable {events.add("remember");check(handle==this.handle&&provider==this.provider);if(rememberFailure!=null)throw rememberFailure;}
        public void close(Object provider)throws Throwable {events.add("close");check(provider==this.provider);if(closeFailure!=null)throw closeFailure;}
        public void release(Object handle){events.add("release");check(handle==this.handle);}
    }
    public static void main(String[] args)throws Throwable {
        Child receiver=new Child();
        Method service=Child.class.getMethod("lifecycle",Object.class,int.class,int.class);
        Method baseService=Base.class.getMethod("lifecycle",Object.class,int.class,int.class);
        Object[] physical={PHYSICAL,Integer.MIN_VALUE,Integer.MAX_VALUE};
        ServiceRoute sr=new ServiceRoute();
        check(RuntimeSelfHookLifecycle.service(sr,service,receiver,physical,a->{
            sr.events.add("original");check(a[0]==LOGICAL);check(a[1].equals(Integer.MIN_VALUE)&&a[2].equals(Integer.MAX_VALUE));return RESULT;})==RESULT);
        check(physical[0]==PHYSICAL);check(sr.events.equals(List.of("owns","intent","original","event:lifecycle")));
        sr.events.clear();sr.owned=false;
        RuntimeSelfHookLifecycle.service(sr,service,receiver,physical,a->{check(a==physical);return RESULT;});
        check(sr.events.equals(List.of("owns")));
        sr.events.clear();sr.owned=true;
        RuntimeSelfHookLifecycle.service(sr,baseService,receiver,physical,a->{check(a==physical);return RESULT;});
        check(sr.events.equals(List.of("owns"))); // A super call must not translate again or emit another lifecycle event.
        sr.events.clear();RuntimeException originalFailure=new RuntimeException("original");
        sameFailure(()->RuntimeSelfHookLifecycle.service(sr,service,receiver,physical,a->{throw originalFailure;}),originalFailure);
        check(sr.events.equals(List.of("owns","intent")));
        RuntimeException acknowledgement=new RuntimeException("ack");sr.events.clear();sr.completionFailure=acknowledgement;
        sameFailure(()->RuntimeSelfHookLifecycle.service(sr,service,receiver,physical,a->{sr.events.add("original");return RESULT;}),acknowledgement);
        check(sr.events.equals(List.of("owns","intent","original","event:lifecycle")));

        Method receive=Child.class.getMethod("receive",Object.class,Object.class);
        Method baseReceive=Base.class.getMethod("receive",Object.class,Object.class);
        Object[] delivery={CONTEXT,PHYSICAL};ReceiverRoute rr=new ReceiverRoute();
        RuntimeSelfHookLifecycle.receiver(rr,receive,receiver,delivery,a->{rr.events.add("original");check(a[0]==GUEST_CONTEXT&&a[1]==LOGICAL);return null;});
        check(rr.events.equals(List.of("owns","pending","before","context","original","after:true")));
        check(delivery[0]==CONTEXT&&delivery[1]==PHYSICAL);
        rr.events.clear();rr.local=true;
        check(RuntimeSelfHookLifecycle.receiver(rr,receive,receiver,delivery,a->{check(a==delivery);return RESULT;})==RESULT);
        check(rr.events.equals(List.of("owns","pending")));rr.local=false;
        rr.events.clear();rr.owned=false;
        RuntimeSelfHookLifecycle.receiver(rr,receive,receiver,delivery,a->{check(a==delivery);return null;});check(rr.events.equals(List.of("owns")));
        rr.events.clear();rr.owned=true;
        RuntimeSelfHookLifecycle.receiver(rr,baseReceive,receiver,delivery,a->{check(a==delivery);return null;});check(rr.events.equals(List.of("owns")));
        rr.events.clear();rr.closed=true;
        RuntimeSelfHookLifecycle.receiver(rr,receive,receiver,delivery,a->{throw new AssertionError("retired delivery invoked");});
        check(rr.events.equals(List.of("owns","pending","before")));
        rr.events.clear();rr.closed=false;
        sameFailure(()->RuntimeSelfHookLifecycle.receiver(rr,receive,receiver,delivery,a->{throw originalFailure;}),originalFailure);
        check(rr.events.equals(List.of("owns","pending","before","context","after:false")));
        rr.events.clear();rr.contextFailure=originalFailure;
        sameFailure(()->RuntimeSelfHookLifecycle.receiver(rr,receive,receiver,delivery,a->{throw new AssertionError("invalid context invoked");}),originalFailure);
        check(rr.events.equals(List.of("owns","pending","before","context","after:false")));
        rr.events.clear();rr.contextFailure=null;rr.afterFailure=acknowledgement;
        sameFailure(()->RuntimeSelfHookLifecycle.receiver(rr,receive,receiver,delivery,a->{throw originalFailure;}),originalFailure);
        check(Arrays.equals(originalFailure.getSuppressed(),new Throwable[]{acknowledgement}));
        sameFailure(()->RuntimeSelfHookLifecycle.receiver(rr,receive,receiver,delivery,a->null),acknowledgement);

        ProviderRoute pr=new ProviderRoute();Object resolver=new Object();
        check(RuntimeSelfHookProviderCalls.acquire(pr,resolver,"owned",false,true,a->{throw new AssertionError("owned acquisition escaped");})==pr.handle);
        check(!pr.stable&&pr.existing);check(pr.events.equals(List.of("owns","acquire:owned","handle","remember")));
        pr.events.clear();pr.owned=false;
        check(RuntimeSelfHookProviderCalls.acquire(pr,resolver,"physical",true,false,a->{check(a[0]==CONTEXT&&a[1].equals("physical"));return RESULT;},CONTEXT,"physical")==RESULT);
        check(pr.events.equals(List.of("owns")));
        pr.events.clear();pr.owned=true;
        check(RuntimeSelfHookProviderCalls.acquire(pr,resolver,"settings",true,false,a->{throw new AssertionError();})==pr.handle);
        check(pr.events.equals(List.of("owns","settings","handle")));
        pr.events.clear();pr.missing=true;
        check(RuntimeSelfHookProviderCalls.acquire(pr,resolver,"absent",false,true,a->{throw new AssertionError();})==null);
        check(pr.events.equals(List.of("owns","acquire:absent")));
        pr.events.clear();pr.missing=false;pr.handleFailure=originalFailure;
        sameFailure(()->RuntimeSelfHookProviderCalls.acquire(pr,resolver,"owned",true,false,a->{throw new AssertionError();}),originalFailure);
        check(pr.events.equals(List.of("owns","acquire:owned","handle","close")));
        pr.events.clear();pr.handleFailure=null;pr.rememberFailure=originalFailure;pr.closeFailure=new RuntimeException("close");
        sameFailure(()->RuntimeSelfHookProviderCalls.acquire(pr,resolver,"owned",true,false,a->{throw new AssertionError();}),originalFailure);
        check(pr.events.equals(List.of("owns","acquire:owned","handle","remember","close")));
        check(Arrays.asList(originalFailure.getSuppressed()).contains(pr.closeFailure));
        pr.events.clear();check(RuntimeSelfHookProviderCalls.release(pr,resolver,pr.handle,a->{throw new AssertionError();}).equals(true));
        check(pr.events.equals(List.of("owns","release")));
        pr.events.clear();pr.owned=false;
        check(RuntimeSelfHookProviderCalls.release(pr,resolver,pr.handle,a->{check(a[0]==pr.handle);return false;}).equals(false));check(pr.events.equals(List.of("owns")));

        int[] positions={0,2,127};Object[] values={null,"value",new RuntimeSelfHookSelection.BackReference(0,"key")};
        RuntimeSelfHookSelection snapshot=new RuntimeSelfHookSelection(positions,values);
        positions[0]=42;values[1]=new Object();check(Arrays.equals(snapshot.keys(1),new int[]{0,2,127}));
        int[] exposed=snapshot.keys(1);exposed[0]=44;check(snapshot.keys(1)[0]==0);
        check(new RuntimeSelfHookSelection(new int[0],new Object[0]).keys(0).length==0);
        for(int key:new int[]{-1,128})rejects(()->new RuntimeSelfHookSelection(new int[]{key},new Object[]{null}).keys(1),"PROVIDER_BATCH_SELECTION_INDEX_INVALID");
        rejects(()->new RuntimeSelfHookSelection(new int[]{1,1},new Object[]{null,null}).keys(1),"PROVIDER_BATCH_SELECTION_INDEX_INVALID");
        rejects(()->new RuntimeSelfHookSelection(new int[]{2,1},new Object[]{null,null}).keys(1),"PROVIDER_BATCH_SELECTION_INDEX_INVALID");
        rejects(()->new RuntimeSelfHookSelection(new int[]{0},new Object[]{"x".repeat(8193)}).keys(1),"PROVIDER_BATCH_SELECTION_TOO_LARGE");
        rejects(()->new RuntimeSelfHookSelection(new int[]{0},new Object[]{new Object()}).keys(1),"PROVIDER_BATCH_SELECTION_TYPE_INVALID");
        for(int from:new int[]{-1,1})rejects(()->new RuntimeSelfHookSelection(new int[]{0},new Object[]{new RuntimeSelfHookSelection.BackReference(from,null)}).keys(1),"PROVIDER_BATCH_BACK_REFERENCE_INVALID");
        rejects(()->new RuntimeSelfHookSelection(new int[]{0},new Object[]{new RuntimeSelfHookSelection.BackReference(0,"x".repeat(513))}).keys(1),"PROVIDER_BATCH_BACK_REFERENCE_INVALID");
        rejects(()->new RuntimeSelfHookSelection(new int[129],new Object[129]),"PROVIDER_BATCH_SELECTION_TOO_LARGE");
        rejects(()->new RuntimeSelfHookSelection(new int[0],new Object[0]).keys(-1),"PROVIDER_BATCH_OPERATION_INDEX_INVALID");
        System.out.println("component checks="+checks);
    }
}
