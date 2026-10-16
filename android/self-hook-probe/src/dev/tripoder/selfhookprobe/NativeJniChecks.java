package dev.tripoder.selfhookprobe;
import dev.tripoder.selfhook.NativeHook;
import dev.tripoder.selfhook.NativeModules;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
final class NativeJniChecks {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new IllegalStateException(name);}
    static JSONObject run(String library,String audit,String buildId) throws Throwable {
        NativeModules.initialize(new String[]{library});check(!NativeModules.snapshot(0).live,"INITIAL_NOT_LOADED");
        check(NativeJniCaller.open(library,audit),"OPEN_PRODUCT_OWNED_LIBRARY");NativeModules.Snapshot module=NativeModules.snapshot(0);
        check(module.live&&module.generation==1,"EXACT_MODULE_GENERATION");long[] rvas=NativeJniCaller.rvas();check(rvas.length==4,"FOUR_EXPORT_RVAS");
        check(NativeModules.attest(0,module.generation,rvas[0],buildId)&&NativeModules.snapshot(0).references==0,"LIVE_BUILD_ID_AND_EXECUTABLE_EXPORT");
        String wrong=(buildId.charAt(0)=='0'?"1":"0")+buildId.substring(1);
        check(!NativeModules.attest(0,module.generation,rvas[0],wrong),"WRONG_LIVE_BUILD_ID_REJECTED");
        check(!NativeModules.attest(0,module.generation,Long.MAX_VALUE,buildId),"NON_EXECUTABLE_RVA_REJECTED");
        AtomicInteger addCalls=new AtomicInteger(),onceRejected=new AtomicInteger(),foreignRejected=new AtomicInteger();
        AtomicReference<NativeHook.Invocation> expired=new AtomicReference<>();
        NativeHook.Handle add=NativeHook.prepare(0,module.generation,rvas[0],library,NativeHook.I32,new int[]{NativeHook.I32,NativeHook.I32},call->{
            if(addCalls.getAndIncrement()==0){
                Thread foreign=new Thread(()->{try{call.original(call.arguments(),call.incomingErrno());}catch(IllegalStateException expected){foreignRejected.incrementAndGet();}});
                foreign.start();foreign.join();expired.set(call);
            }
            NativeHook.Result original=call.original(call.arguments(),call.incomingErrno());
            try{call.original(call.arguments(),call.incomingErrno());}catch(IllegalStateException expected){onceRejected.incrementAndGet();}
            return new NativeHook.Result(original.bits+100,original.errorNumber);
        });
        NativeHook.Handle exact=NativeHook.prepare(0,module.generation,rvas[1],library,NativeHook.U64,new int[]{NativeHook.U64},call->{NativeHook.Result original=call.original(call.arguments(),call.incomingErrno());return new NativeHook.Result(original.bits+5,original.errorNumber);});
        NativeHook.Handle fraction=NativeHook.prepare(0,module.generation,rvas[2],library,NativeHook.F64,new int[]{NativeHook.F64},call->{NativeHook.Result original=call.original(call.arguments(),call.incomingErrno());return new NativeHook.Result(Double.doubleToRawLongBits(Double.longBitsToDouble(original.bits)+1.25),original.errorNumber);});
        NativeHook.Handle error=NativeHook.prepare(0,module.generation,rvas[3],library,NativeHook.I32,new int[]{NativeHook.I32},call->{NativeHook.Result original=call.original(call.arguments(),call.incomingErrno());return new NativeHook.Result(original.bits+200,33);});
        check(NativeModules.snapshot(0).references==4,"PUBLICATION_LEASES_PREPARED");NativeHook.publish(add,exact,fraction,error);
        check(NativeJniCaller.add(3,4)==107,"JAVA_NATIVE_CALLBACK_ORIGINAL_RETURN");
        check(NativeJniCaller.exact(0xfedcba9876543210L)==0xfedcba9876543215L,"UNSIGNED_64_RAW_BITS");
        check(NativeJniCaller.fraction(2.0)==3.75,"DOUBLE_NATIVE_CALLBACK");
        long[] floating=NativeJniCaller.floatingEnvironment();check(Double.longBitsToDouble(floating[0])==4503599627370498.0&&floating[1]==floating[2]&&(floating[3]&0x10)==0x10,"JAVA_IEEE_MODE_AND_NATIVE_ORIGINAL_FP_ENVIRONMENT");
        check(NativeJniCaller.error(9)==209&&NativeJniCaller.count(3)==1,"ERROR_ORIGINAL_SIDE_EFFECT_ONCE");
        check(addCalls.get()==1&&onceRejected.get()==1&&foreignRejected.get()==1,"SAME_THREAD_AND_ONCE_GUARDS");
        boolean closed=false;try{expired.get().original(new long[]{3,4},17);}catch(IllegalStateException expected){closed=true;}check(closed,"JAVA_ORIGINAL_EXPIRED");
        System.gc();long[] workers=NativeJniCaller.concurrent();
        check(workers.length==3&&workers[0]==4&&workers[1]==400&&workers[2]==0,"FOREIGN_NATIVE_THREADS_ATTACH_DISPATCH_DETACH");
        check(addCalls.get()==401&&onceRejected.get()==401,"NATIVE_THREADS_USE_PINNED_CONTEXT");
        NativeHook.retire(exact);check(NativeJniCaller.exact(17)==17&&NativeJniCaller.add(3,4)==107,"JNI_SHARED_PAGE_RETIRE_ONE");
        NativeHook.retire(add,fraction,error);check(NativeModules.snapshot(0).references==0,"ALL_PUBLICATION_LEASES_RELEASED");
        check(NativeJniCaller.add(3,4)==7&&NativeJniCaller.fraction(2.0)==2.5,"JNI_ORIGINALS_RESTORED");
        NativeHook.Handle invalid=NativeHook.prepare(0,module.generation,rvas[0],library,NativeHook.I32,new int[]{NativeHook.I32,NativeHook.I32},call->{
            boolean first=false,second=false;try{call.original(new long[]{3},17);}catch(IllegalStateException expected){first=true;}
            try{call.original(new long[]{3,4},17);}catch(IllegalStateException expected){second=true;}
            if(!first||!second)throw new IllegalStateException("INVALID_ATTEMPT_NOT_CONSUMED");return new NativeHook.Result(123,33);
        });
        NativeHook.publish(invalid);int before=NativeJniCaller.count(0);check(NativeJniCaller.add(3,4)==123&&NativeJniCaller.count(0)==before,"INVALID_JNI_ORIGINAL_CONSUMES_TOKEN_WITHOUT_SIDE_EFFECT");NativeHook.retire(invalid);
        NativeHook.Handle canceled=NativeHook.prepare(0,module.generation,rvas[0],library,NativeHook.I32,new int[]{NativeHook.I32,NativeHook.I32},call->{throw new AssertionError("CANCELED_CALLBACK_RAN");});
        NativeHook.retire(canceled);check(NativeJniCaller.add(3,4)==7&&NativeModules.snapshot(0).references==0,"CANCELED_PREPARATION");
        check(NativeJniCaller.close()&&!NativeModules.snapshot(0).live,"REAL_LIBRARY_UNLOAD_AFTER_JNI_RETIRE");
        check(!NativeModules.attest(0,module.generation,rvas[0],buildId),"REMOVED_MODULE_ATTESTATION_REJECTED");
        long[] events=NativeModules.events();boolean added=false,removed=false;
        for(int i=0;i<events.length;i+=5){added|=events[i+4]==1;removed|=events[i+4]==2;}
        check(added&&removed,"NOTIFIER_DRIVEN_NATIVE_EVENT_HISTORY");
        return new JSONObject().put("checks",checks).put("nativeThreads",4).put("foreignCalls",400).put("addCallbacks",addCalls.get()).put("onceRejected",onceRejected.get()).put("foreignOriginalRejected",foreignRejected.get())
            .put("actualJavaNativeCore",true).put("scriptBackendConnected",false).put("productAuthorityConnected",false).put("productAccepted",false);
    }
}
