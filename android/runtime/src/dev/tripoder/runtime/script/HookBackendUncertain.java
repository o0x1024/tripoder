package dev.tripoder.runtime.script;

/** Publication ownership is uncertain: never serialize this as a definitive FAILED command ACK. */
public final class HookBackendUncertain extends Error {
    private static final long serialVersionUID = 1L;
    HookBackendUncertain(Throwable cause) {super("HOOK_BACKEND_UNCERTAIN_PROCESS_MUST_TERMINATE",cause);}
}
