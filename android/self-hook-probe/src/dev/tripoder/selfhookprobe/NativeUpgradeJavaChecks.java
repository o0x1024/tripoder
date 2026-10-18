package dev.tripoder.selfhookprobe;
import dev.tripoder.selfhook.NativeHook;
import dev.tripoder.selfhook.NativeModules;
import java.util.Map;
import org.json.JSONObject;
final class NativeUpgradeJavaChecks {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new IllegalStateException(name);}
    private static long number(Map<String,Object> value,String key){return ((Number)value.get(key)).longValue();}
    static JSONObject run(String library,String audit)throws Throwable {
        check(NativeJniCaller.open(library,audit),"JAVA_RESOURCE_LIBRARY");
        NativeModules.Snapshot module=NativeModules.snapshot(0);long rva=NativeJniCaller.rvas()[0];
        Map<String,Object> before=NativeHook.resources();int[] types={NativeHook.I32,NativeHook.I32};
        for(int i=0;i<64;i++){
            boolean rejected=false;try{NativeHook.prepare(0,module.generation,rva,library+".wrong",NativeHook.I32,types,call->new NativeHook.Result(0,0));}
            catch(IllegalStateException error){rejected=error.getMessage().contains("NATIVE_IMAGE:stage=EXECUTABLE_SEGMENT");}
            check(rejected,"PREPARE_FAILURE_KEEPS_REASON");
        }
        Map<String,Object> after=NativeHook.resources();
        check(number(before,"issued")==number(after,"issued")&&number(before,"jniGlobalRoots")==number(after,"jniGlobalRoots")
            &&NativeModules.snapshot(0).references==0,"FAILED_PREPARATION_HAS_NO_HANDLE_OR_JNI_ROOT");
        NativeHook.Handle canceled=NativeHook.prepare(0,module.generation,rva,library,NativeHook.I32,types,call->{throw new AssertionError("CANCELED_ENTRY");});
        check(number(NativeHook.resources(),"jniGlobalRoots")==number(before,"jniGlobalRoots")+1,"PREPARED_ROOT_ACCOUNTED");
        NativeHook.retire(canceled);
        check(number(NativeHook.resources(),"jniGlobalRoots")==number(before,"jniGlobalRoots")&&NativeModules.snapshot(0).references==0,"UNPUBLISHED_ROOT_RELEASED");
        NativeHook.Handle active=NativeHook.prepare(0,module.generation,rva,library,NativeHook.I32,types,call->{
            long[] copy=call.arguments();copy[0]=999;
            check(call.arguments()[0]==3,"ARGUMENT_SNAPSHOT_NOT_EXPOSED");
            check(number(NativeHook.resources(),"inFlight")==1,"JAVA_SEES_NATIVE_ADMISSION");
            return call.original(call.arguments(),call.incomingErrno());
        });
        NativeHook.publish(active);check(NativeJniCaller.add(3,4)==7,"SNAPSHOT_ORIGINAL_UNCHANGED");NativeHook.retire(active);
        after=NativeHook.resources();
        check(number(after,"inFlight")==0&&number(after,"jniGlobalRoots")==number(before,"jniGlobalRoots")+1,"PUBLISHED_CONTEXT_RETAINED");
        check(number(after,"prepared")+number(after,"active")+number(after,"retired")+number(after,"failed")==number(after,"issued"),"RESOURCE_ACCOUNTING_IDENTITY");
        check(NativeJniCaller.close()&&!NativeModules.snapshot(0).live,"JAVA_RESOURCE_MODULE_UNLOADED");
        return new JSONObject().put("checks",checks).put("failedPreparations",64).put("resources",new JSONObject(after));
    }
}
