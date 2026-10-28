# Tencent video diagnostic run 2 (short QA host dev.tripoder.cmbq)

- Launch receipt: SUCCEEDED, workerPid 19284, MAY_HAVE_DISPATCHED.
- Harness result: DeadObjectException before the first recorded check (no checks recorded).
- Device logcat (native-abort-logcat-excerpt.txt): the guest worker aborted with SIGABRT in thread "Idle Thread-762";
  preceding libc lines report `shadow stack read-write mprotect(...) failed: Out of memory` and `mprotect(...) failed: Out of memory`.
- Interpretation: a guest-side native abort while mapping memory for a very large thread count (~762 idle threads).
  This is not yet proven to be the cause of run 1 (`SecurityException` from the Toast accessibility path), which had no native abort.
