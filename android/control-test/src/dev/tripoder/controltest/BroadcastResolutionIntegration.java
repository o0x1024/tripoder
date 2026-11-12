package dev.tripoder.controltest;

import android.content.*;
import android.net.Uri;
import java.lang.reflect.Method;
import java.util.concurrent.*;

/** Compare candidate resolution with real registered receivers in the existing host. */
public final class BroadcastResolutionIntegration extends ControlTestBase {
    private Method resolver;
    @Override void test()throws Exception{
        report.put("scope","registered-broadcast-platform-parity");
        Class<?> type=getTargetContext().getClassLoader().loadClass("dev.tripoder.runtime.RuntimeBroadcastIntent");
        resolver=type.getDeclaredMethod("matchesRegistered",IntentFilter.class,Intent.class);resolver.setAccessible(true);
        String action=enginePackage()+".TEST_RESOLUTION."+run;
        IntentFilter plain=new IntentFilter(action);
        check("raw-filter-match-is-not-candidate-resolution",plain.match(null,null,null,null,null,"test")>=0,"null action passes raw filter match");
        compare("empty-intent",plain,new Intent(),false);
        compare("matching-action",plain,new Intent(action),true);
        compare("different-action",plain,new Intent(action+".OTHER"),false);
        compare("unexpected-category",plain,new Intent(action).addCategory("undeclared"),false);
        IntentFilter mime=new IntentFilter(action);mime.addDataType("text/plain");
        compare("mime-without-action",mime,new Intent().setType("text/plain"),true);
        compare("wildcard-without-action",mime,new Intent().setType("*/*"),false);
        compare("wildcard-with-action",mime,new Intent(action).setType("*/*"),true);
        IntentFilter scheme=new IntentFilter(action);scheme.addDataScheme("tripoder-test");
        compare("scheme-without-action",scheme,new Intent().setData(Uri.parse("tripoder-test://probe")),true);
        IntentFilter actionless=new IntentFilter();actionless.addDataType("text/plain");
        compare("actionless-mime-filter",actionless,new Intent().setType("text/plain"),true);
        facadeContract();
        pendingResultLifetime();
    }
    /** Platform-parity judgement for the ordered-broadcast wall (微博 PushService: setResultData on a pool thread after
     *  onReceive returned → IllegalStateException "Call while result is not pending"). A guest only survives that if the
     *  platform leaves the receiver's pending result in place after the delivery returns. Measure the real platform here
     *  before claiming the runtime dropped a platform fact. */
    private void pendingResultLifetime()throws Exception{
        Object[] plain=probeResultData("plain",false);
        Object[] ordered=probeResultData("ordered",true);
        check("set-result-data-accepted-during-delivery","accepted".equals(plain[0]),plain[0]);
        check("platform-accepts-set-result-data-after-delivery","accepted".equals(plain[1]),plain[1]);
        check("ordered-set-result-data-accepted-during-delivery","accepted".equals(ordered[0]),ordered[0]);
        check("platform-accepts-ordered-set-result-data-after-delivery","accepted".equals(ordered[1]),ordered[1]);
    }
    /** Returns {answer inside onReceive, answer 250ms after the delivery returned}. A guest that keeps the receiver
     *  reference and sets result data later (微博 PushService) only survives when the second answer is "accepted". */
    private Object[] probeResultData(String name,boolean ordered)throws Exception{
        Context context=getTargetContext();
        String action=enginePackage()+".TEST_RESULTDATA_"+name.toUpperCase(java.util.Locale.ROOT)+"."+run;
        CountDownLatch delivered=new CountDownLatch(1);
        String[] inside=new String[1];
        BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent intent){
                inside[0]=answer(this,"inside");
                delivered.countDown();
            }
        };
        String after;
        context.registerReceiver(receiver,new IntentFilter(action),Context.RECEIVER_NOT_EXPORTED);
        try{
            Intent intent=new Intent(action).setPackage(context.getPackageName());
            if(ordered)context.sendOrderedBroadcast(intent,null,null,null,0,null,null);
            else context.sendBroadcast(intent);
            delivered.await(5,TimeUnit.SECONDS);
            Thread.sleep(250);
            after=answer(receiver,"after");
        }finally{context.unregisterReceiver(receiver);}
        return new Object[]{inside[0],after};
    }
    private static String answer(BroadcastReceiver receiver,String probe){
        try{receiver.setResultData(probe);return "accepted";}
        catch(RuntimeException error){return error.getClass().getSimpleName()+":"+error.getMessage();}
    }
    /** The facade exception contract: only an answer the platform itself gives may reach a guest caller, so every internal
     *  limitation is reduced instead of raised. A guest process that dies here is a runtime defect reported as an
     *  application crash (BOSS直聘 一键登录, 2026-11-13). */
    private void facadeContract()throws Exception{
        ClassLoader loader=getTargetContext().getClassLoader();
        Class<?> intent=loader.loadClass("dev.tripoder.runtime.RuntimeBroadcastIntent");
        Method sanitize=intent.getDeclaredMethod("sanitize",Intent.class);sanitize.setAccessible(true);
        Intent shaped=new Intent("probe").setIdentifier("probe").addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        shaped.setClipData(ClipData.newPlainText("probe","probe"));shaped.setSelector(new Intent("probe"));shaped.setSourceBounds(new android.graphics.Rect(0,0,1,1));
        Intent reduced=(Intent)sanitize.invoke(null,shaped);
        check("sanitize-drops-clipdata",reduced.getClipData()==null,reduced.getClipData());
        check("sanitize-drops-selector",reduced.getSelector()==null,reduced.getSelector());
        check("sanitize-drops-sourcebounds",reduced.getSourceBounds()==null,reduced.getSourceBounds());
        check("sanitize-drops-identifier",reduced.getIdentifier()==null,reduced.getIdentifier());
        check("sanitize-keeps-receiver-flags",(reduced.getFlags()&Intent.FLAG_RECEIVER_REPLACE_PENDING)!=0,reduced.getFlags());
        // A Binder nested in a Bundle cannot cross as payload bytes: the intent must be reduced, not refused.
        Method encodeReduced=intent.getDeclaredMethod("encodeReduced",Intent.class);encodeReduced.setAccessible(true);
        android.os.Bundle nested=new android.os.Bundle();nested.putBinder("binder",new android.os.Binder());
        Object bytes=null;String failure="none";
        try{bytes=encodeReduced.invoke(null,new Intent("probe").putExtra("nested",nested));}
        catch(java.lang.reflect.InvocationTargetException error){failure=error.getCause().getClass().getName()+":"+error.getCause().getMessage();}
        check("nested-binder-is-reduced-not-refused",failure.equals("none")&&bytes instanceof byte[]&&((byte[])bytes).length>0,failure);
        // The contract itself: a SecurityException is the single answer the platform also gives.
        Class<?> contract=loader.loadClass("dev.tripoder.runtime.RuntimeFacadeContract");
        Method platformAnswer=contract.getDeclaredMethod("platformAnswer",Throwable.class);platformAnswer.setAccessible(true);
        check("security-refusal-reaches-the-caller",(Boolean)platformAnswer.invoke(null,new SecurityException("probe")),true);
        check("internal-failure-is-reduced",!(Boolean)platformAnswer.invoke(null,new IllegalStateException("probe")),false);
        check("unsupported-shape-is-reduced",!(Boolean)platformAnswer.invoke(null,new UnsupportedOperationException("probe")),false);
        // Non-broadcast facades use the same contract: an unknown flag word is narrowed (as an older platform ignores
        // newer flags) and a reduced call answers instead of throwing.
        Method flagBits=contract.getDeclaredMethod("flagBits",String.class,long.class);flagBits.setAccessible(true);
        check("unknown-flag-bits-are-narrowed",((Integer)flagBits.invoke(null,"PROBE_FLAGS",0x1_0000_0001L))==1,flagBits.invoke(null,"PROBE_FLAGS",0x1_0000_0001L));
        check("known-flag-bits-pass-unchanged",((Integer)flagBits.invoke(null,"PROBE_FLAGS",0x10L))==0x10,flagBits.invoke(null,"PROBE_FLAGS",0x10L));
        Method nothing=contract.getDeclaredMethod("nothing",String.class,Throwable.class,Object.class);nothing.setAccessible(true);
        check("reduced-call-answers-the-platform-way",Boolean.FALSE.equals(nothing.invoke(null,"PROBE_NOTHING",new IllegalStateException("probe"),Boolean.FALSE)),false);
        // Baseline fact this contract must respect: the platform already refuses a selector broadcast, so that shape is
        // never a parity target - the runtime's job is to keep the caller alive for the shapes the platform does accept.
        String selectorAnswer;
        try{getTargetContext().sendBroadcast(new Intent("probe").setPackage(getTargetContext().getPackageName()));selectorAnswer="accepted";}
        catch(RuntimeException error){selectorAnswer=error.getClass().getSimpleName();}
        check("plain-broadcast-is-accepted-by-the-platform",selectorAnswer.equals("accepted"),selectorAnswer);
    }
    private void compare(String name,IntentFilter filter,Intent input,boolean expected)throws Exception{
        Context context=getTargetContext();Intent intent=new Intent(input).setPackage(context.getPackageName());
        boolean logical=(Boolean)resolver.invoke(null,filter,intent);
        check(name+"-runtime",logical==expected,logical);
        CountDownLatch delivered=new CountDownLatch(1);
        BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){delivered.countDown();}};
        context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        boolean observed;
        try{context.sendBroadcast(intent);observed=delivered.await(expected?3000:600,TimeUnit.MILLISECONDS);}
        finally{context.unregisterReceiver(receiver);}
        check(name+"-platform",observed==expected,observed);
    }
}
