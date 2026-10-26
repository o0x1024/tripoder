package dev.tripoder.runtime.script;

import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Strong managed roots exist only for this synchronous invocation. No global handles or addresses. */
public final class JavaObjectScope implements AutoCloseable {
    private static final ThreadLocal<Deque<JavaObjectScope>> SCOPES=ThreadLocal.withInitial(ArrayDeque::new);
    private final Thread thread=Thread.currentThread();
    private final Map<String,Class<?>> types=new HashMap<>();
    private final IdentityHashMap<Object,Ref> references=new IdentityHashMap<>();
    private final String generation,revision;
    private boolean closed;
    public static final class Ref {
        private final JavaObjectScope scope;private Object value;
        private Ref(JavaObjectScope scope,Object value){this.scope=scope;this.value=value;}
        @Override public String toString(){throw new ScriptFault("HOOK_OBJECT_STRING_UNSUPPORTED: use ctx.java.objectInfo or snapshotThrowable");}
    }
    private JavaObjectScope(String generation,String revision,Class<?>[] parameters,Class<?> result){
        this.generation=Objects.requireNonNull(generation);this.revision=Objects.requireNonNull(revision);
        for(Class<?> type:parameters)types.put(type.getName(),type);types.put(result.getName(),result);SCOPES.get().push(this);
    }
    public static JavaObjectScope open(String generation,String revision,Class<?>[] parameters,Class<?> result){return new JavaObjectScope(generation,revision,parameters,result);}
    private void live(){require(!closed&&thread==Thread.currentThread()&&SCOPES.get().peek()==this,"HOOK_OBJECT_REFERENCE_EXPIRED");}
    static JavaObjectScope current(){JavaObjectScope scope=SCOPES.get().peek();require(scope!=null,"HOOK_OBJECT_OUTSIDE_INVOCATION");scope.live();return scope;}
    public static Object wrap(Class<?> declared,Object value){
        JavaObjectScope scope=current();if(value==null)return null;require(declared.isInstance(value),"HOOK_OBJECT_TYPE_MISMATCH:"+declared.getName());
        return scope.references.computeIfAbsent(value,key->new Ref(scope,key));
    }
    public static Object checked(String type,Object value){
        JavaObjectScope scope=current();Class<?> expected=scope.types.get(type);require(expected!=null&&!expected.isPrimitive(),"HOOK_OBJECT_TYPE_NOT_BOUND:"+type);
        if(value==null)return null;require(value instanceof Ref,"HOOK_OBJECT_REFERENCE_REQUIRED:"+type);Ref ref=(Ref)value;ref.scope.live();require(ref.scope==scope,"HOOK_OBJECT_SCOPE_MISMATCH");
        require(expected.isInstance(ref.value),"HOOK_OBJECT_TYPE_MISMATCH:"+type);return value;
    }
    public static Object unwrap(Class<?> type,Object value){checked(type.getName(),value);return value==null?null:((Ref)value).value;}
    public static Object invocationValue(Object value){if(value instanceof Ref ref){ref.scope.live();require(ref.scope==current(),"HOOK_OBJECT_SCOPE_MISMATCH");}return value;}
    static Object object(Object value){require(value instanceof Ref,"HOOK_OBJECT_REFERENCE_REQUIRED");Ref ref=(Ref)value;ref.scope.live();require(ref.scope==current(),"HOOK_OBJECT_SCOPE_MISMATCH");return ref.value;}
    public static Map<String,Object> info(Object value){
        Object object=object(value);Class<?> type=object.getClass();Map<String,Object> out=new LinkedHashMap<>();out.put("className",type.getName());
        out.put("loader",type.getClassLoader()==null?"BOOTSTRAP":"DECLARING_RUNTIME_LOADER");return Collections.unmodifiableMap(out);
    }
    @Override public void close(){live();closed=true;for(Ref ref:references.values())ref.value=null;references.clear();types.clear();SCOPES.get().pop();if(SCOPES.get().isEmpty())SCOPES.remove();}
}
