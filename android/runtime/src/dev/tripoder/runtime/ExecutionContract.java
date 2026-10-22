package dev.tripoder.runtime;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Single-writer runtime authority. The caller must capture Binder PID/UID at the
 * Binder entry point, before dispatching work, and pass those observed values.
 * This prevents accidental identity confusion; it is not a same-UID sandbox.
 * Session and callback capabilities intentionally do not survive broker restart.
 * The operation journal must be durable; this class has no in-memory Store.
 */
public final class ExecutionContract {
    public enum ProcessState { ALLOCATED, ATTACHED, RUNNING, DEAD, FAILED }
    public enum OperationState { ACCEPTED, RUNNING, SUCCEEDED, FAILED, UNKNOWN }

    /** Writes must durably commit before returning. Reads return full records. */
    public interface Store {
        Operation read(String operationId) throws Exception;
        void write(Operation operation) throws Exception;
        List<Operation> incomplete() throws Exception;
    }
    /** A durable admission check rejected a NEW effect; the journal itself remains healthy. */
    public static final class AdmissionRejected extends IllegalStateException {
        private static final long serialVersionUID=1L;
        public AdmissionRejected(String detail){super(detail);}
    }

    public static final class ContractException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public final String code;
        private ContractException(String code, String message) { super(message); this.code = code; }
        private ContractException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    }

    public static final class Identity {
        public final String spaceId, packageName, installGeneration, processName, processGeneration;
        private Identity(String spaceId, String packageName, String installGeneration,
                         String processName, String processGeneration) {
            this.spaceId = identifier(spaceId, "spaceId");
            this.packageName = packageName(packageName);
            this.installGeneration = identifier(installGeneration, "installGeneration");
            this.processName = processName(processName);
            this.processGeneration = identifier(processGeneration, "processGeneration");
        }
        /** Length-prefixed, unambiguous identity for the operation journal. */
        public String key() {
            return part(spaceId) + part(packageName) + part(installGeneration)
                + part(processName) + part(processGeneration);
        }
    }

    public static final class Session {
        public final Identity identity;
        public final String processGeneration;
        public final ProcessState state;
        public final int pid, uid, expectedUid;
        public final String failureReason;
        private Session(Identity identity, ProcessState state, int pid, int uid,
                        int expectedUid, String failureReason) {
            this.identity = identity; this.processGeneration = identity.processGeneration;
            this.state = state; this.pid = pid; this.uid = uid;
            this.expectedUid = expectedUid; this.failureReason = failureReason;
        }
    }

    public static final class Allocation {
        public final Session session;
        public final String bootstrapToken;
        private Allocation(Session session, String bootstrapToken) {
            this.session = session; this.bootstrapToken = bootstrapToken;
        }
    }

    public static final class Lease {
        public final String processGeneration, ownerId, ownerGeneration;
        private Lease(String processGeneration, String ownerId, String ownerGeneration) {
            this.processGeneration = processGeneration; this.ownerId = ownerId;
            this.ownerGeneration = ownerGeneration;
        }
    }

    /** Immutable journal row. Public constructor is also the strict SQL hydration boundary. */
    public static final class Operation {
        public final String id, callerIdentity, payloadDigest, result, detail, evidence;
        public final OperationState state;
        public final long createdAtMs, updatedAtMs;
        public Operation(String id, String callerIdentity, String payloadDigest,
                         OperationState state, String result, String detail,
                         long createdAtMs, long updatedAtMs) {
            this(id, callerIdentity, payloadDigest, state, result, detail, createdAtMs, updatedAtMs, "");
        }
        public Operation(String id, String callerIdentity, String payloadDigest,
                         OperationState state, String result, String detail,
                         long createdAtMs, long updatedAtMs, String evidence) {
            this.id = identifier(id, "operationId");
            this.callerIdentity = text(callerIdentity, 2048, false, "callerIdentity");
            this.payloadDigest = digest(payloadDigest);
            if (state == null) throw error("INVALID_ARGUMENT", "Operation state is required");
            this.state = state;
            this.result = text(result, 65536, true, "result");
            this.detail = text(detail, 2048, true, "detail");
            this.evidence = text(evidence, 2048, true, "evidence");
            if (state != OperationState.SUCCEEDED && state != OperationState.FAILED && !this.evidence.isEmpty())
                throw error("INVALID_ARGUMENT", "Reconciliation evidence requires a terminal state");
            if (createdAtMs < 0 || updatedAtMs < createdAtMs)
                throw error("INVALID_ARGUMENT", "Invalid operation timestamps");
            if ((state == OperationState.ACCEPTED || state == OperationState.RUNNING)
                    && (!this.result.isEmpty() || !this.detail.isEmpty()))
                throw error("INVALID_ARGUMENT", "Incomplete operation cannot contain a result");
            if (state != OperationState.SUCCEEDED && !this.result.isEmpty())
                throw error("INVALID_ARGUMENT", "Only success can contain a result");
            if ((state == OperationState.FAILED || state == OperationState.UNKNOWN) && this.detail.isEmpty())
                throw error("INVALID_ARGUMENT", "Failure or unknown state requires a reason");
            if (state == OperationState.SUCCEEDED && !this.detail.isEmpty())
                throw error("INVALID_ARGUMENT", "Success cannot contain a failure reason");
            this.createdAtMs = createdAtMs; this.updatedAtMs = updatedAtMs;
        }
    }

    public static final class Acceptance {
        public final Operation operation;
        /** Only true permits the first attempt at the external side effect. */
        public final boolean created;
        private Acceptance(Operation operation, boolean created) {
            this.operation = operation; this.created = created;
        }
    }

    private static final class Owner {
        final Lease lease;
        boolean unknown;
        Owner(Lease lease) { this.lease = lease; }
    }

    private static final class Callback {
        final String sourceGeneration, targetGeneration, operationId;
        final long expiresAtMs;
        Callback(String sourceGeneration, String targetGeneration, String operationId, long expiresAtMs) {
            this.sourceGeneration = sourceGeneration; this.targetGeneration = targetGeneration;
            this.operationId = operationId; this.expiresAtMs = expiresAtMs;
        }
    }

    private final Store store;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, String> bootstraps = new HashMap<>();
    private final Map<String, Owner> owners = new HashMap<>();
    private final Map<String, Callback> callbacks = new HashMap<>();
    private final Set<String> consumedCallbacks = new HashSet<>();
    private boolean recovered;
    private boolean storePoisoned;
    private long lastNow;
    private static final long MAX_CALLBACK_LIFETIME_MS = 300_000;

    public ExecutionContract(Store store) { this(store, System::currentTimeMillis); }
    public ExecutionContract(Store store, LongSupplier nowMs) {
        this.store = Objects.requireNonNull(store, "Durable operation Store required");
        this.clock = Objects.requireNonNull(nowMs, "Clock required");
    }

    /**
     * Called before accepting work. Never resumes external side effects. A partial
     * recovery remains closed and can be retried; already-UNKNOWN rows stay intact.
     */
    public synchronized int onRecover() {
        recovered = false;
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!ended(session)) end(session, ProcessState.FAILED, "BROKER_RECOVERED");
        }
        bootstraps.clear(); callbacks.clear(); owners.clear();
        List<Operation> incomplete;
        try { incomplete = store.incomplete(); }
        catch (Exception failure) { throw storageFailure(failure); }
        if (incomplete == null) throw corrupt("Store returned a null recovery list");
        Set<String> ids = new HashSet<>();
        // Validate the complete enumeration before writing any recovery transitions.
        for (Operation item : incomplete) {
            if (item == null || !ids.add(item.id)) throw corrupt("Invalid recovery enumeration");
            Operation persisted = read(item.id);
            if (persisted == null || !sameRecord(item, persisted)) throw corrupt("Recovery row mismatch");
            if (item.state != OperationState.ACCEPTED && item.state != OperationState.RUNNING)
                throw corrupt("Recovery enumeration contains a completed or unknown operation");
        }
        int count = 0;
        for (Operation item : incomplete) {
            write(change(item, OperationState.UNKNOWN, "", "BROKER_RECOVERY")); count++;
        }
        storePoisoned = false; recovered = true;
        return count;
    }

    public synchronized Allocation allocate(String spaceId, String packageName, String installGeneration,
                                            String processName, int expectedUid) {
        writable();
        if (expectedUid < 0) throw error("INVALID_ARGUMENT", "Expected UID must be non-negative");
        Identity identity = new Identity(spaceId, packageName, installGeneration, processName, randomToken());
        for (Session existing : sessions.values()) {
            Identity prior = existing.identity;
            if (!ended(existing) && prior.spaceId.equals(identity.spaceId)
                    && prior.packageName.equals(identity.packageName) && prior.processName.equals(identity.processName))
                throw error("PROCESS_ALREADY_ACTIVE", "Stop the existing application process before allocating another generation");
        }
        Session session = new Session(identity, ProcessState.ALLOCATED, -1, -1, expectedUid, "");
        String bootstrap = randomToken();
        sessions.put(identity.processGeneration, session); bootstraps.put(bootstrap, identity.processGeneration);
        return new Allocation(session, bootstrap);
    }

    public synchronized Session attach(String bootstrapToken, int pid, int uid) {
        writable(); token(bootstrapToken); observedProcess(pid, uid);
        String generation = bootstraps.get(bootstrapToken);
        if (generation == null) throw error("BOOTSTRAP_INVALID", "Bootstrap capability is missing or already consumed");
        Session session = session(generation);
        if (session.state != ProcessState.ALLOCATED) throw error("PROCESS_STATE", "Session is not allocated");
        if (uid != session.expectedUid) throw error("CALLER_MISMATCH", "Unexpected real UID");
        for (Session active : sessions.values()) {
            if (authenticated(active) && active.pid == pid)
                throw error("PID_ALREADY_ATTACHED", "PID is already bound to another session");
        }
        Session attached = new Session(session.identity, ProcessState.ATTACHED, pid, uid, session.expectedUid, "");
        sessions.put(generation, attached); bootstraps.remove(bootstrapToken);
        return attached;
    }

    public synchronized Session authenticate(String generation, int pid, int uid) {
        writable(); observedProcess(pid, uid);
        Session session = session(generation);
        if (!authenticated(session)) throw error("SESSION_INACTIVE", "Session has not attached or has ended");
        if (session.pid != pid || session.uid != uid) throw error("CALLER_MISMATCH", "Real PID/UID do not match the registered session");
        return session;
    }

    public synchronized Session markRunning(String generation, int pid, int uid) {
        Session session = authenticate(generation, pid, uid);
        if (session.state == ProcessState.RUNNING) return session;
        Session running = new Session(session.identity, ProcessState.RUNNING, pid, uid, session.expectedUid, "");
        sessions.put(generation, running); return running;
    }

    public synchronized Session getSession(String generation) { return session(generation); }
    public synchronized Session markDead(String generation) {
        writable(); Session session = session(generation);
        return ended(session) ? session : end(session, ProcessState.DEAD, "");
    }
    public synchronized Session markFailed(String generation, String reason) {
        writable(); reason = text(reason, 2048, false, "reason"); Session session = session(generation);
        return ended(session) ? session : end(session, ProcessState.FAILED, reason);
    }

    /** Called when this committed install is removed or replaced. */
    public synchronized int invalidateInstall(String spaceId, String packageName, String installGeneration) {
        writable(); identifier(spaceId, "spaceId"); packageName(packageName); identifier(installGeneration, "installGeneration");
        int count = 0;
        for (Session current : new ArrayList<>(sessions.values())) {
            Identity id = current.identity;
            if (!ended(current) && id.spaceId.equals(spaceId) && id.packageName.equals(packageName)
                    && id.installGeneration.equals(installGeneration)) {
                end(current, ProcessState.FAILED, "INSTALL_INVALIDATED"); count++;
            }
        }
        return count;
    }

    public synchronized Lease acquireOwner(String generation, String ownerId) {
        writable(); ownerId = identifier(ownerId, "ownerId"); liveSession(generation);
        Owner prior = owners.get(generation);
        if (prior != null) {
            if (prior.unknown) throw error("OWNER_STATE_UNKNOWN", "Previous deployment state requires explicit reconciliation");
            if (!prior.lease.ownerId.equals(ownerId)) throw error("OWNER_CONFLICT", "Runtime already has a different controller");
            return prior.lease;
        }
        Lease lease = new Lease(generation, ownerId, randomToken());
        owners.put(generation, new Owner(lease)); return lease;
    }

    public synchronized void releaseOwner(String generation, String ownerId, String ownerGeneration, boolean confirmed) {
        writable(); liveSession(generation);
        Owner owner = requireOwner(generation, ownerId, ownerGeneration);
        if (!confirmed) {
            owner.unknown = true;
            throw error("OWNER_STATE_UNKNOWN", "Release was not confirmed; ownership remains reserved");
        }
        owners.remove(generation);
    }

    public synchronized Lease requireOwnerLease(String generation, String ownerId, String ownerGeneration) {
        writable(); liveSession(generation); Owner owner = requireOwner(generation, ownerId, ownerGeneration);
        if (owner.unknown) throw error("OWNER_STATE_UNKNOWN", "Deployment state is unknown");
        return owner.lease;
    }

    /** callerIdentity must be derived by the Broker, never copied from guest extras. */
    public synchronized Acceptance accept(String id, String callerIdentity, String payloadDigest) {
        writable(); identifier(id, "operationId"); text(callerIdentity, 2048, false, "callerIdentity"); digest(payloadDigest);
        Operation existing = read(id);
        if (existing != null) {
            if (!existing.callerIdentity.equals(callerIdentity) || !existing.payloadDigest.equals(payloadDigest))
                throw error("OPERATION_CONFLICT", "Operation ID is already bound to a different request or caller");
            return new Acceptance(existing, false);
        }
        long time = now();
        Operation operation = new Operation(id, callerIdentity, payloadDigest, OperationState.ACCEPTED, "", "", time, time);
        write(operation); return new Acceptance(operation, true);
    }

    public synchronized Operation start(String id) {
        writable(); Operation current = requiredOperation(id);
        if (current.state != OperationState.ACCEPTED)
            throw error("OPERATION_NOT_STARTABLE", "Only a newly accepted operation may start");
        Operation running = change(current, OperationState.RUNNING, "", ""); write(running); return running;
    }
    public synchronized Operation succeed(String id, String result) {
        return finish(id, OperationState.SUCCEEDED, text(result, 65536, true, "result"), "");
    }
    public synchronized Operation fail(String id, String errorCode) {
        return finish(id, OperationState.FAILED, "", identifier(errorCode, "errorCode"));
    }
    public synchronized Operation markUnknown(String id, String reason) {
        writable(); reason = text(reason, 2048, false, "reason"); Operation current = requiredOperation(id);
        if (current.state == OperationState.UNKNOWN) return current;
        if (current.state != OperationState.ACCEPTED && current.state != OperationState.RUNNING)
            throw error("TERMINAL_IMMUTABLE", "Cannot change a completed operation");
        Operation unknown = change(current, OperationState.UNKNOWN, "", reason); write(unknown); return unknown;
    }
    /** Broker-only coordination after observing the actual outcome; never executes the action. */
    public synchronized Operation reconcile(String id, OperationState terminal, String value, String evidence) {
        writable(); evidence = text(evidence, 2048, false, "evidence");
        if (terminal != OperationState.SUCCEEDED && terminal != OperationState.FAILED)
            throw error("INVALID_ARGUMENT", "Reconciliation requires succeeded or failed");
        String result = terminal == OperationState.SUCCEEDED ? text(value, 65536, true, "result") : "";
        String detail = terminal == OperationState.FAILED ? identifier(value, "errorCode") : "";
        Operation current = requiredOperation(id);
        if (current.state == terminal && current.result.equals(result) && current.detail.equals(detail)
                && current.evidence.equals(evidence)) return current;
        if (current.state != OperationState.UNKNOWN)
            throw error("RECONCILIATION_STATE", "Only an unknown operation can be reconciled");
        Operation resolved = new Operation(current.id, current.callerIdentity, current.payloadDigest,
            terminal, result, detail, current.createdAtMs, Math.max(now(), current.updatedAtMs), evidence);
        write(resolved); return resolved;
    }
    /** Read only: never starts, retries, cancels or reconciles an external action. */
    public synchronized Operation query(String id) { identifier(id, "operationId"); return read(id); }

    public synchronized String issueCallback(String sourceGeneration, String targetGeneration,
                                             String operationId, long expiryMs) {
        writable(); Session source = liveSession(sourceGeneration), target = session(targetGeneration);
        // A reserved immutable destination need not have executed yet. This is
        // an unconsumed capability, not a delivery receipt. consumeCallback
        // still authenticates the actual attached target PID/UID/generation.
        if(ended(target))throw error("SESSION_INACTIVE", "Callback target reservation has ended");
        if (!source.identity.spaceId.equals(target.identity.spaceId))
            throw error("CALLBACK_SPACE_MISMATCH", "Callback participants must be in the same space");
        Operation operation = requiredOperation(operationId);
        if (operation.state != OperationState.RUNNING) throw error("CALLBACK_OPERATION_STATE", "Callback requires a running operation");
        if (!operation.callerIdentity.equals(source.identity.key()))
            throw error("CALLBACK_CALLER_MISMATCH", "Operation is not owned by the source session");
        long time = now();
        if (expiryMs <= time || expiryMs - time > MAX_CALLBACK_LIFETIME_MS)
            throw error("CALLBACK_EXPIRY", "Callback lifetime must be positive and no more than five minutes");
        String value = randomToken();
        callbacks.put(value, new Callback(sourceGeneration, targetGeneration, operationId, expiryMs));
        return value;
    }

    /** Target returns a result; the Broker receives the authoritative source session. */
    public synchronized Session consumeCallback(String value, String targetGeneration, int pid, int uid, String operationId) {
        writable(); token(value); identifier(operationId, "operationId");
        if (consumedCallbacks.contains(value)) throw error("CALLBACK_REPLAY", "Callback has already been consumed");
        Callback callback = callbacks.get(value);
        if (callback == null) throw error("CALLBACK_INVALID", "Callback capability is missing or revoked");
        if (now() >= callback.expiresAtMs) {
            callbacks.remove(value); throw error("CALLBACK_EXPIRED", "Callback capability has expired");
        }
        if (!callback.targetGeneration.equals(targetGeneration) || !callback.operationId.equals(operationId))
            throw error("CALLBACK_TARGET_MISMATCH", "Callback target or operation does not match");
        authenticate(targetGeneration, pid, uid);
        Session source = liveSession(callback.sourceGeneration);
        if (requiredOperation(operationId).state != OperationState.RUNNING)
            throw error("CALLBACK_OPERATION_STATE", "Callback operation is no longer running");
        callbacks.remove(value); consumedCallbacks.add(value); return source;
    }

    private Operation finish(String id, OperationState state, String result, String detail) {
        writable(); Operation current = requiredOperation(id);
        if (current.state == state && current.result.equals(result) && current.detail.equals(detail)) return current;
        if (current.state == OperationState.UNKNOWN) throw error("OPERATION_UNKNOWN", "Unknown result requires evidence-based reconciliation, not replay");
        if (current.state == OperationState.SUCCEEDED || current.state == OperationState.FAILED)
            throw error("TERMINAL_IMMUTABLE", "Cannot alter a completed operation");
        if (current.state != OperationState.RUNNING) throw error("OPERATION_NOT_RUNNING", "Start the operation before recording a result");
        Operation next = change(current, state, result, detail); write(next); return next;
    }
    private Owner requireOwner(String generation, String ownerId, String ownerGeneration) {
        identifier(ownerId, "ownerId"); token(ownerGeneration);
        Owner owner = owners.get(generation);
        if (owner == null || !owner.lease.ownerId.equals(ownerId) || !owner.lease.ownerGeneration.equals(ownerGeneration))
            throw error("OWNER_STALE", "Control lease is missing or stale");
        return owner;
    }
    private Session end(Session session, ProcessState state, String reason) {
        String generation = session.processGeneration;
        Session ended = new Session(session.identity, state, session.pid, session.uid, session.expectedUid, reason);
        sessions.put(generation, ended); owners.remove(generation);
        bootstraps.entrySet().removeIf(entry -> entry.getValue().equals(generation));
        callbacks.entrySet().removeIf(entry -> entry.getValue().sourceGeneration.equals(generation)
            || entry.getValue().targetGeneration.equals(generation));
        return ended;
    }
    private Session session(String generation) {
        identifier(generation, "processGeneration"); Session session = sessions.get(generation);
        if (session == null) throw error("SESSION_MISSING", "Process generation is not registered");
        return session;
    }
    private Session liveSession(String generation) {
        Session session = session(generation);
        if (!authenticated(session)) throw error("SESSION_INACTIVE", "Session has not attached or has ended");
        return session;
    }
    private static boolean authenticated(Session session) { return session.state == ProcessState.ATTACHED || session.state == ProcessState.RUNNING; }
    private static boolean ended(Session session) { return session.state == ProcessState.DEAD || session.state == ProcessState.FAILED; }
    private Operation requiredOperation(String id) {
        identifier(id, "operationId"); Operation operation = read(id);
        if (operation == null) throw error("OPERATION_MISSING", "Operation has not been accepted");
        return operation;
    }
    private Operation read(String id) {
        try {
            Operation result = store.read(id);
            if (result != null && !id.equals(result.id)) throw corrupt("Store returned another operation");
            return result;
        } catch (ContractException failure) { throw failure; }
        catch (Exception failure) { throw storageFailure(failure); }
    }
    private void write(Operation operation) {
        try { store.write(operation); }
        catch (AdmissionRejected rejected) { throw error("RECOVERY_SCOPE_UNKNOWN", rejected.getMessage()); }
        catch (Exception failure) { throw storageFailure(failure); }
    }
    private ContractException storageFailure(Exception failure) {
        storePoisoned = true;
        return new ContractException("STORE_UNAVAILABLE", "Journal outcome is unknown; recover before performing side effects", failure);
    }
    private ContractException corrupt(String reason) { storePoisoned = true; return error("STORE_CORRUPT", reason); }
    private void writable() {
        if (!recovered) throw error("RECOVERY_REQUIRED", "Recover the durable journal before accepting work");
        if (storePoisoned) throw error("STORE_UNAVAILABLE", "Journal must be recovered before accepting work");
    }
    private Operation change(Operation current, OperationState state, String result, String detail) {
        return new Operation(current.id, current.callerIdentity, current.payloadDigest, state, result, detail,
            current.createdAtMs, Math.max(now(), current.updatedAtMs));
    }
    private long now() {
        long time = clock.getAsLong();
        if (time < 0) throw error("CLOCK_INVALID", "Clock returned a negative timestamp");
        lastNow = Math.max(lastNow, time); return lastNow;
    }
    private String randomToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        StringBuilder text = new StringBuilder(64);
        for (byte value : bytes) { text.append(Character.forDigit((value >>> 4) & 15, 16)); text.append(Character.forDigit(value & 15, 16)); }
        return text.toString();
    }
    private static String token(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw error("INVALID_ARGUMENT", "Capability must be 256-bit hex");
        return value;
    }
    private static String digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw error("INVALID_ARGUMENT", "Expected lowercase SHA-256 digest");
        return value;
    }
    private static String identifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw error("INVALID_ARGUMENT", "Invalid " + field);
        return value;
    }
    private static String packageName(String value) {
        if (value == null || value.length() > 255 || !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw error("INVALID_ARGUMENT", "Invalid package name");
        return value;
    }
    private static String processName(String value) {
        if (value == null || value.length() > 255
                || !value.matches("(?:[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*)(?::[A-Za-z0-9_]+)?|:[A-Za-z0-9_]+"))
            throw error("INVALID_ARGUMENT", "Invalid process name");
        return value;
    }
    private static String text(String value, int max, boolean emptyAllowed, String field) {
        if (value == null || value.length() > max || value.getBytes(StandardCharsets.UTF_8).length > max
                || (!emptyAllowed && value.trim().isEmpty()) || value.indexOf('\0') >= 0)
            throw error("INVALID_ARGUMENT", "Invalid " + field);
        return value;
    }
    private static String part(String value) { return value.length() + ":" + value; }
    private static void observedProcess(int pid, int uid) {
        if (pid <= 0 || uid < 0) throw error("INVALID_ARGUMENT", "Invalid observed process identity");
    }
    private static boolean sameRecord(Operation a, Operation b) {
        return a.id.equals(b.id) && a.callerIdentity.equals(b.callerIdentity) && a.payloadDigest.equals(b.payloadDigest)
            && a.state == b.state && a.result.equals(b.result) && a.detail.equals(b.detail)
            && a.createdAtMs == b.createdAtMs && a.updatedAtMs == b.updatedAtMs && a.evidence.equals(b.evidence);
    }
    private static ContractException error(String code, String message) { return new ContractException(code, message); }
}
