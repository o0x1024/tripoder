package dev.tripoder.runtime;

import java.lang.reflect.Method;

/** Callback ordering shared by the typed component adapters; no component/Broker state lives here. */
final class RuntimeSelfHookLifecycle {
    @FunctionalInterface interface Original { Object call(Object... arguments) throws Throwable; }
    interface ServiceRoute {
        boolean owns(Object service);
        Object intent(Object service,Object physical);
        void completed(Object service,String event)throws Throwable;
    }
    interface ReceiverRoute {
        boolean owns(Object receiver);
        Object pending(Object receiver);
        Object before(Object receiver,Object intent,Object pending);
        Object context(Object original);
        void after(Object receiver,boolean success)throws Throwable;
    }

    static Object service(ServiceRoute route,Method target,Object receiver,Object[] arguments,Original original)throws Throwable {
        if(!route.owns(receiver)||!mostDerived(target,receiver))return original.call(arguments);
        Object[] logical=arguments.clone();
        if(logical.length!=0)logical[0]=route.intent(receiver,logical[0]);
        Object result=original.call(logical);
        route.completed(receiver,target.getName());
        return result;
    }

    static Object receiver(ReceiverRoute route,Method target,Object receiver,Object[] arguments,Original original)throws Throwable {
        if(!route.owns(receiver)||!mostDerived(target,receiver))return original.call(arguments);
        if(arguments.length!=2)throw new IllegalArgumentException("RECEIVER_ARGUMENT_SHAPE");
        Object pending=route.pending(receiver);
        // onReceive is also an ordinary public Java method. A local call has
        // no Android delivery token and must neither claim a ticket nor invent
        // receiver lifecycle receipts. Real system deliveries retain every guard.
        if(pending==null)return original.call(arguments);
        Object logical=route.before(receiver,arguments[1],pending);
        if(logical==null)return null;
        boolean success=false;Throwable failure=null;
        try {
            Object result=original.call(route.context(arguments[0]),logical);
            success=true;
            return result;
        } catch(Throwable error){failure=error;throw error;}
        finally {
            try{route.after(receiver,success);}
            catch(Throwable acknowledgement){
                if(failure==null)throw acknowledgement;
                if(failure!=acknowledgement)failure.addSuppressed(acknowledgement);
            }
        }
    }

    private static boolean mostDerived(Method target,Object receiver)throws NoSuchMethodException {
        // Public component lifecycle metadata only. No hidden-member lookup or accessible override.
        return target.equals(receiver.getClass().getMethod(target.getName(),target.getParameterTypes()));
    }
    private RuntimeSelfHookLifecycle(){}
}
