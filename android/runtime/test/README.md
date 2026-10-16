# Execution contract host verification

The production class is `../src/dev/tripoder/runtime/ExecutionContract.java`.
The memory journal exists only in this test source; an Android caller must provide
a durable `ExecutionContract.Store` and successfully call `onRecover()` before
allocating sessions or accepting operations.

Run from the repository root:

```sh
rtk proxy mkdir -p /private/tmp/tripoder-execution-contract-test/classes
rtk proxy javac --release 8 -Xlint:all,-options \
  -d /private/tmp/tripoder-execution-contract-test/classes \
  android/runtime/src/dev/tripoder/runtime/ExecutionContract.java \
  android/runtime/test/dev/tripoder/runtime/ExecutionContractTest.java
rtk proxy java -cp /private/tmp/tripoder-execution-contract-test/classes \
  dev.tripoder.runtime.ExecutionContractTest > android/runtime/test/host-result.json
```

`host-result.json` is the actual latest test output. It covers identity binding,
PID reuse, control ownership, operation deduplication, unknown outcomes, durable
journal recovery and injected write failures, evidence-based reconciliation,
one-use callbacks and simultaneous retry admission. These are host JVM checks;
they do not verify Android Binder capture, Android SQLite persistence, component
launch, Native I/O interception, device execution, or hostile-code isolation.

Integration rules:

- Capture real PID/UID at the Binder entry point and authenticate before dispatch.
- Derive `callerIdentity` in the Broker. A guest must not choose its own identity.
- `Acceptance.created` is admission for the first attempt, not success. Persist
  `start()` before performing the external action, then persist the observed result.
- A Store exception closes mutation admission until recovery. A lost commit ACK
  can mean the row was written; retries must query/recover, never blindly replay.
- Store `Operation.evidence` and hydrate it with the nine-argument constructor.
- `Store.incomplete()` returns only ACCEPTED/RUNNING rows. UNKNOWN is retained;
  `reconcile()` requires concrete evidence and never performs the original action.
- Session ownership and callback capabilities end with the Broker process. A
  process-generation value from an old Broker never reattaches after recovery.
- Callback operation ownership uses the source session's `identity.key()`.
- Terminal operation rows are immutable; a duplicate terminal response is only
  accepted when its existing value matches exactly.
