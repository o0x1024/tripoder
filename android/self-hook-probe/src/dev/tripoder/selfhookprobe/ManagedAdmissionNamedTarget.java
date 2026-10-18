package dev.tripoder.selfhookprobe;
/** Uses ART's symbol-name resolution, with the same JNI signature as canRead. */
public final class ManagedAdmissionNamedTarget {
    static {System.setProperty("tripoder.managed.admission.named.initialized","yes");}
    public static native boolean read(String value);
    private ManagedAdmissionNamedTarget(){}
}
