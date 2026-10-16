package dev.tripoder.provideridentity;

import java.lang.reflect.Field;
import org.json.*;

/** Reflection must preserve caller access, including a class reading its own private field. */
final class ReflectionAccessChecks {
    private static final class Owner {
        private Object secret="original";
        public Object exposed="public";
        Object readOwn()throws Exception{return Owner.class.getDeclaredField("secret").get(this);}
        void writeOwn(Object value)throws Exception{Owner.class.getDeclaredField("secret").set(this,value);}
    }
    static void inspect(JSONArray checks)throws Exception{
        Owner owner=new Owner();boolean own=false;String error="";
        try{own="original".equals(owner.readOwn());owner.writeOwn("written");own&="written".equals(owner.readOwn());}catch(Exception failure){error=failure.getClass().getName()+":"+failure.getMessage();}
        check(checks,"reflection-private-owner-access-preserved",own,error);
        Field privateField=Owner.class.getDeclaredField("secret");privateField.setAccessible(true);Object value=new Object();privateField.set(owner,value);
        check(checks,"reflection-explicit-access-keeps-object-identity",privateField.get(owner)==value,true);
        Field denied=Owner.class.getDeclaredField("secret");boolean rejected=false;try{ReflectionOtherReader.read(denied,owner);}catch(IllegalAccessException expected){rejected=true;}
        check(checks,"reflection-other-private-access-remains-denied",rejected,true);
        Field publicField=Owner.class.getField("exposed");publicField.setAccessible(true);check(checks,"reflection-explicit-public-field-read-preserved","public".equals(publicField.get(owner)),true);
        ReflectionAccessOwner separate=new ReflectionAccessOwner();Object token=new Object();
        separate.writeOwn(token);check(checks,"reflection-public-owner-private-access",separate.readOwn()==token,true);
        ReflectionAccessOwner.writeStatic(token);check(checks,"reflection-private-static-access",ReflectionAccessOwner.readStatic()==token,true);
        Field visible=ReflectionAccessOwner.class.getField("exposed");visible.set(separate,token);
        check(checks,"reflection-public-field-without-access-override",visible.get(separate)==token,true);
        Field privateCopy=ReflectionAccessOwner.class.getDeclaredField("secret");privateCopy.setAccessible(true);
        Field fresh=ReflectionAccessOwner.class.getDeclaredField("secret");
        expect(checks,"reflection-field-access-flags-are-independent",IllegalAccessException.class,()->fresh.get(separate));
        expect(checks,"reflection-private-write-remains-denied",IllegalAccessException.class,()->fresh.set(separate,new Object()));
        check(checks,"reflection-denied-write-keeps-original",privateCopy.get(separate)==token,true);
        expect(checks,"reflection-wrong-receiver-type",IllegalArgumentException.class,()->privateCopy.get(new Object()));
        expect(checks,"reflection-null-receiver",NullPointerException.class,()->privateCopy.get(null));
        Field number=ReflectionAccessOwner.class.getField("number");number.set(separate,Byte.valueOf((byte)23));
        check(checks,"reflection-primitive-boxing-and-widening",Integer.valueOf(23).equals(number.get(separate)),number.get(separate));
        expect(checks,"reflection-primitive-narrowing-rejected",IllegalArgumentException.class,()->number.set(separate,Long.valueOf(30)));
        expect(checks,"reflection-null-primitive-rejected",IllegalArgumentException.class,()->number.set(separate,null));
        check(checks,"reflection-final-object-read",ReflectionAccessOwner.class.getField("fixed").get(separate)==separate.fixed,true);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread[] readers=new Thread[4];
        for(int i=0;i<readers.length;i++){readers[i]=new Thread(()->{try{
            ReflectionAccessOwner local=new ReflectionAccessOwner();Field field=ReflectionAccessOwner.class.getDeclaredField("secret");field.setAccessible(true);
            for(int n=0;n<1000;n++){Object item=new Object();local.writeOwn(item);if(local.readOwn()!=item||field.get(local)!=item)throw new AssertionError("REFLECTION_CONCURRENT_IDENTITY");if(n%100==0)System.gc();}
        }catch(Throwable error2){failure.compareAndSet(null,error2);}},"owned-reflection-"+i);readers[i].start();}
        for(Thread reader:readers)reader.join(15000);
        boolean finished=true;for(Thread reader:readers)finished&=!reader.isAlive();
        check(checks,"reflection-four-thread-gc-4000-rounds",finished&&failure.get()==null,String.valueOf(failure.get()));
    }
    private interface Attempt {void run()throws Exception;}
    private static void expect(JSONArray checks,String name,Class<? extends Throwable> type,Attempt attempt)throws Exception{
        Throwable failure=null;try{attempt.run();}catch(Throwable error){failure=error;}
        check(checks,name,failure!=null&&failure.getClass()==type,failure==null?"NO_EXCEPTION":failure.getClass().getName());
    }
    private static void check(JSONArray checks,String name,boolean passed,Object observed)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",passed).put("observed",observed));if(!passed)throw new AssertionError(name+":"+observed);}
    private ReflectionAccessChecks(){}
}
final class ReflectionOtherReader {
    static Object read(Field field,Object owner)throws IllegalAccessException{return field.get(owner);}
}
