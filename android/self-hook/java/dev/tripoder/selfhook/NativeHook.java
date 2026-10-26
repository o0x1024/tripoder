package dev.tripoder.selfhook;
/** Immutable Native export activations. Signature/value conversion belongs to
 * the product codec. No raw address is exposed to the restricted script. */
public final class NativeHook {
    /** Known refusal before changing any target entry. No operation replay. */
    public static final class Busy extends IllegalStateException {
        private static final long serialVersionUID=1L;
        public Busy(){super("NATIVE_BUSY_NO_ENTRY_CHANGED");}
    }
    public static final int VOID=0,I32=1,U32=2,I64=3,U64=4,F32=5,F64=6;
    public interface Handler {Result invoke(Invocation call) throws Throwable;}
    public static final class Result {
        public final long bits;public final int errorNumber;
        public Result(long bits,int errorNumber){this.bits=bits;this.errorNumber=errorNumber;}
    }
    public static final class Invocation {
        private final long token;private final long[] arguments;
        private final Thread owner=Thread.currentThread();private final int incoming;
        private boolean called,closed,completed;
        private long[] calledArguments;
        private Invocation(long token,long[] arguments,int incoming){this.token=token;this.arguments=arguments;this.incoming=incoming;}
        public long[] arguments(){return arguments.clone();}
        public int incomingErrno(){return incoming;}
        public Result original(long[] replacement,int errorNumber){
            if(owner!=Thread.currentThread()||called||closed)throw new IllegalStateException("NATIVE_ORIGINAL_NOT_OWNED");
            called=true;calledArguments=replacement.clone();Result value=original0(token,calledArguments,errorNumber);
            if(value==null)throw new IllegalStateException("NATIVE_ORIGINAL_REJECTED");completed=true;return value;
        }
        public byte[] snapshotArgument(int index,int length){
            if(owner!=Thread.currentThread()||closed||!completed)throw new IllegalStateException("NATIVE_BUFFER_AFTER_ORIGINAL_REQUIRED");
            if(index<0||index>=calledArguments.length||length<1||length>1024)throw new IllegalArgumentException("NATIVE_BUFFER_BOUNDS");
            return snapshot0(calledArguments[index],length);
        }
    }
    private static final class Context {
        final Class<Result> resultType=Result.class;final Handler handler;
        Context(Handler handler){this.handler=handler;}
        @SuppressWarnings("unused") private Result dispatchNative(long token,long[] values,int incoming) throws Throwable {
            Invocation invocation=new Invocation(token,values,incoming);
            try {Result result=handler.invoke(invocation);if(result==null)throw new IllegalStateException("NATIVE_RESULT_REQUIRED");return result;}
            finally {invocation.closed=true;}
        }
    }
    public static final class Handle {
        private final int id;private final Context context;private int phase=1;
        private Handle(int id,Context context){this.id=id;this.context=context;}
        public int nativeId(){return id;}
    }
    private NativeHook(){}
    /** Observation only: zero in-flight is not permission to reclaim code. */
    public static java.util.Map<String,Object> resources(){
        long[] values=resources0();if(values==null||values.length!=12)throw new IllegalStateException("NATIVE_RESOURCE_REPORT");
        String[] names={"issued","prepared","active","retired","failed","inFlight","inboundUsed","retainedImages","jniGlobalRoots","jniOwnedThreadLeases","jniThreadAttachments","jniThreadDetachments"};
        java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();
        for(int i=0;i<names.length;i++)result.put(names[i],values[i]);
        result.put("publishedResourcesRetained",true);return java.util.Collections.unmodifiableMap(result);
    }
    private static boolean contractChecked;
    private static synchronized void requireContract(){
        if(contractChecked)return;long[] expected={4,VOID,I32,U32,I64,U64,F32,F64,16,1024};
        if(!java.util.Arrays.equals(expected,contract0()))throw new IllegalStateException("NATIVE_ABI_CONTRACT_MISMATCH");contractChecked=true;
    }
    public static Handle prepare(int module,long generation,long exportRva,String canonicalPath,int result,int[] parameters,Handler handler){
        if(module<0||generation<=0||generation>0xffffffffL||exportRva<0||(exportRva&3)!=0||handler==null||parameters==null||parameters.length>16||result<VOID||result>F64)throw new IllegalArgumentException("NATIVE_BINDING");
        requireContract();
        int[] types=parameters.clone();for(int type:types)if(type<I32||type>F64)throw new IllegalArgumentException("NATIVE_ARGUMENT_TYPE");
        Context context=new Context(handler);int id=prepare0(module,generation,exportRva,NativeModules.path(canonicalPath),result,types,context);
        if(id<0)throw new IllegalStateException("NATIVE_PREPARATION_REJECTED");return new Handle(id,context);
    }
    public static synchronized void publish(Handle... handles){
        int[] ids=ids(handles,1);int result=publish0(ids);if(result==1)throw new Busy();if(result!=0)throw new IllegalStateException("NATIVE_PUBLICATION_REJECTED");
        for(Handle handle:handles)handle.phase=2;
    }
    public static synchronized void retire(Handle... handles){
        if(handles==null||handles.length==0)throw new IllegalArgumentException("NATIVE_RETIRE_BATCH");
        int phase=handles[0]==null?0:handles[0].phase;if(phase!=1&&phase!=2)throw new IllegalStateException("NATIVE_RETIRE_STATE");
        int[] ids=ids(handles,phase);int result=retire0(ids);if(result==1)throw new Busy();if(result!=0)throw new IllegalStateException("NATIVE_RETIRE_REJECTED");
        for(Handle handle:handles)handle.phase=3;
    }
    private static int[] ids(Handle[] handles,int phase){
        if(handles==null||handles.length==0||handles.length>1024)throw new IllegalArgumentException("NATIVE_BATCH_BUDGET");
        int[] ids=new int[handles.length];for(int i=0;i<ids.length;i++){
            Handle handle=handles[i];if(handle==null||handle.phase!=phase)throw new IllegalStateException("NATIVE_HANDLE_STATE");
            for(int j=0;j<i;j++)if(handles[j]==handle)throw new IllegalArgumentException("NATIVE_DUPLICATE_HANDLE");ids[i]=handle.id;
        }return ids;
    }
    private static native int prepare0(int module,long generation,long rva,byte[] path,int result,int[] arguments,Context context);
    private static native int publish0(int[] ids);
    private static native int retire0(int[] ids);
    private static native Result original0(long token,long[] arguments,int incomingErrno);
    private static native long[] contract0();
    private static native long[] resources0();
    private static native byte[] snapshot0(long address,int length);
}
