package dev.tripoder.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import dev.tripoder.runtime.ExecutionContract.*;

/** Plain javac tests. This test-only store is never included in an Android APK. */
public final class ExecutionContractTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_HASH = "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final List<String> checks = new ArrayList<>();
    private static final class Clock implements LongSupplier {
        long time = 1_000;
        @Override public long getAsLong() { return time; }
    }
    private static final class MemoryStore implements Store {
        final Map<String, Operation> rows = new HashMap<>();
        int writes, reads, failAtWrite = -1;
        boolean throwAfterCommit;boolean rejectAdmission;
        @Override public Operation read(String id) { reads++; return rows.get(id); }
        @Override public void write(Operation row) throws Exception {
            if(rejectAdmission&&row.state==OperationState.ACCEPTED)throw new AdmissionRejected("original-unknown");
            writes++;
            if (writes == failAtWrite && !throwAfterCommit) throw new java.io.IOException("synthetic storage failure");
            rows.put(row.id, row);
            if (writes == failAtWrite) throw new java.io.IOException("synthetic lost commit acknowledgement");
        }
        @Override public List<Operation> incomplete() {
            List<Operation> result = new ArrayList<>();
            for (Operation row : rows.values())
                if (row.state == OperationState.ACCEPTED || row.state == OperationState.RUNNING) result.add(row);
            return result;
        }
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void check(String name, boolean passed) {
        if (!passed) throw new AssertionError(name);
        checks.add(name);
    }
    private static void rejects(String name, String expected, Work action) throws Exception {
        try { action.run(); }
        catch (ContractException failure) {
            check(name, expected.equals(failure.code)); return;
        }
        throw new AssertionError(name + " was not rejected");
    }
    private static ExecutionContract ready(MemoryStore store, Clock clock) {
        ExecutionContract contract = new ExecutionContract(store, clock); contract.onRecover(); return contract;
    }
    private static Session running(ExecutionContract contract, String space, String pkg, String install, int pid) {
        Allocation allocated = contract.allocate(space, pkg, install, pkg, 10234);
        contract.attach(allocated.bootstrapToken, pid, 10234);
        return contract.markRunning(allocated.session.processGeneration, pid, 10234);
    }
    private static void runOperation(ExecutionContract contract, Session source, String id) {
        contract.accept(id, source.identity.key(), HASH); contract.start(id);
    }

    private static void identities() throws Exception {
        MemoryStore store = new MemoryStore(); Clock clock = new Clock();
        ExecutionContract contract = new ExecutionContract(store, clock);
        rejects("allocation-requires-recovery", "RECOVERY_REQUIRED",
            () -> contract.allocate("space", "dev.test.a", "install1", "dev.test.a", 10234));
        rejects("accept-requires-recovery", "RECOVERY_REQUIRED", () -> contract.accept("op", "controller", HASH));
        check("empty-recovery", contract.onRecover() == 0);
        rejects("space-path-rejected", "INVALID_ARGUMENT",
            () -> contract.allocate("../space", "dev.test.a", "install1", "dev.test.a", 10234));
        rejects("package-path-rejected", "INVALID_ARGUMENT",
            () -> contract.allocate("space", "../a", "install1", "dev.test.a", 10234));
        rejects("process-name-path-rejected", "INVALID_ARGUMENT",
            () -> contract.allocate("space", "dev.test.a", "install1", "../a", 10234));
        Allocation first = contract.allocate("space", "dev.test.a", "install1", "dev.test.a", 10234);
        String generation = first.session.processGeneration;
        check("allocated-identity-immutable-snapshot", first.session.state == ProcessState.ALLOCATED
            && first.session.pid == -1 && first.session.identity.installGeneration.equals("install1"));
        check("bootstrap-and-process-generation-distinct", first.bootstrapToken.length() == 64
            && generation.length() == 64 && !generation.equals(first.bootstrapToken));
        rejects("allocated-cannot-authenticate", "SESSION_INACTIVE", () -> contract.authenticate(generation, 100, 10234));
        rejects("wrong-uid-cannot-attach", "CALLER_MISMATCH", () -> contract.attach(first.bootstrapToken, 100, 999));
        rejects("invalid-pid-cannot-attach", "INVALID_ARGUMENT", () -> contract.attach(first.bootstrapToken, 0, 10234));
        Session attached = contract.attach(first.bootstrapToken, 100, 10234);
        check("attach-binds-real-identity", attached.state == ProcessState.ATTACHED && attached.pid == 100 && attached.uid == 10234);
        check("allocation-snapshot-does-not-mutate", first.session.state == ProcessState.ALLOCATED);
        rejects("bootstrap-single-use", "BOOTSTRAP_INVALID", () -> contract.attach(first.bootstrapToken, 100, 10234));
        rejects("pid-cannot-be-spoofed", "CALLER_MISMATCH", () -> contract.authenticate(generation, 101, 10234));
        rejects("uid-cannot-be-spoofed", "CALLER_MISMATCH", () -> contract.authenticate(generation, 100, 999));
        Session live = contract.markRunning(generation, 100, 10234);
        check("running-transition", live.state == ProcessState.RUNNING);
        rejects("live-process-cannot-change-install", "PROCESS_ALREADY_ACTIVE",
            () -> contract.allocate("space", "dev.test.a", "install2", "dev.test.a", 10234));
        Allocation second = contract.allocate("space", "dev.test.b", "install1", "dev.test.b", 10234);
        rejects("one-pid-one-session", "PID_ALREADY_ATTACHED", () -> contract.attach(second.bootstrapToken, 100, 10234));
        contract.markDead(generation);
        rejects("dead-session-cannot-authenticate", "SESSION_INACTIVE", () -> contract.authenticate(generation, 100, 10234));
        Session reused = contract.attach(second.bootstrapToken, 100, 10234);
        check("pid-reuse-gets-different-generation", reused.pid == live.pid && !reused.processGeneration.equals(generation));
        rejects("pid-reuse-does-not-revive-old-session", "SESSION_INACTIVE", () -> contract.authenticate(generation, 100, 10234));
        Session otherSpace = running(contract, "otherSpace", "dev.test.b", "install1", 101);
        check("different-space-same-package-permitted", otherSpace.identity.spaceId.equals("otherSpace"));
        check("install-invalidation-targets-only-one-space", contract.invalidateInstall("space", "dev.test.b", "install1") == 1);
        check("install-invalidation-retains-other-space", contract.getSession(reused.processGeneration).state == ProcessState.FAILED
            && contract.authenticate(otherSpace.processGeneration, 101, 10234).state == ProcessState.RUNNING);
        Allocation neverAttached = contract.allocate("space", "dev.test.c", "install1", "dev.test.c", 10234);
        contract.markDead(neverAttached.session.processGeneration);
        rejects("death-revokes-bootstrap", "BOOTSTRAP_INVALID", () -> contract.attach(neverAttached.bootstrapToken, 102, 10234));
    }

    private static void owners() throws Exception {
        ExecutionContract contract = ready(new MemoryStore(), new Clock());
        Session a = running(contract, "space", "dev.test.a", "install1", 100);
        Lease owner = contract.acquireOwner(a.processGeneration, "projectA");
        check("same-owner-acquisition-idempotent", contract.acquireOwner(a.processGeneration, "projectA").ownerGeneration.equals(owner.ownerGeneration));
        rejects("conflicting-owner-rejected", "OWNER_CONFLICT", () -> contract.acquireOwner(a.processGeneration, "projectB"));
        rejects("forged-owner-generation-rejected", "OWNER_STALE",
            () -> contract.releaseOwner(a.processGeneration, "projectA", HASH, true));
        rejects("unconfirmed-release-retains-owner", "OWNER_STATE_UNKNOWN",
            () -> contract.releaseOwner(a.processGeneration, "projectA", owner.ownerGeneration, false));
        rejects("unknown-deployment-blocks-same-owner", "OWNER_STATE_UNKNOWN", () -> contract.acquireOwner(a.processGeneration, "projectA"));
        rejects("unknown-deployment-blocks-new-owner", "OWNER_STATE_UNKNOWN", () -> contract.acquireOwner(a.processGeneration, "projectB"));
        rejects("unknown-lease-cannot-deploy", "OWNER_STATE_UNKNOWN",
            () -> contract.requireOwnerLease(a.processGeneration, "projectA", owner.ownerGeneration));
        contract.releaseOwner(a.processGeneration, "projectA", owner.ownerGeneration, true);
        Lease next = contract.acquireOwner(a.processGeneration, "projectB");
        check("confirmed-release-allocates-new-owner-generation", !owner.ownerGeneration.equals(next.ownerGeneration));
        rejects("old-owner-remains-stale", "OWNER_STALE",
            () -> contract.requireOwnerLease(a.processGeneration, "projectA", owner.ownerGeneration));
        contract.markDead(a.processGeneration);
        rejects("dead-runtime-cannot-have-owner", "SESSION_INACTIVE", () -> contract.acquireOwner(a.processGeneration, "projectB"));
    }

    private static void operations() throws Exception {
        MemoryStore store = new MemoryStore(); Clock clock = new Clock(); ExecutionContract contract = ready(store, clock);
        rejects("digest-required", "INVALID_ARGUMENT", () -> contract.accept("op", "controller", "bad"));
        Acceptance first = contract.accept("op", "controller", HASH);
        check("accepted-is-persisted-before-return", first.created && store.rows.get("op").state == OperationState.ACCEPTED);
        check("exact-request-deduplicates", !contract.accept("op", "controller", HASH).created && store.writes == 1);
        rejects("request-digest-conflict", "OPERATION_CONFLICT", () -> contract.accept("op", "controller", OTHER_HASH));
        rejects("caller-identity-conflict", "OPERATION_CONFLICT", () -> contract.accept("op", "anotherController", HASH));
        rejects("success-requires-running", "OPERATION_NOT_RUNNING", () -> contract.succeed("op", "done"));
        contract.start("op");
        rejects("running-operation-not-restarted", "OPERATION_NOT_STARTABLE", () -> contract.start("op"));
        check("running-retry-never-replays", !contract.accept("op", "controller", HASH).created);
        clock.time = 2_000;
        Operation success = contract.succeed("op", "actual-resumed");
        check("terminal-result-persisted", success.state == OperationState.SUCCEEDED && store.rows.get("op").result.equals("actual-resumed"));
        int writes = store.writes;
        check("same-terminal-result-idempotent", contract.succeed("op", "actual-resumed") == success && store.writes == writes);
        rejects("success-cannot-change-result", "TERMINAL_IMMUTABLE", () -> contract.succeed("op", "different"));
        rejects("success-cannot-become-failure", "TERMINAL_IMMUTABLE", () -> contract.fail("op", "LATE_FAILURE"));
        rejects("success-cannot-become-unknown", "TERMINAL_IMMUTABLE", () -> contract.markUnknown("op", "late timeout"));
        check("query-does-not-write-or-execute", contract.query("op") == success && store.writes == writes);
        check("missing-query-is-empty-and-read-only", contract.query("missing") == null && store.writes == writes);
        contract.accept("unknown", "controller", HASH); contract.start("unknown");
        contract.markUnknown("unknown", "BINDER_TIMEOUT");
        rejects("unknown-never-restarts", "OPERATION_NOT_STARTABLE", () -> contract.start("unknown"));
        rejects("unknown-never-succeeds-without-coordination", "OPERATION_UNKNOWN", () -> contract.succeed("unknown", "guess"));
        rejects("unknown-never-fails-without-coordination", "OPERATION_UNKNOWN", () -> contract.fail("unknown", "GUESS"));
        rejects("reconcile-needs-evidence", "INVALID_ARGUMENT", () -> contract.reconcile("unknown", OperationState.SUCCEEDED, "observed", ""));
        rejects("reconcile-cannot-restart", "INVALID_ARGUMENT", () -> contract.reconcile("unknown", OperationState.RUNNING, "", "evidence1"));
        Operation resolved = contract.reconcile("unknown", OperationState.SUCCEEDED, "observed", "evidence1");
        check("reconcile-records-evidence-without-replay", resolved.state == OperationState.SUCCEEDED && resolved.evidence.equals("evidence1"));
        check("same-reconciliation-is-idempotent", contract.reconcile("unknown", OperationState.SUCCEEDED, "observed", "evidence1") == resolved);
        rejects("reconciliation-evidence-immutable", "RECONCILIATION_STATE",
            () -> contract.reconcile("unknown", OperationState.SUCCEEDED, "observed", "evidence2"));
        contract.accept("failure", "controller", HASH); contract.start("failure");
        Operation failed = contract.fail("failure", "TARGET_MISSING");
        check("failure-is-terminal", failed.state == OperationState.FAILED && failed.detail.equals("TARGET_MISSING"));
        check("failure-retry-is-query-only", !contract.accept("failure", "controller", HASH).created);
        contract.accept("unknownFailure", "controller", HASH); contract.markUnknown("unknownFailure", "COMMIT_UNKNOWN");
        check("unknown-reconciles-to-failure", contract.reconcile("unknownFailure", OperationState.FAILED, "NO_INSTANCE", "process-snapshot1").state == OperationState.FAILED);
        rejects("hydration-rejects-result-on-running", "INVALID_ARGUMENT",
            () -> new Operation("invalid", "controller", HASH, OperationState.RUNNING, "forged", "", 1, 2));
        rejects("hydration-rejects-negative-time", "INVALID_ARGUMENT",
            () -> new Operation("invalid", "controller", HASH, OperationState.ACCEPTED, "", "", -1, 2));
        rejects("hydration-rejects-empty-unknown-reason", "INVALID_ARGUMENT",
            () -> new Operation("invalid", "controller", HASH, OperationState.UNKNOWN, "", "", 1, 2));
        char[] oversized = new char[30_000]; java.util.Arrays.fill(oversized, '\u754c');
        rejects("result-limit-is-utf8-bytes", "INVALID_ARGUMENT",
            () -> new Operation("invalid", "controller", HASH, OperationState.SUCCEEDED, new String(oversized), "", 1, 2));
    }

    private static void recoveryAndStorageFailure() throws Exception {
        MemoryStore store = new MemoryStore(); Clock clock = new Clock(); ExecutionContract initial = ready(store, clock);
        initial.accept("accepted", "controller", HASH);
        initial.accept("running", "controller", HASH); initial.start("running");
        initial.accept("terminal", "controller", HASH); initial.start("terminal"); initial.succeed("terminal", "done");
        Session session = running(initial, "space", "dev.test.a", "install1", 100);
        check("recovery-marks-only-incomplete-unknown", initial.onRecover() == 2
            && initial.query("accepted").state == OperationState.UNKNOWN
            && initial.query("running").state == OperationState.UNKNOWN
            && initial.query("terminal").state == OperationState.SUCCEEDED);
        rejects("broker-recovery-revokes-live-session", "SESSION_INACTIVE", () -> initial.authenticate(session.processGeneration, 100, 10234));
        check("repeated-recovery-is-idempotent", initial.onRecover() == 0);
        ExecutionContract reopened = new ExecutionContract(store, clock);
        reopened.onRecover();
        check("broker-recreation-retains-terminal-result", reopened.query("terminal").result.equals("done"));
        check("broker-recreation-deduplicates-old-operation", !reopened.accept("running", "controller", HASH).created);
        rejects("broker-recreation-does-not-revive-session", "SESSION_MISSING", () -> reopened.authenticate(session.processGeneration, 100, 10234));

        MemoryStore failedStore = new MemoryStore(); ExecutionContract failed = ready(failedStore, clock);
        failedStore.failAtWrite = 1;
        rejects("failed-accepted-write-returns-no-execution-permission", "STORE_UNAVAILABLE", () -> failed.accept("failed", "controller", HASH));
        check("failed-before-commit-has-no-row", failedStore.rows.isEmpty());
        rejects("storage-failure-blocks-further-effects", "STORE_UNAVAILABLE", () -> failed.accept("second", "controller", HASH));
        failedStore.failAtWrite = -1; check("failed-store-recovery-resumes-admission", failed.onRecover() == 0 && failed.accept("second", "controller", HASH).created);

        MemoryStore ambiguousStore = new MemoryStore(); ExecutionContract ambiguous = ready(ambiguousStore, clock);
        ambiguousStore.failAtWrite = 1; ambiguousStore.throwAfterCommit = true;
        rejects("lost-commit-ack-does-not-return-execution-permission", "STORE_UNAVAILABLE", () -> ambiguous.accept("lostAck", "controller", HASH));
        check("lost-commit-ack-can-have-persisted-row", ambiguous.query("lostAck").state == OperationState.ACCEPTED);
        ambiguousStore.failAtWrite = -1;
        check("lost-commit-ack-recovers-to-unknown", ambiguous.onRecover() == 1 && ambiguous.query("lostAck").state == OperationState.UNKNOWN);
        check("lost-commit-ack-retry-never-replays", !ambiguous.accept("lostAck", "controller", HASH).created);

        MemoryStore terminalStore = new MemoryStore(); ExecutionContract terminal = ready(terminalStore, clock);
        terminal.accept("completed", "controller", HASH); terminal.start("completed");
        terminalStore.failAtWrite = terminalStore.writes + 1; terminalStore.throwAfterCommit = true;
        rejects("terminal-commit-ack-loss-is-unknown-to-caller", "STORE_UNAVAILABLE", () -> terminal.succeed("completed", "actual-effect-done"));
        terminalStore.failAtWrite = -1;
        check("recovery-preserves-committed-terminal-after-ack-loss", terminal.onRecover() == 0
            && terminal.query("completed").state == OperationState.SUCCEEDED
            && !terminal.accept("completed", "controller", HASH).created);

        MemoryStore partialStore = new MemoryStore(); ExecutionContract partial = ready(partialStore, clock);
        partial.accept("one", "controller", HASH); partial.accept("two", "controller", HASH);
        partialStore.failAtWrite = partialStore.writes + 2;
        rejects("partial-recovery-remains-closed", "STORE_UNAVAILABLE", partial::onRecover);
        rejects("incomplete-recovery-cannot-admit", "RECOVERY_REQUIRED", () -> partial.accept("three", "controller", HASH));
        partialStore.failAtWrite = -1;
        check("partial-recovery-can-finish-without-replay", partial.onRecover() == 1
            && partial.query("one").state == OperationState.UNKNOWN && partial.query("two").state == OperationState.UNKNOWN);
    }

    private static void callbacks() throws Exception {
        MemoryStore store = new MemoryStore(); Clock clock = new Clock(); ExecutionContract contract = ready(store, clock);
        Session a = running(contract, "space", "dev.test.a", "install1", 100);
        Session b = running(contract, "space", "dev.test.b", "install1", 101);
        Session outsider = running(contract, "other", "dev.test.b", "install1", 102);
        runOperation(contract, a, "roundtrip");
        Allocation cold=contract.allocate("space","dev.test.cold","cold-install","dev.test.cold",10234);
        runOperation(contract,a,"cold-callback");
        String coldCallback=contract.issueCallback(a.processGeneration,cold.session.processGeneration,"cold-callback",2_000);
        check("callback-reservation-does-not-start-target",contract.getSession(cold.session.processGeneration).state==ProcessState.ALLOCATED);
        rejects("reserved-callback-cannot-be-consumed-before-attach","SESSION_INACTIVE",()->contract.consumeCallback(coldCallback,cold.session.processGeneration,200,10234,"cold-callback"));
        rejects("reserved-callback-target-must-attach-real-uid","CALLER_MISMATCH",()->contract.attach(cold.bootstrapToken,200,10235));
        contract.attach(cold.bootstrapToken,200,10234);
        rejects("reserved-callback-target-real-pid-still-required","CALLER_MISMATCH",()->contract.consumeCallback(coldCallback,cold.session.processGeneration,201,10234,"cold-callback"));
        check("reserved-callback-consumed-by-exact-attached-target",contract.consumeCallback(coldCallback,cold.session.processGeneration,200,10234,"cold-callback").processGeneration.equals(a.processGeneration));
        rejects("reserved-callback-remains-one-use","CALLBACK_REPLAY",()->contract.consumeCallback(coldCallback,cold.session.processGeneration,200,10234,"cold-callback"));
        contract.markDead(cold.session.processGeneration);
        rejects("callback-dead-reservation-cannot-be-reissued","SESSION_INACTIVE",()->contract.issueCallback(a.processGeneration,cold.session.processGeneration,"cold-callback",2_000));
        rejects("callback-cross-space-rejected", "CALLBACK_SPACE_MISMATCH",
            () -> contract.issueCallback(a.processGeneration, outsider.processGeneration, "roundtrip", 2_000));
        rejects("callback-operation-owner-checked", "CALLBACK_CALLER_MISMATCH",
            () -> contract.issueCallback(b.processGeneration, a.processGeneration, "roundtrip", 2_000));
        rejects("callback-expiry-positive", "CALLBACK_EXPIRY",
            () -> contract.issueCallback(a.processGeneration, b.processGeneration, "roundtrip", 1_000));
        rejects("callback-expiry-bounded", "CALLBACK_EXPIRY",
            () -> contract.issueCallback(a.processGeneration, b.processGeneration, "roundtrip", 400_000));
        String callback = contract.issueCallback(a.processGeneration, b.processGeneration, "roundtrip", 2_000);
        check("callback-has-256-bit-random-token", callback.matches("[0-9a-f]{64}"));
        rejects("callback-target-generation-checked", "CALLBACK_TARGET_MISMATCH",
            () -> contract.consumeCallback(callback, a.processGeneration, 100, 10234, "roundtrip"));
        rejects("callback-operation-checked", "CALLBACK_TARGET_MISMATCH",
            () -> contract.consumeCallback(callback, b.processGeneration, 101, 10234, "anotherOp"));
        rejects("callback-real-pid-checked", "CALLER_MISMATCH",
            () -> contract.consumeCallback(callback, b.processGeneration, 999, 10234, "roundtrip"));
        check("callback-broker-chooses-source", contract.consumeCallback(callback, b.processGeneration, 101, 10234, "roundtrip").identity.key().equals(a.identity.key()));
        rejects("callback-single-use", "CALLBACK_REPLAY", () -> contract.consumeCallback(callback, b.processGeneration, 101, 10234, "roundtrip"));

        String expiring = contract.issueCallback(a.processGeneration, b.processGeneration, "roundtrip", 2_000);
        clock.time = 2_000;
        rejects("callback-rejected-at-exact-expiry", "CALLBACK_EXPIRED", () -> contract.consumeCallback(expiring, b.processGeneration, 101, 10234, "roundtrip"));
        clock.time = 1_000;
        rejects("clock-rollback-cannot-revive-expired-callback", "CALLBACK_INVALID", () -> contract.consumeCallback(expiring, b.processGeneration, 101, 10234, "roundtrip"));
        rejects("clock-rollback-cannot-extend-expiry", "CALLBACK_EXPIRY", () -> contract.issueCallback(a.processGeneration, b.processGeneration, "roundtrip", 1_500));
        clock.time = 2_000;
        String afterUnknown = contract.issueCallback(a.processGeneration, b.processGeneration, "roundtrip", 3_000);
        contract.markUnknown("roundtrip", "TIMEOUT");
        rejects("unknown-operation-cannot-accept-callback", "CALLBACK_OPERATION_STATE",
            () -> contract.consumeCallback(afterUnknown, b.processGeneration, 101, 10234, "roundtrip"));

        runOperation(contract, a, "death");
        String death = contract.issueCallback(a.processGeneration, b.processGeneration, "death", 3_000);
        contract.markDead(b.processGeneration);
        Session newB = running(contract, "space", "dev.test.b", "install2", 101);
        rejects("target-death-and-pid-reuse-revoke-callback", "CALLBACK_INVALID",
            () -> contract.consumeCallback(death, newB.processGeneration, 101, 10234, "death"));
        String sourceDeath = contract.issueCallback(a.processGeneration, newB.processGeneration, "death", 3_000);
        contract.invalidateInstall("space", "dev.test.a", "install1");
        rejects("source-install-replacement-revokes-callback", "CALLBACK_INVALID",
            () -> contract.consumeCallback(sourceDeath, newB.processGeneration, 101, 10234, "death"));
    }

    private static void concurrency() throws Exception {
        MemoryStore store = new MemoryStore(); ExecutionContract contract = ready(store, new Clock());
        int count = 16; CountDownLatch ready = new CountDownLatch(count), go = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try { go.await(); if (contract.accept("same", "controller", HASH).created) admitted.incrementAndGet(); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
            }); workers.add(worker); worker.start();
        }
        ready.await(); go.countDown(); for (Thread worker : workers) worker.join();
        if (failure.get() != null) throw new AssertionError("concurrent admission", failure.get());
        check("concurrent-retries-admit-one-side-effect", admitted.get() == 1 && store.writes == 1);
        AtomicInteger starts = new AtomicInteger(); workers.clear();
        for (int i = 0; i < count; i++) {
            Thread worker = new Thread(() -> {
                try { contract.start("same"); starts.incrementAndGet(); }
                catch (ContractException expected) { if (!expected.code.equals("OPERATION_NOT_STARTABLE")) failure.compareAndSet(null, expected); }
            }); workers.add(worker); worker.start();
        }
        for (Thread worker : workers) worker.join();
        if (failure.get() != null) throw new AssertionError("concurrent start", failure.get());
        check("concurrent-starts-have-one-writer", starts.get() == 1 && store.writes == 2);
    }

    public static void main(String[] args) throws Exception {
        identities(); owners(); operations(); recoveryAndStorageFailure(); callbacks(); concurrency();
        MemoryStore admissionStore=new MemoryStore();ExecutionContract admission=ready(admissionStore,new Clock());admissionStore.rejectAdmission=true;
        rejects("uncertain-scope-rejects-before-admission","RECOVERY_SCOPE_UNKNOWN",()->admission.accept("blocked-effect","controller",HASH));
        check("rejected-scope-has-no-operation",admission.query("blocked-effect")==null);
        admissionStore.rejectAdmission=false;check("scope-rejection-does-not-poison-journal",admission.accept("unrelated-effect","controller",HASH).created);
        StringBuilder output = new StringBuilder("{\"suite\":\"execution-contract-host\",\"evidenceKind\":\"host-javac\",\"passed\":true,\"checks\":");
        output.append(checks.size()).append(",\"androidDeviceVerified\":false,\"securityIsolationVerified\":false,\"names\":[");
        for (int i = 0; i < checks.size(); i++) { if (i > 0) output.append(','); output.append('"').append(checks.get(i)).append('"'); }
        System.out.println(output.append("]}"));
    }
}
