package dev.tripoder.selfhook.dex;
public final class ClosureOracle {
    public interface Operation { int run(int value); }
    public static class Base { public int run(int value){return value+1;} }
    public static final class Child extends Base implements Operation {
        @Override public int run(int value){return super.run(value)+1;}
    }
    public static int virtualCall(Base value,int x){return value.run(x);}
    public static int interfaceCall(Operation value,int x){return value.run(x);}
    public static int outer(Base value,int x){return virtualCall(value,x);}
    public static Object reflective(java.lang.reflect.Method method,Object value)throws Exception{return method.invoke(value,7);}
    public static int methodHandle(java.lang.invoke.MethodHandle handle,int value)throws Throwable{return (int)handle.invokeExact(value);}
    public static class StaticBase { public static int value(int n){return n+1;} }
    public static final class StaticChild extends StaticBase { }
    public static final class Unrelated { public static int value(int n){return n+9;} }
    public static int selected(int n){return StaticBase.value(n);}
    public static int inherited(int n){return StaticChild.value(n);}
    public static int unrelated(int n){return Unrelated.value(n);}
    public static int selectedOuter(int n){return selected(n);}
    public static int unrelatedOuter(int n){return unrelated(n);}
}
