#!/usr/bin/env python3
"""Compile and run independent path-policy C tests as Android shell, not inside a guest.
This is kernel-semantics evidence only. It never claims the libc adapter is installed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--ndk", required=True, type=Path)
    p.add_argument("--evidence", required=True, type=Path)
    p.add_argument("--direct-syscalls", action="store_true", help="Exercise the direct SVC namespace, including ABI and signal chaining")
    args = p.parse_args()
    repo = Path(__file__).resolve().parents[2]
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    binary = evidence / "path-policy-probe"
    sources = ["android/runtime/native/path_policy.c", "android/runtime/native/io_adapter.c", "android/runtime/native/user_memory.c", "android/runtime/test/native/path_policy_probe.c"]
    if args.direct_syscalls:
        sources[-1]="android/runtime/test/native/syscall_namespace_probe.c"
        sources.append("android/runtime/native/syscall_namespace.c")
        sources.append("android/runtime/native/kernel_syscall_a64.S")
    else:
        sources.append("android/runtime/native/kernel_syscall_a64.S")
    compiler = args.ndk.resolve() / "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android36-clang"
    compile_command = [str(compiler), "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "-Iandroid/runtime/native", *sources, "-o", str(binary)]
    subprocess.run(compile_command, cwd=repo, check=True)
    # Executables stay in ignored build output; evidence retains the exact hash and command.
    build_binary = repo / ("android/runtime-probe/build/native/syscall-namespace-probe-validated" if args.direct_syscalls else "android/runtime-probe/build/native/path-policy-probe-validated")
    binary.replace(build_binary)
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    run = uuid.uuid4().hex
    remote = "/data/local/tmp/tripoder-policy-" + run
    scratch = ("/data/local/tmp/tripoder-syscall-" if args.direct_syscalls else "/data/local/tmp/tripoder-path-") + run
    commands = []
    result = {"runId": run, "serial": args.serial, "hookInstalled": False, "guestExecutionVerified": False, "strongIsolationVerified": False,
              "directSyscalls":args.direct_syscalls,
              "compile": compile_command, "binarySha256": digest(build_binary), "sourceSha256": {name: digest(repo / name) for name in [*sources, "android/runtime/native/path_policy.h", "android/scripts/validate-native-policy.py"]}, "commands": commands}

    def adb(*command):
        value = subprocess.run(["adb", "-s", args.serial, *command], text=True, capture_output=True, timeout=30)
        commands.append({"command": list(command), "exitCode": value.returncode, "stdout": value.stdout, "stderr": value.stderr})
        return value

    try:
        result["fingerprint"] = adb("shell", "getprop", "ro.build.fingerprint").stdout.strip()
        result["executionUid"] = adb("shell", "id", "-u").stdout.strip()
        for command in [("push", str(build_binary), remote), ("shell", "chmod", "700", remote)]:
            if adb(*command).returncode:
                raise RuntimeError("Owned policy probe deployment failed")
        observed = adb("shell", remote, scratch)
        rows = [json.loads(line) for line in observed.stdout.splitlines()]
        result["observations"] = rows
        result["passed"] = observed.returncode == 0 and len(rows) > 1 and rows[-1].get("summary") is True and all(row.get("passed") is True for row in rows)
        if not result["passed"]:
            raise RuntimeError("Native path-policy assertions failed")
        print(json.dumps({"passed": True, "checks": rows[-1]["checks"], "runId": run, "hookInstalled": False, "evidence": str(evidence)}), flush=True)
    except Exception as error:
        result["passed"] = False
        result["error"] = str(error)
        raise
    finally:
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
