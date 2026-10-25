# BROKER_RECOVERY launch fence — fix + evidence (2026-10-25)

**待协调 (coordinate before merging).** The recovery-proof subsystem
(`RuntimeEffectRecoveryProof.java`, `RuntimeStoppedEffectProof.java`,
`RuntimeUnstartedEffectProof.java`, plus modifications to `ExecutionContract.java`,
`RuntimeMutationGuard.java`, `ActivityLaunchLedger.java`, `EngineRecoveryService.java`)
is another session's **uncommitted work in progress**. This commit does **not** touch
those source files — it carries the change as `fix.patch` so the owner can apply it on
top of their version when they commit the subsystem.

## Symptom

A guest whose launch was in flight when the engine authority restarted
(e.g. repeated workbench "重新连接" / `am instrument` process restarts) gets its launch
operation marked `UNKNOWN` with `detail='BROKER_RECOVERY'` (ExecutionContract writes this
on single-authority recovery). Every later `prepareApplicationLaunch` for that package is
then rejected with `RECOVERY_SCOPE_UNKNOWN`
(logcat `TripoderRecovery admission-blocked … detail=BROKER_RECOVERY`), and force-stop +
relaunch — the designed recovery — does not clear it.

## Root cause

`RuntimeEffectRecoveryProof.inert` discharges a launch operation (no `activity_launches`
row, launch-command scope, and either a confirmed explicit stop or an all-dead session set)
only when `detail='LAUNCH_PREPARATION_UNCONFIRMED'` (the guest-crash-during-prep case).
A broker-recovered launch carries `detail='BROKER_RECOVERY'` instead, which no proof
covered — so it fenced the scope permanently.

## Fix (`fix.patch`)

Broaden the launch-inert clause to
`original.detail IN ('LAUNCH_PREPARATION_UNCONFIRMED','BROKER_RECOVERY')`. The existing
guards make it sound and well-scoped:
- `NOT EXISTS(activity_launches for op)` — the launch never crossed into a delivered Activity.
- `own.command IN (prepareApplicationLaunch|prepareLaunch|agentPrepareLaunch)` — only
  launch-command scopes are discharged; a `BROKER_RECOVERY` install/stop stays fenced.
- the `live` guard (no live session in scope) + a confirmed dead session / explicit stop —
  proves no session of it still runs.

For a launch op, a dead session = dead process = inert. (Internal `legacy-or-internal` ops
are deliberately NOT covered by this clause — they are handled by
`RuntimeUnstartedEffectProof`, which requires `NOT_DISPATCHED`/ACK, because an internal
effect can outlive any session. A `MAY_HAVE_DISPATCHED` internal op is correctly left fenced.)

## Verification

1. **SQL behavioral test** — `verify_recovery_proof.sh` runs the old vs. new discharge query
   against a real `sqlite3` reproducing the wedged journal. **9/9 pass**: old query fences,
   new query discharges; non-launch `BROKER_RECOVERY`, live-session, existing-activity-entry,
   and no-evidence cases all stay fenced; explicit-stop discharge works; and the original
   `LAUNCH_PREPARATION_UNCONFIRMED` case is unregressed.
2. **Full engine build** — `build-workbench.py` (`dev.tripoder.engine` and
   `dev.tripoder.compatbochk`) compiles + packages cleanly with the change.
3. **On device (d861a4fa)** — the patched `compatbochk` build was installed over the wedged
   instance (journal preserved via `-r`, signer cert `2e03e499…` matched). This confirmed the
   fix is in the live dex. NOTE: the specific live BOCHK wedge turned out to be a *different*
   class — a `legacy-or-internal` / `MAY_HAVE_DISPATCHED` op (`a0fd0751`), not a launch op —
   so this fix correctly did not clear it; that op is not safely auto-dischargeable. After
   `pm clear` + re-adding BOCHK fresh, BOCHK launched end-to-end: real `MainActivity` rendered
   its genuine splash inside Tripoder (engine side fully unblocked — fence gone,
   `HIDE_OVERLAY_WINDOWS` passes), then self-terminated ~11s later on its own anti-tamper
   check (outside the engine).

## Related, separate (not in this change)

- `HIDE_OVERLAY_WINDOWS`: BOCHK's `MainActivity.onCreate` calls `setHideOverlayWindows`, which
  the system enforces against the engine UID; the engine manifest must declare the permission
  (that change is in the other session's working tree, already installed + granted).
- BOCHK's own ~11s anti-tamper self-kill is an app-side check, not an engine issue.
