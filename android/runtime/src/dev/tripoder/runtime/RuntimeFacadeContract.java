package dev.tripoder.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The exception contract between a guest application and the runtime facades it calls.
 *
 * <p>The platform never fails an application because of an internal authority decision: an app that
 * calls {@code Context.sendBroadcast} with an odd intent shape, broadcast options, or a Binder extra
 * gets its broadcast delivered (or silently ignored), never a crash. A runtime facade that surfaces
 * its own policy refusal as an unchecked exception therefore decides the guest's process lifetime on
 * the authority's behalf — BOSS直聘 died exactly that way on 2026-11-13.
 *
 * <p>So a facade may only surface what the platform itself would:
 * <ul>
 *   <li>{@link SecurityException} — a permission or loader refusal, which the platform raises too;</li>
 *   <li>{@link IllegalArgumentException} — only for an argument the platform also rejects (null intent, null filter).</li>
 * </ul>
 * Every other internal failure is recorded through {@link #drop} and reduced to a no-op or to the
 * nearest honest delivery, so a runtime limitation costs a feature instead of an application.
 */
final class RuntimeFacadeContract {
    private static final int LIMIT=64;
    private static final Map<String,Integer> COUNTS=new LinkedHashMap<>();
    /** One bounded record per code (twice at most): a repeating limitation must not become log spam. */
    static void drop(String code,Throwable error){
        String detail=error==null?"":error.getClass().getName()+(error.getMessage()==null?"":":"+error.getMessage());
        synchronized(COUNTS){
            int seen=COUNTS.getOrDefault(code,0);
            if(seen>=2)return;
            if(seen==0&&COUNTS.size()>=LIMIT)return;
            COUNTS.put(code,seen+1);
        }
        android.util.Log.w("TripoderFacade",code+" dropped"+(detail.isEmpty()?"":" cause="+detail));
    }
    /** True when the platform itself would fail the caller this way, so a facade may pass it through. */
    static boolean platformAnswer(Throwable error){
        return error instanceof SecurityException;
    }
    /**
     * Records a limitation and answers the way the platform answers a call that did nothing
     * ({@code null}, {@code false}, an empty list). Use this instead of throwing from a facade.
     */
    static <T> T nothing(String code,Throwable error,T answer){
        drop(code,error);
        return answer;
    }
    /**
     * Narrows a flag word to the bits this facade implements. A newer caller on an older platform gets its
     * unknown bits ignored rather than an exception — that is how the platform treats flags it does not know.
     */
    static int flagBits(String code,long flags){
        if((flags&~0xFFFFFFFFL)==0)return (int)flags;
        drop(code,null);
        return (int)(flags&0xFFFFFFFFL);
    }
    private RuntimeFacadeContract(){}
}
