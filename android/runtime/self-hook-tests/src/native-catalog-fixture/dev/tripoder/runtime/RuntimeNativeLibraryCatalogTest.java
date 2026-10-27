package dev.tripoder.runtime;

import dev.tripoder.selfhook.NativeModules;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Startup ownership and tool observation share one frozen native identity. */
public final class RuntimeNativeLibraryCatalogTest {
    private static int checks,inspections;
    private interface Action {void run()throws Exception;}
    private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
    private static void rejected(Action action,String code)throws Exception{
        try{action.run();throw new AssertionError("accepted "+code);}catch(IllegalStateException error){check(code.equals(error.getMessage()));}
    }
    private static RuntimeNativeLibraryCatalog catalog(List<String> names,AtomicBoolean live)throws Exception{
        return new RuntimeNativeLibraryCatalog(new RuntimeNativeLibraryCatalog.Inventory(){
            public List<String> names(){return names;}
            public String path(String name){return "/owned/lib/"+name;}
            public Map<String,Object> inspect(String name){inspections++;throw new AssertionError("startup inspected Hook metadata");}
        },live::get);
    }
    public static void main(String[] args)throws Exception{
        NativeModules.reset();AtomicBoolean live=new AtomicBoolean(true);List<String> input=new ArrayList<>(List.of("libz.so","liba.so"));
        var catalog=catalog(input,live);input.clear();check(NativeModules.calls==0&&inspections==0);
        check(new ArrayList<>(catalog.artifacts().keySet()).equals(List.of("libz.so","liba.so")));
        check(catalog.artifacts().get("libz.so").index()==0&&catalog.artifacts().get("liba.so").index()==1);
        try{catalog.artifacts().clear();throw new AssertionError("mutable identity");}catch(UnsupportedOperationException expected){checks++;}
        rejected(()->new RuntimeNativeObservation(catalog,live::get,Map.of()),"NATIVE_LIBRARY_RUNTIME_NOT_READY");check(NativeModules.calls==0);
        catalog.installProcessBoundary();catalog.requireReady();check(NativeModules.calls==1&&Arrays.equals(NativeModules.paths,new String[]{"/owned/lib/libz.so","/owned/lib/liba.so"}));
        new RuntimeNativeObservation(catalog,live::get,Map.of());new RuntimeNativeObservation(catalog,live::get,Map.of());
        check(NativeModules.calls==1&&inspections==0);
        rejected(catalog::installProcessBoundary,"NATIVE_LIBRARY_RUNTIME_ALREADY_ATTEMPTED");check(NativeModules.calls==1);
        live.set(false);rejected(catalog::requireReady,"NATIVE_LIBRARY_RUNTIME_NOT_READY");

        NativeModules.reset();live.set(true);NativeModules.reject=true;var failed=catalog(List.of("libfail.so"),live);
        rejected(failed::installProcessBoundary,"OWNED_NATIVE_REJECTION");rejected(failed::requireReady,"NATIVE_LIBRARY_RUNTIME_NOT_READY");
        rejected(failed::installProcessBoundary,"NATIVE_LIBRARY_RUNTIME_ALREADY_ATTEMPTED");check(NativeModules.calls==1);

        NativeModules.reset();live.set(false);var stale=catalog(List.of("libstale.so"),live);
        rejected(stale::installProcessBoundary,"HOOK_PROCESS_STALE");live.set(true);
        rejected(stale::installProcessBoundary,"NATIVE_LIBRARY_RUNTIME_ALREADY_ATTEMPTED");check(NativeModules.calls==0);
        try{catalog(List.of("libdup.so","libdup.so"),live);throw new AssertionError("duplicate");}catch(IllegalArgumentException expected){check("HOOK_MODULE_CATALOG".equals(expected.getMessage()));}
        check(NativeModules.calls==0&&inspections==0);
        System.out.println("RuntimeNativeLibraryCatalogTest: "+checks+" passed");
    }
}
