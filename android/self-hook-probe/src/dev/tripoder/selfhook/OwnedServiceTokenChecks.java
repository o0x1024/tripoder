package dev.tripoder.selfhook;

import android.content.ComponentName;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/** Exact fixed IAM member and owned token/delegate; never calls the real AMS. */
public final class OwnedServiceTokenChecks {
    private static int checks;
    private static void check(boolean passed,String name){checks++;if(!passed)throw new AssertionError(name);}
    public static JSONObject run()throws Exception{
        Object[] members=FrameworkAccess.serviceTokenMembers();Class<?> contract=(Class<?>)members[1];Method stop=(Method)members[4];
        Binder token=new Binder(),backendBinder=new Binder();ComponentName physical=new ComponentName("dev.tripoder.owned.host","dev.tripoder.owned.host.ServiceStub");
        ComponentName mixed=new ComponentName("dev.tripoder.owned.guest",physical.getClassName());
        List<Object[]> calls=new ArrayList<>();RuntimeException sentinel=new IllegalStateException("OWNED_SERVICE_BACKEND_FAILURE");
        Object backend=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(proxy,method,args)->{
            if(method.equals(stop)){calls.add(args.clone());if((Integer)args[2]==99)throw sentinel;return (Integer)args[2]==7;}
            if(method.getDeclaringClass()==IInterface.class&&method.getName().equals("asBinder"))return backendBinder;
            throw new AssertionError("UNEXPECTED_OWNED_SERVICE_BACKEND_METHOD:"+method.getName());
        });
        Object scoped=ServiceTokenAccess.scopedManager(backend,token,physical,mixed.getPackageName(),false);
        Object result=FixedFrameworkMembers.call(stop,scoped,mixed,token,7);
        check(Boolean.TRUE.equals(result)&&calls.size()==1&&calls.get(0)[0]==physical&&calls.get(0)[1]==token&&(Integer)calls.get(0)[2]==7,"SDK_MIXED_SELECTOR_USES_EXACT_PHYSICAL_COMPONENT_ONCE");
        result=FixedFrameworkMembers.call(stop,scoped,mixed,token,6);
        check(Boolean.FALSE.equals(result)&&calls.size()==2&&(Integer)calls.get(1)[2]==6,"OLD_START_ID_FALSE_IS_NOT_RETRIED_OR_COERCED");
        check(Boolean.TRUE.equals(FixedFrameworkMembers.call(stop,scoped,physical,token,7))&&calls.size()==3,"CANONICAL_PHYSICAL_SELECTOR_RETAINS_SINGLE_BACKEND_CALL");
        boolean denied=false;try{FixedFrameworkMembers.call(stop,scoped,mixed,new Binder(),7);}catch(SecurityException error){denied=error.getMessage().equals("SERVICE_STOP_TOKEN_MISMATCH");}
        check(denied&&calls.size()==3,"FOREIGN_TOKEN_HAS_ZERO_BACKEND_CALLS");
        denied=false;try{FixedFrameworkMembers.call(stop,scoped,new ComponentName("dev.tripoder.foreign",physical.getClassName()),token,7);}catch(SecurityException error){denied=error.getMessage().equals("SERVICE_STOP_COMPONENT_MISMATCH");}
        check(denied&&calls.size()==3,"FOREIGN_COMPONENT_HAS_ZERO_BACKEND_CALLS");
        check(((IInterface)scoped).asBinder()==backendBinder&&calls.size()==3,"OTHER_MANAGER_METHOD_RETAINS_ORIGINAL_BINDER");
        boolean exact=false;try{FixedFrameworkMembers.call(stop,scoped,mixed,token,99);}catch(RuntimeException error){exact=error==sentinel;}
        check(exact&&calls.size()==4,"BACKEND_EXCEPTION_IDENTITY_IS_PRESERVED");
        check(Boolean.FALSE.equals(FixedFrameworkMembers.call(stop,scoped,mixed,token,Integer.MAX_VALUE))&&calls.size()==5&&(Integer)calls.get(4)[2]==Integer.MAX_VALUE,"FULL_START_ID_PRESERVED_WITHOUT_SUBSTITUTING_MINUS_ONE");
        return new JSONObject().put("passed",true).put("checks",checks).put("physicalAmsCalled",false);
    }
    private OwnedServiceTokenChecks(){}
}
