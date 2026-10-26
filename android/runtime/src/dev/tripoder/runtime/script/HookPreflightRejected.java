package dev.tripoder.runtime.script;

/** A known rejection before any guest constructor, method or runtime mutation is invoked. */
public final class HookPreflightRejected extends IllegalArgumentException {
    private static final long serialVersionUID=1L;
    public HookPreflightRejected(String message){super(message);}
}
