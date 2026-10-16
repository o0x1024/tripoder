package dev.tripoder.runtime.script;

/** A backend proved that no target entry was changed and cancelled all of its
 * unpublished preparations. This is never used after partial publication. */
public final class HookPublicationRejected extends Exception {
    private static final long serialVersionUID=1L;
    public HookPublicationRejected(Throwable cause){super("HOOK_PUBLICATION_UNCHANGED:"+cause.getMessage(),cause);}
}
