package dev.tripoder.runtime;

import java.lang.reflect.Method;
import java.util.Arrays;

public final class ManagerPolicyTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static class Parent<T>{public T variant(){return null;}}
    public static final class Battery extends Parent<String> {
        public boolean isCharging(){return false;}public long computeChargeTimeRemaining(){return 0;}
        public int getIntProperty(int id){return 0;}public long getLongProperty(int id){return 0;}
        public void deniedControl(){}public native int deniedNative();public static void staticOperation(){}
        @Override public String variant(){return "variant";}
    }
    public static final class PhysicalManager {
        final String identity;final RuntimeException marker=new RuntimeException("platform-original");
        PhysicalManager(String identity){this.identity=identity;}
        private String context(){return identity;}
        public String operation(){return context();}
        public void fail(){throw marker;}
    }
    private static final class Route implements RuntimeSelfHookManagerPolicy.Route {
        boolean owned=true;int executions;Object result;Throwable failure;
        public boolean owns(Object receiver){return owned;}
        public Object execute(String key,Object[] args)throws Throwable{executions++;if(failure!=null)throw failure;return result;}
    }
    @FunctionalInterface private interface Action{void run()throws Throwable;}
    private static void rejected(Action action,Class<? extends Throwable> type)throws Throwable {
        try{action.run();throw new AssertionError("accepted");}catch(Throwable error){check(type.isInstance(error));}
    }
    public static void main(String[] args)throws Throwable {
        Object receiver=new Object();Object[] arguments={Long.valueOf(9007199254740993L)};Route route=new Route();int supported=0;
        for(var family:RuntimeSelfHookManagerPolicy.Family.values()) {
            for(String key:RuntimeSelfHookManagerPolicy.required(family)) {
                route.result=arguments[0];int before=route.executions;
                check(RuntimeSelfHookManagerPolicy.invoke(family,route,key,receiver,arguments,a->{throw new AssertionError("owned escaped");})==arguments[0]);
                check(route.executions==before+1);supported++;
            }
            int before=route.executions;
            rejected(()->RuntimeSelfHookManagerPolicy.invoke(family,route,"unlistedControl()",receiver,arguments,a->{throw new AssertionError("denial escaped");}),family==RuntimeSelfHookManagerPolicy.Family.NETWORK_STATS?SecurityException.class:UnsupportedOperationException.class);
            check(route.executions==before);
            route.owned=false;
            check(RuntimeSelfHookManagerPolicy.invoke(family,route,"unlistedControl()",receiver,arguments,a->{check(a==arguments);return arguments[0];})==arguments[0]);
            check(route.executions==before);route.owned=true;
            rejected(()->RuntimeSelfHookManagerPolicy.required(family).add("unlistedControl()"),UnsupportedOperationException.class);
        }
        var battery=RuntimeSelfHookManagerPolicy.Family.BATTERY;
        for(var denial:java.util.Map.of(RuntimeSelfHookManagerPolicy.Family.NOTIFICATIONS,"NOTIFICATION",RuntimeSelfHookManagerPolicy.Family.SHORTCUTS,"SHORTCUT").entrySet()){
            try{RuntimeSelfHookManagerPolicy.invoke(denial.getKey(),route,"unlistedControl()",receiver,arguments,a->{throw new AssertionError("owned denial escaped");});throw new AssertionError("owned denial accepted");}
            catch(UnsupportedOperationException error){check(error.getMessage().equals(denial.getValue()+"_CAPABILITY_UNSUPPORTED:unlistedControl()"));}
        }
        for(Object precise:new Object[]{Long.MIN_VALUE,Long.MAX_VALUE,9007199254740993L,Float.NaN,-0.0f}) {
            route.result=precise;
            check(RuntimeSelfHookManagerPolicy.invoke(battery,route,"getLongProperty(int)",receiver,new Object[]{1},a->{throw new AssertionError();})==precise);
        }
        Throwable failure=new Exception("original");route.failure=failure;
        try{RuntimeSelfHookManagerPolicy.invoke(battery,route,"getLongProperty(int)",receiver,new Object[]{1},a->{throw new AssertionError();});throw new AssertionError();}
        catch(Throwable error){check(error==failure);}route.failure=null;
        Method[] inventory=Battery.class.getDeclaredMethods();var plan=RuntimeSelfHookManagerPolicy.plan(battery,Battery.class,inventory,route);
        check(plan.size()==8);check(plan.keySet().stream().filter(m->m.getName().equals("variant")).count()==2);
        for(var entry:plan.entrySet())if(!RuntimeSelfHookManagerPolicy.required(battery).contains(RuntimeSelfHookManagerPolicy.signature(entry.getKey())))
            rejected(()->entry.getValue().invoke(receiver,new Object[0],a->{throw new AssertionError();}),UnsupportedOperationException.class);
        rejected(()->plan.clear(),UnsupportedOperationException.class);
        Method[] missing=Arrays.stream(inventory).filter(m->!m.getName().equals("getIntProperty")).toArray(Method[]::new);
        rejected(()->RuntimeSelfHookManagerPolicy.plan(battery,Battery.class,missing,route),IllegalArgumentException.class);
        Method[] duplicate=Arrays.copyOf(inventory,inventory.length+1);duplicate[duplicate.length-1]=inventory[0];
        rejected(()->RuntimeSelfHookManagerPolicy.plan(battery,Battery.class,duplicate,route),IllegalArgumentException.class);
        Method[] wrong=inventory.clone();wrong[0]=Object.class.getMethod("hashCode");
        rejected(()->RuntimeSelfHookManagerPolicy.plan(battery,Battery.class,wrong,route),IllegalArgumentException.class);
        inventory[0]=null;check(plan.size()==8);
        PhysicalManager guest=new PhysicalManager("guest"),physical=new PhysicalManager("physical");
        RuntimeSelfHookManagerPolicy.Route physicalRoute=new RuntimeSelfHookManagerPolicy.Route(){
            public boolean owns(Object value){return value==guest;}
            public Object platform(){return physical;}
            public Object execute(String signature,Object[] arguments){throw new AssertionError("unexpected facade route");}
        };
        Method operation=PhysicalManager.class.getMethod("operation");
        check("physical".equals(RuntimeSelfHookManagerPolicy.physicalOriginal(operation,physicalRoute,guest,new Object[0],a->{throw new AssertionError("guest original invoked");})));
        try{RuntimeSelfHookManagerPolicy.physicalOriginal(PhysicalManager.class.getMethod("fail"),physicalRoute,guest,new Object[0],a->{throw new AssertionError();});throw new AssertionError();}catch(RuntimeException error){check(error==physical.marker);}
        check("original".equals(RuntimeSelfHookManagerPolicy.physicalOriginal(operation,physicalRoute,physical,new Object[0],a->"original")));
        RuntimeSelfHookManagerPolicy.Route invalidRoute=new RuntimeSelfHookManagerPolicy.Route(){
            public boolean owns(Object value){return value==guest;}public Object platform(){return guest;}
            public Object execute(String signature,Object[] arguments){throw new AssertionError();}
        };
        rejected(()->RuntimeSelfHookManagerPolicy.physicalOriginal(operation,invalidRoute,guest,new Object[0],a->null),SecurityException.class);
        System.out.println("manager policy checks="+checks+" supported="+supported);
    }
}
