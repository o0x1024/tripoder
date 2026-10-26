package dev.tripoder.runtime;

import android.content.ContentProvider;
import android.database.*;
import android.os.*;
import java.lang.reflect.*;

/** Real lazy Cursor, real windows. Invocation identity and acquisition lifetime remain separate. */
final class RuntimeProviderCursor implements InvocationHandler {
    private final ContentProvider provider;
    private final CrossProcessCursor cursor;
    private final Bundle claim;
    private final java.util.concurrent.atomic.AtomicBoolean closing=new java.util.concurrent.atomic.AtomicBoolean();
    private RuntimeProviderCursor(ContentProvider provider,Cursor cursor,Bundle claim){this.provider=provider;this.cursor=new CrossProcessCursorWrapper(cursor);this.claim=new Bundle(claim);}
    static Cursor wrap(ContentProvider provider,Cursor cursor,Bundle claim){return cursor==null?null:(Cursor)Proxy.newProxyInstance(RuntimeProviderCursor.class.getClassLoader(),new Class<?>[]{CrossProcessCursor.class},new RuntimeProviderCursor(provider,cursor,claim));}
    @Override public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
        if(method.getDeclaringClass()==Object.class){switch(method.getName()){case "equals":return proxy==args[0];case "hashCode":return System.identityHashCode(proxy);case "toString":return "TripoderCursor["+claim.getString("operation")+"]";default:throw new IllegalStateException("CURSOR_OBJECT_METHOD");}}
        int pid=Binder.getCallingPid(),uid=Binder.getCallingUid();Bundle identity;
        boolean cleanup=method.getName().equals("close")||method.getName().equals("unregisterContentObserver");
        if(pid==claim.getInt("callerPid")&&uid==claim.getInt("callerUid")){
            // The fixed original caller may release an existing local resource
            // after closing starts. Data methods still require current admission.
            if(cleanup)identity=claim;
            else if(claim.getBoolean("thin"))identity=RuntimeProviderTransport.thinCursorAccess(claim,pid,uid);
            else{
                RuntimeEngine engine=RuntimeEngine.current;Bundle request=engine.auth();request.putString("cap",claim.getString("cap"));request.putString("operation",claim.getString("operation"));request.putInt("callerPid",pid);request.putInt("callerUid",uid);
                long token=Binder.clearCallingIdentity();
                try{engine.brokerCall(RuntimeWire.PROVIDER_CURSOR_ACCESS,request);}finally{Binder.restoreCallingIdentity(token);}
                identity=claim;
            }
        }else if(pid==android.os.Process.myPid()&&uid==android.os.Process.myUid()&&cleanup){
            // Android binder-death / construction-failure cleanup is not a new request by the source.
            identity=new Bundle();
        }else throw new SecurityException("PROVIDER_CURSOR_CALLER_MISMATCH");
        if(method.getName().equals("close")&&!closing.compareAndSet(false,true))return null;
        try(RuntimeProviderIdentity.Scope scope=RuntimeProviderIdentity.enter(provider,identity)){
            try{return method.invoke(cursor,args);}catch(InvocationTargetException error){throw error.getCause();}
        }
    }
}
