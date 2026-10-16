#!/usr/bin/env python3
"""Run the built ordinary-UID runtime acceptance app, collect bound evidence, and reject incomplete results.
Only owned test packages are used; notification phase can explicitly grant its own temporary permission.
"""
import argparse
import base64
import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
from runtime_crash_evidence import java_fatal_evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--install", action="store_true", help="Install the two owned test packages; phone confirmation may be required")
    parser.add_argument("--target-package", choices=["com.tencent.mm", "com.tencent.tmgp.sgame"], help="Observe one explicitly authorized target app")
    parser.add_argument("--target-space", help="Reuse an existing target installation after exact APK verification; preserve its data and history")
    parser.add_argument("--fixture-close-plan", type=Path, help="Exact two already-dead owned fixture generations for process reuse acceptance")
    parser.add_argument("--phase", choices=["p0", "p1", "p2", "p3", "p4", "artifact", "target", "receiver", "providers", "provider-identity", "entry", "failure", "packages", "notification", "creation", "service-startup", "service-passive", "activity-flags", "sources", "jobs", "jobs-native", "framework-gc", "process-reuse"], default="p0")
    parser.add_argument("--allow-notification-test-grant", action="store_true", help="Temporarily grant POST_NOTIFICATIONS only to the owned runtime-probe for notification phase")
    args = parser.parse_args()
    if bool(args.fixture_close_plan) != (args.phase == "process-reuse"):
        parser.error("--fixture-close-plan is required only for --phase process-reuse")
    if args.target_package and args.phase != "target":
        parser.error("--target-package requires --phase target")
    if args.target_space and not args.target_package:
        parser.error("--target-space requires --target-package")
    if args.allow_notification_test_grant and args.phase != "notification":
        parser.error("Notification permission grant is only valid for notification phase")
    root = Path(__file__).resolve().parents[2]
    build = root / "android/runtime-probe/build"
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    receipt = json.loads((build / "build-receipt.json").read_text())
    for relative, digest in receipt["sourceSha256"].items():
        if hashlib.sha256((root / relative).read_bytes()).hexdigest() != digest:
            raise SystemExit("Source changed since build: " + relative)
    if hashlib.sha256((build / "runtime-probe.apk").read_bytes()).hexdigest() != receipt["hostApkSha256"]:
        raise SystemExit("Build artifact checksum mismatch")
    commands = []

    def adb(*command, check=True, timeout=60):
        result = subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, text=True, timeout=timeout)
        if command[:2] != ("shell", "cat"):
            commands.append({"command": list(command), "exitCode": result.returncode, "stdout": "[permission state parsed below]" if command[:3] == ("shell", "dumpsys", "package") else result.stdout, "stderr": result.stderr})
        if check and result.returncode:
            raise RuntimeError("ADB failed: " + " ".join(command) + "\n" + result.stdout + result.stderr)
        return result

    def notification_permission():
        dump = adb("shell", "dumpsys", "package", "dev.tripoder.runtimeprobe").stdout
        section = re.search(r"^    User 0:.*?(?=^    User \d+:|^\S|\Z)", dump, re.MULTILINE | re.DOTALL)
        if not section or "installed=true" not in section.group().splitlines()[0]:
            raise RuntimeError("Owned notification permission user state unknown")
        values = re.findall(r"^\s+android\.permission\.POST_NOTIFICATIONS: granted=(true|false),", section.group(), re.MULTILINE)
        if len(values) > 1:
            raise RuntimeError("Owned notification permission state ambiguous")
        # Android's per-user grant record defaults to denied when no entry exists.
        return "granted" if values == ["true"] else "denied"

    run_id = args.phase + "-" + uuid.uuid4().hex[:16]
    observation = {"runId": run_id, "observedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "rootUsed": False,
                   "permissionGrantsPerformed": False, "productApkReplaced": False, "commands": commands}
    result = None
    owned_uid = None
    device_log_start = None
    notification_granted = False
    live_log = None
    live_log_file = None

    def collect_owned_logs():
        if owned_uid is None or device_log_start is None:
            return
        # debuggerd uses a reporter PID, not the crashed worker's PID. UID and
        # this run's device-clock start retain its report without other apps' logs.
        crash = adb("logcat", "-b", "crash", "-d", "--uid=" + str(owned_uid), "-T", device_log_start, check=False)
        if crash.returncode:
            raise RuntimeError("Owned crash evidence unavailable: " + crash.stderr)
        (evidence / "owned-crash.txt").write_text(crash.stdout)
        crash_text = crash.stdout
        if args.phase == "target" and (evidence / "owned-live-log.txt").exists():
            crash_text += "\n" + (evidence / "owned-live-log.txt").read_text()
        observation["nativeMemoryCrashObserved"] = bool(re.search(r"Fatal signal (?:11|7) ", crash_text))
        observation["nativeFatalSignalObserved"] = bool(re.search(r"Fatal signal \d+ ", crash_text))
        observation.update(java_fatal_evidence(crash_text, result, args.phase))
        art = adb("logcat", "-b", "main", "-d", "--uid=" + str(owned_uid), "-T", device_log_start, "TripoderART:I", "*:S", check=False)
        if art.returncode:
            raise RuntimeError("ART evidence unavailable: " + art.stderr)
        (evidence / "art-method-layout.txt").write_text(art.stdout)
        hooks = adb("logcat", "-b", "main", "-d", "--uid=" + str(owned_uid), "-T", device_log_start, "TripoderHook:I", "*:S", check=False)
        if hooks.returncode:
            raise RuntimeError("Hook evidence unavailable: " + hooks.stderr)
        (evidence / "hook-lifecycle.txt").write_text(hooks.stdout)
        receivers = adb("logcat", "-b", "main", "-d", "--uid=" + str(owned_uid), "-T", device_log_start, "TripoderReceiver:I", "*:S", check=False)
        if receivers.returncode:
            raise RuntimeError("Receiver ingress evidence unavailable: " + receivers.stderr)
        (evidence / "receiver-ingress.txt").write_text(receivers.stdout)
        bootstrap = adb("logcat", "-b", "main", "-d", "--uid=" + str(owned_uid), "-T", device_log_start,
                        "TripoderSelfHook:I", "TripoderRuntime:E", "TripoderOwnership:I", "TripoderJobs:I", "*:S", check=False)
        if bootstrap.returncode:
            raise RuntimeError("Self Hook startup evidence unavailable: " + bootstrap.stderr)
        (evidence / "bootstrap-log.txt").write_text(bootstrap.stdout)
        observation["selfHookInstallationSamples"] = [
            {"pid": int(pid), "bindings": int(bindings), "durationNs": int(duration)}
            for pid, bindings, duration in re.findall(
                r"^\S+\s+\S+\s+(\d+)\s+\d+\s+I\s+TripoderSelfHook\s*:\s+READY:READY:26/26:bindings=(\d+):ns=(\d+)$",
                bootstrap.stdout, re.MULTILINE)]
        if result is not None and args.phase == "p4" and result.get("expectedHookTerminations"):
            def exact_exit(item):
                expected={"HOOK_TYPE":(2,9),"HOOK_ACTIVE_MODULE_UNLOADED":(1,198)}.get(item.get("reason"))
                exit=item.get("exitInfo",{})
                return (expected is not None and (exit.get("reason"),exit.get("status"))==expected
                        and exit.get("pid")==item.get("pid") and exit.get("uid")==owned_uid
                        and isinstance(item.get("startedAt"),int) and exit.get("timestamp",0)>=item["startedAt"]
                        and re.fullmatch(r"dev\.tripoder\.runtimeprobe:slot[0-7]",exit.get("process","")) is not None)
            observation["expectedHookTerminationsConfirmed"] = all(exact_exit(item) for item in result["expectedHookTerminations"])
    try:
        if adb("get-state").stdout.strip() != "device":
            raise RuntimeError("Device not ready")
        if args.install:
            adb("shell", "am", "force-stop", "dev.tripoder.runtimeprobe")
            for apk in ["b.apk", "runtime-probe.apk"]:
                adb("install", "--no-incremental", "-r", str(build / apk), timeout=90)
        if args.phase == "notification":
            permission = notification_permission()
            if permission not in ["granted", "denied"]:
                raise RuntimeError("Notification permission state unknown: " + permission)
            observation["notificationPermissionBefore"] = permission
            if permission == "denied":
                if not args.allow_notification_test_grant:
                    raise RuntimeError("Explicit --allow-notification-test-grant required for real posted-record test")
                adb("shell", "pm", "grant", "dev.tripoder.runtimeprobe", "android.permission.POST_NOTIFICATIONS")
                notification_granted = True
                observation["permissionGrantsPerformed"] = True
        marker = "/sdcard/Android/data/dev.tripoder.p0.b/files/decoy-launch.txt"
        marker_before = adb("shell", "test", "-e", marker, check=False)
        if marker_before.returncode != 1 or marker_before.stderr:
            raise RuntimeError("Decoy marker already present or inaccessible; do not delete contradictory evidence")
        path = adb("shell", "pm", "path", "dev.tripoder.p0.b").stdout.strip().removeprefix("package:")
        if not re.fullmatch(r"/data/app/[a-zA-Z0-9_~/=+.-]+/base\.apk", path):
            raise RuntimeError("Unexpected decoy APK path")
        observation["decoyApkSha256"] = adb("shell", "sha256sum", path).stdout.split()[0]
        if observation["decoyApkSha256"] != receipt["fixtures"]["b"]["sha256"]:
            raise RuntimeError("Installed decoy does not match this build's fixture B")
        observation["decoyMarkerBefore"] = False
        observation["pageSize"] = int(adb("shell", "getconf", "PAGESIZE").stdout.strip())
        installed_a = adb("shell", "pm", "path", "dev.tripoder.p0.a", check=False)
        if installed_a.stdout.strip() or installed_a.stderr.strip():
            raise RuntimeError("A must not be installed in Android system")
        observation["aInstalledInHost"] = False
        adb("shell", "am", "force-stop", "dev.tripoder.runtimeprobe")
        packages = adb("shell", "cmd", "package", "list", "packages", "-U", "dev.tripoder.runtimeprobe").stdout
        match = re.fullmatch(r"package:dev\.tripoder\.runtimeprobe uid:(\d+)\s*", packages)
        if match is None:
            raise RuntimeError("Owned UID could not be resolved")
        owned_uid = int(match[1])
        device_log_start = adb("shell", 'date "+%m-%d %H:%M:%S.%3N"').stdout.strip()
        if not re.fullmatch(r"\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}", device_log_start):
            raise RuntimeError("Device evidence clock unavailable")
        observation["ownedUid"] = owned_uid
        observation["deviceLogStart"] = device_log_start
        if args.phase == "target":
            live_log_file = (evidence / "owned-live-log.txt").open("w")
            live_log = subprocess.Popen(["adb", "-s", args.serial, "logcat", "-b", "main", "-b", "system", "-b", "crash", "--uid=" + str(owned_uid), "-T", device_log_start], stdout=live_log_file, stderr=subprocess.STDOUT)
        activity = {"p0": "ProbeActivity", "p1": "P1ProbeActivity", "p2": "P2ProbeActivity", "p3": "P3ProbeActivity", "p4": "P4ProbeActivity", "artifact": "ArtifactProbeActivity", "target": "ArtifactProbeActivity", "receiver": "P2ProbeActivity", "providers": "P2ProbeActivity", "provider-identity": "P2ProbeActivity", "entry": "P2ProbeActivity", "failure": "P2ProbeActivity", "packages": "P2ProbeActivity", "notification": "P2ProbeActivity", "creation": "P2ProbeActivity", "service-startup": "P2ProbeActivity", "service-passive": "P2ProbeActivity", "activity-flags": "P2ProbeActivity", "sources": "P2ProbeActivity", "jobs": "P2ProbeActivity", "jobs-native": "NativeRetryActivity", "framework-gc": "FrameworkGcActivity", "process-reuse": "P2ProbeActivity"}[args.phase]
        start_flags = ["-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", "0x10008000"] if args.phase in ["p2", "p4", "receiver", "providers", "provider-identity", "entry", "failure", "packages", "notification", "creation", "service-startup", "service-passive", "activity-flags", "sources", "jobs"] else []
        scenario = ["--ez", "receiverOnly", "true"] if args.phase == "receiver" else ["--ez", "providersOnly", "true"] if args.phase == "providers" else ["--ez", "providerIdentityOnly", "true"] if args.phase == "provider-identity" else ["--ez", "entryOnly", "true"] if args.phase == "entry" else ["--ez", "failureOnly", "true"] if args.phase == "failure" else ["--ez", "packageOnly", "true"] if args.phase == "packages" else ["--ez", "notificationOnly", "true"] if args.phase == "notification" else ["--ez", "creationOnly", "true"] if args.phase == "creation" else ["--ez", "serviceStartupOnly", "true"] if args.phase == "service-startup" else ["--ez", "servicePassiveOnly", "true"] if args.phase == "service-passive" else ["--ez", "activityFlagsOnly", "true"] if args.phase == "activity-flags" else ["--ez", "sourcesOnly", "true"] if args.phase == "sources" else ["--ez", "jobsOnly", "true"] if args.phase == "jobs" else ["--ez", "launchTargets", "true"] if args.phase == "target" else []
        if args.phase == "process-reuse":
            start_flags = ["-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", "0x10008000"]
            plan_data = json.dumps(json.loads(args.fixture_close_plan.read_text()), separators=(",", ":")).encode()
            scenario = ["--ez", "processReuseOnly", "true", "--es", "closurePlan", base64.urlsafe_b64encode(plan_data).decode()]
        if args.target_package:
            scenario += ["--es", "targetPackage", args.target_package]
        if args.target_space:
            scenario += ["--es", "targetSpace", args.target_space]
        adb("shell", "am", "start", "-W", *start_flags, "-n", "dev.tripoder.runtimeprobe/." + activity, "--es", "runId", run_id, *scenario)
        remote = "/sdcard/Android/data/dev.tripoder.runtimeprobe/files/result-" + run_id + ".json"
        deadline = time.monotonic() + (300 if args.phase in ["jobs", "jobs-native"] else 1200 if args.phase == "p2" else 600 if args.phase in ["p1", "p2", "p3", "p4", "artifact", "target", "receiver", "providers", "provider-identity", "process-reuse"] else 150)
        result = None
        resumed_task = False
        while time.monotonic() < deadline:
            fetched = adb("shell", "cat", remote, check=False, timeout=10)
            if fetched.returncode != 0 and "No such file or directory" not in fetched.stderr:
                raise RuntimeError("Report is not readable: " + fetched.stderr.strip())
            if fetched.returncode == 0:
                result = json.loads(fetched.stdout)
                (evidence / "device-result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))
                break
            if args.phase == "p2" and not resumed_task:
                handoff = adb("shell", "cat", "/sdcard/Android/data/dev.tripoder.runtimeprobe/files/resume-task-" + run_id + ".json", check=False, timeout=10)
                if handoff.returncode == 0:
                    request = json.loads(handoff.stdout)
                    if request.get("runId") != run_id or request.get("action") != "launcher-resume-owned-task" or not isinstance(request.get("taskId"), int) or request["taskId"] <= 0:
                        raise RuntimeError("Invalid owned-task handoff")
                    # External test stimulus equivalent to launching the retained app task.
                    # No grant, shell dependency, or background-launch bypass in runtime code.
                    resumed_task = True
                    observation["coldTaskHandoff"] = request
                    adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", "dev.tripoder.runtimeprobe/.P2ProbeActivity", "--es", "runId", run_id)
            time.sleep(1)
        if result is None:
            raise RuntimeError("No terminal result; device validation is UNKNOWN")
        after = adb("shell", "test", "-e", marker, check=False)
        observation["decoyMarkerAfter"] = after.returncode == 0
        observation["hostMatchesBuild"] = result.get("hostApkSha256") == receipt["hostApkSha256"]
        observation["fixturesMatchBuild"] = result.get("fixtures") == receipt["fixtures"]
        observation["p1FixturesMatchBuild"] = args.phase != "p1" or result.get("p1Fixtures") == receipt["p1Fixtures"]
        observation["p2FixturesMatchBuild"] = args.phase not in ["p2", "p3", "p4", "receiver", "providers", "provider-identity", "entry", "failure", "packages", "notification", "creation", "service-startup", "service-passive", "activity-flags", "sources", "jobs", "process-reuse"] or result.get("p2Fixtures") == receipt["p2Fixtures"]
        observation["allDeviceAssertionsPassed"] = bool(result.get("checks")) and all(c.get("passed") is True for c in result["checks"])
        collect_owned_logs()
        observation["passed"] = result.get("passed") is True and result.get("runId") == run_id and observation["hostMatchesBuild"] and observation["fixturesMatchBuild"] and observation["p1FixturesMatchBuild"] and observation["p2FixturesMatchBuild"] and observation["allDeviceAssertionsPassed"] and after.returncode == 1 and not after.stderr
        if not observation["passed"]:
            raise RuntimeError("Device assertions failed; inspect device-result.json")
        if observation["nativeMemoryCrashObserved"]:
            raise RuntimeError("Native memory crash observed during this run")
        if result.get("unexpectedAnrObserved") or (args.phase=="jobs" and result.get("unexpectedAnrObserved") is not False):
            raise RuntimeError("ANR observed or required ANR evidence missing")
        if observation["unexpectedJavaFatalObserved"]:
            raise RuntimeError("Java fatal exception observed during this run")
        if args.phase == "p4" and (observation["nativeFatalSignalObserved"] or observation.get("expectedHookTerminationsConfirmed") is not True):
            raise RuntimeError("P4 termination evidence missing or unexpected Native fatal signal")
    except Exception as error:
        observation["passed"] = False
        observation["error"] = str(error)
        raise
    finally:
        if live_log is not None:
            live_log.terminate()
            try:
                live_log.wait(timeout=5)
            except subprocess.TimeoutExpired:
                live_log.kill()
                live_log.wait(timeout=5)
            live_log_file.close()
        if not observation.get("passed"):
            try:
                collect_owned_logs()
                progress = adb("shell", "cat", "/sdcard/Android/data/dev.tripoder.runtimeprobe/files/progress-" + run_id + ".json", check=False)
                if progress.returncode == 0:
                    (evidence / "progress.json").write_text(progress.stdout)
            except Exception as error:
                observation["logCollectionError"] = str(error)
        if notification_granted:
            try:
                adb("shell", "pm", "revoke", "dev.tripoder.runtimeprobe", "android.permission.POST_NOTIFICATIONS")
                observation["notificationPermissionRestored"] = notification_permission() == "denied"
                if not observation["notificationPermissionRestored"]:
                    observation["passed"] = False
                    observation["permissionRestoreError"] = "Notification permission restoration unconfirmed"
            except Exception as error:
                observation["passed"] = False
                observation["permissionRestoreError"] = str(error)
        if args.phase in ["jobs", "jobs-native"]:
            samples = adb("shell", "cat", "/sdcard/Android/data/dev.tripoder.runtimeprobe/files/job-system-" + run_id + ".json", check=False)
            if samples.returncode == 0:
                (evidence / "job-system-samples.json").write_text(samples.stdout)
        shutil.copyfile(build / "build-receipt.json", evidence / "build-receipt.json")
        observation["finishedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (evidence / "device-observation.json").write_text(json.dumps(observation, indent=2, ensure_ascii=False))
    if not observation.get("passed"):
        raise RuntimeError("Validation or permission restoration failed; inspect device-observation.json")
    print(json.dumps({"passed": True, "checks": len(result["checks"]), "runId": run_id, "hostSha256": receipt["hostApkSha256"], "evidence": str(evidence)}), flush=True)


if __name__ == "__main__":
    main()
