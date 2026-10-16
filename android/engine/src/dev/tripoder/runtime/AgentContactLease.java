package dev.tripoder.runtime;

/** Monotonic deadlines; waiting for the first authenticated client is not a disconnect. */
final class AgentContactLease {
    static final long LIFETIME = 600_000, FIRST_CONTACT_TIMEOUT = 480_000, CONTACT_TIMEOUT = 45_000;
    final long expires, firstContactDeadline;
    private boolean connected;
    private long contact;
    AgentContactLease(long now) { expires=now+LIFETIME;firstContactDeadline=now+FIRST_CONTACT_TIMEOUT; }
    boolean connected() { return connected; }
    long deadline() { return Math.min(expires,connected?contact+CONTACT_TIMEOUT:firstContactDeadline); }
    String expiry(long now) {
        if(now>=expires)return "EXPIRED";
        if(now>=deadline())return connected?"CONTACT_EXPIRED":"CLIENT_CONNECT_TIMEOUT";
        return null;
    }
    void touch(long now) {
        String reason=expiry(now);if(reason!=null)throw new IllegalStateException(reason);
        connected=true;contact=now;
    }
}
