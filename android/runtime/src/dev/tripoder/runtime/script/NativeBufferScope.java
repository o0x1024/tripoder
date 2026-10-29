package dev.tripoder.runtime.script;

import java.math.BigInteger;
import java.util.*;
import static dev.tripoder.runtime.script.ScriptFault.require;

/** Invocation-owned, after-return byte observation. No script-supplied address or memory writes. */
public final class NativeBufferScope implements AutoCloseable {
    @FunctionalInterface public interface Reader { byte[] read(int argument,int length); }
    private static final ThreadLocal<ArrayDeque<NativeBufferScope>> SCOPES=ThreadLocal.withInitial(ArrayDeque::new);
    private final String symbol,resultType;
    private final List<String> types;
    private final Reader reader;
    private final Thread owner=Thread.currentThread();
    private List<Object> arguments;
    private Object result;
    private boolean completed,closed,captured;
    public NativeBufferScope(String symbol,List<String> types,String resultType,Reader reader){
        this(symbol,types,resultType,reader,null);
    }
    public NativeBufferScope(String symbol,List<String> types,String resultType,Reader reader,List<Object> initialArguments){
        this.symbol=symbol;this.types=List.copyOf(types);this.resultType=resultType;this.reader=reader;
        if(initialArguments!=null)this.arguments=List.copyOf(initialArguments);
        SCOPES.get().push(this);
    }
    public void complete(List<Object> arguments,Object result){
        live();require(!completed,"NATIVE_BUFFER_COMPLETED");this.arguments=List.copyOf(arguments);this.result=result;completed=true;
    }
    private void live(){require(!closed&&owner==Thread.currentThread()&&SCOPES.get().peek()==this,"NATIVE_BUFFER_SCOPE_EXPIRED");}
    public static Map<String,Object> capture(int pointerArgument,int capacityArgument,int maxBytes){
        NativeBufferScope scope=SCOPES.get().peek();require(scope!=null,"NATIVE_BUFFER_OUTSIDE_INVOCATION");scope.live();
        boolean isBefore=!scope.completed;
        if(isBefore&&scope.arguments==null)require(false,"NATIVE_BUFFER_AFTER_ORIGINAL_REQUIRED");
        require(HookInvocationSession.nativeObservationSymbol(scope.symbol),"NATIVE_BUFFER_NATIVE_REQUIRED");
        require(!scope.captured,"NATIVE_BUFFER_ONCE_PER_INVOCATION");
        require(pointerArgument>=0&&capacityArgument>=0&&pointerArgument<scope.types.size()&&capacityArgument<scope.types.size()&&pointerArgument!=capacityArgument,"NATIVE_BUFFER_ARGUMENT_INDEX");
        require(scope.types.get(pointerArgument).equals("uint64"),"NATIVE_BUFFER_ADDRESS_ABI");
        require(Set.of("int32","uint32","int64","uint64").contains(scope.types.get(capacityArgument)),"NATIVE_BUFFER_LENGTH_ABI");
        if(!isBefore)require(Set.of("int32","int64").contains(scope.resultType),"NATIVE_BUFFER_LENGTH_ABI");
        require(maxBytes>=1&&maxBytes<=1024,"NATIVE_BUFFER_LIMIT");scope.captured=true;
        BigInteger capacity=integer(scope.arguments.get(capacityArgument)),actual=isBefore?capacity:integer(scope.result);
        Map<String,Object> out=new LinkedHashMap<>();out.put("stage",isBefore?"before":"after");out.put("capacity",capacity.toString());out.put("result",actual.toString());out.put("capturedBytes",0d);out.put("hex","");out.put("truncated",false);
        if(actual.signum()<=0){out.put("state","NO_DATA");return Collections.unmodifiableMap(out);}
        if(capacity.signum()<0||(!isBefore&&actual.compareTo(capacity)>0)){out.put("state","INVALID_LENGTH");return Collections.unmodifiableMap(out);}
        int count=actual.min(BigInteger.valueOf(maxBytes)).intValueExact();out.put("truncated",actual.compareTo(BigInteger.valueOf(count))>0);
        byte[] bytes=scope.reader.read(pointerArgument,count);
        if(bytes==null||bytes.length!=count){out.put("state","UNAVAILABLE");return Collections.unmodifiableMap(out);}
        out.put("state","COMPLETE");out.put("capturedBytes",(double)count);out.put("hex",HexFormat.of().formatHex(bytes));return Collections.unmodifiableMap(out);
    }
    public static String readCString(int pointerArgument,int maxBytes){
        NativeBufferScope scope=SCOPES.get().peek();require(scope!=null,"NATIVE_BUFFER_OUTSIDE_INVOCATION");scope.live();
        require(scope.arguments!=null,"NATIVE_BUFFER_ARGUMENTS_UNAVAILABLE");
        require(HookInvocationSession.nativeObservationSymbol(scope.symbol),"NATIVE_BUFFER_NATIVE_REQUIRED");
        require(pointerArgument>=0&&pointerArgument<scope.types.size(),"NATIVE_BUFFER_ARGUMENT_INDEX");
        require(scope.types.get(pointerArgument).equals("uint64"),"NATIVE_BUFFER_ADDRESS_ABI");
        require(maxBytes>=1&&maxBytes<=1024,"NATIVE_BUFFER_LIMIT");
        BigInteger ptr=integer(scope.arguments.get(pointerArgument));
        if(ptr.signum()==0)return null;
        byte[] bytes=scope.reader.read(pointerArgument,maxBytes);
        if(bytes==null||bytes.length==0)return null;
        int len=0;while(len<bytes.length&&bytes[len]!=0)len++;
        return new String(bytes,0,len,java.nio.charset.StandardCharsets.UTF_8);
    }
    private static BigInteger integer(Object value){
        if(value instanceof BigInteger integer)return integer;
        require(value instanceof Double&&Double.isFinite((Double)value)&&(Double)value==Math.rint((Double)value),"NATIVE_BUFFER_INTEGER_REQUIRED");return BigInteger.valueOf(((Double)value).longValue());
    }
    @Override public void close(){live();closed=true;SCOPES.get().pop();if(SCOPES.get().isEmpty())SCOPES.remove();}
}
