#!/usr/bin/env python3
"""Full-device Tripoder guest campaign: inventory -> import -> launch compatibility.

Design notes
------------
* Every phone mutation goes through the engine's own MCP contract; adb is used only
  for read-only observation (logcat, dumpsys, screencap) and for the OS package list.
* The campaign is resumable: every per-application decision is appended to
  `journal.jsonl` and re-read on start, so a transport drop never loses work.
* A launch is judged by four independent signals, never by one:
    1. `process_start` accepted and returned a pid/processGeneration
    2. the guest process is still enumerated by `application_query targets`
    3. `mCurrentFocus` belongs to the guest package (its own Activity rendered)
    4. no crash/ANR block for that pid in the logcat crash+main buffers
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "tools"))
from tpmcp import _Session, McpError  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "artifacts")
SKIP_PREFIXES = ("dev.tripoder.",)
# A single UNKNOWN launch fences its whole recovery scope (`space/<id>`), and no MCP
# command can turn such an operation terminal again. One dedicated space per package
# therefore keeps a wedged app from blinding the rest of the matrix; `--space` pins a
# shared space instead when a realistic multi-app container is what we want to measure.
SPACE = ""
SHARED_SPACE = False
# Runtime instrumentation mode exercised by the launch phase. Empty keeps whatever the
# installation already uses (the campaign never switched it before this option existed);
# `--mode thick` switches a stopped installation, launches, judges and restores the
# previous mode so one device state can be compared under both models.
MODE = ""
RESTORE_MODE = True
# The engine leases one of eight activity-affinity slots per (space, guest, affinity) and only
# returns a lease when the space that owns the component row is deleted. A 100+ app campaign
# therefore exhausts the pool and every later launch is refused with
# ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED. `--recycle` deletes each judged space before the next
# launch, so the pool never holds more than the app currently being measured.
RECYCLE = False
# A resumed run normally treats a recorded import failure as final. That is wrong for the two
# states that describe the harness rather than the guest (the engine's recovery scope was
# fenced, or the import transport failed), because retrying those is exactly how a wedged
# campaign makes progress. `--retry-failed` re-imports them instead of skipping.
RETRY_FAILED = False

# High-value apps first, so a truncated run still answers the questions people care about.
PRIORITY = (
    "com.tencent.mm", "com.eg.android.AlipayGphone", "com.taobao.taobao", "com.ss.android.ugc.aweme",
    "com.xingin.xhs", "com.sankuai.meituan", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
    "com.android.chrome", "com.google.android.youtube", "org.telegram.messenger", "com.openai.chatgpt",
    "com.anthropic.claude", "com.deepseek.chat", "com.alibaba.android.rimet", "com.autonavi.minimap",
    "com.sdu.didi.psnger", "com.baidu.BaiduMap", "com.sina.weibo", "com.zhihu.android",
    "com.ss.android.article.news", "tv.danmaku.bili", "com.qiyi.video", "com.tencent.qqlive",
    "com.netease.cloudmusic", "com.luna.music", "com.xs.fm", "com.dragon.read", "com.duokan.reader",
    "com.smile.gifmaker", "com.tencent.mobileqq", "com.instagram.android", "com.twitter.android",
    "com.github.android", "com.x8bit.bitwarden", "org.wikipedia", "org.videolan.vlc",
    "com.microsoft.office.officehubrow", "cn.wps.moffice_eng", "com.booking", "com.duolingo",
)


def adb(args, timeout=90):
    # logcat can contain non-UTF-8 bytes (OEM logs, app payloads); never let a
    # decoding error abort an entire campaign.
    result = subprocess.run(["adb", "-s", "d861a4fa", *args], capture_output=True, text=True,
                            timeout=timeout, errors="replace")
    return result.stdout


def slug(package):
    return re.sub(r"[^A-Za-z0-9]+", "-", package).strip("-")[:40]


def space_for(package):
    return SPACE if SHARED_SPACE else ("qa-" + slug(package).lower())


class Journal:
    def __init__(self, path):
        self.path = path
        self.entries = []
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as handle:
                for line in handle:
                    line = line.strip()
                    if line:
                        try:
                            self.entries.append(json.loads(line))
                        except json.JSONDecodeError:
                            pass
        self.handle = open(path, "a", encoding="utf-8")

    def add(self, **record):
        record["at"] = dt.datetime.now().isoformat(timespec="seconds")
        self.entries.append(record)
        self.handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.handle.flush()
        os.fsync(self.handle.fileno())

    def latest(self, phase, package):
        for entry in reversed(self.entries):
            if entry.get("phase") == phase and entry.get("package") == package:
                return entry
        return None

    def close(self):
        self.handle.close()


class Engine:
    """MCP session with transparent re-initialisation after a transport drop."""

    def __init__(self, retries=4):
        self.retries = retries
        self.session = None

    def connect(self):
        self.session = _Session(timeout=180)
        self.session.init()
        return self.session

    def call(self, tool, arguments, timeout=180, attempts=None):
        attempts = attempts or self.retries
        last = None
        for index in range(attempts):
            try:
                if self.session is None:
                    self.connect()
                return self.session.call(tool, arguments, timeout=timeout)
            except (OSError, McpError) as error:
                last = error
                self.session = None
                time.sleep(min(2 + index * 3, 12))
        raise last


# --------------------------------------------------------------------------- inventory
def device_packages():
    out = adb(["shell", "pm list packages -3"])
    packages = []
    for line in out.splitlines():
        name = line.strip().replace("package:", "").strip()
        if not name or any(name.startswith(prefix) for prefix in SKIP_PREFIXES):
            continue
        packages.append(name)
    return sorted(set(packages))


def package_facts(package):
    """OS-level facts for root-cause analysis (not needed for the verdict itself)."""
    dump = adb(["shell", f"pm dump {package}"])
    facts = {"package": package}
    for key, pattern in (
        ("codePath", r"codePath=(\S+)"),
        ("versionCode", r"versionCode=(\d+)"),
        ("minSdk", r"minSdk=(\d+)"),
        ("targetSdk", r"targetSdk=(\d+)"),
        ("versionName", r"versionName=(\S+)"),
        ("splits", r"splits=\[([^\]]*)\]"),
    ):
        match = re.search(pattern, dump)
        facts[key] = match.group(1).strip() if match else ""
    paths = [line.replace("package:", "").strip() for line in adb(["shell", f"pm path {package}"]).splitlines() if line.strip()]
    total = 0
    for path in paths:
        raw = adb(["shell", f"stat -c %s '{path}' 2>/dev/null"]).strip()
        if raw.isdigit():
            total += int(raw)
    facts["apkBytes"] = total
    facts["apkFiles"] = len(paths)
    split_names = [s for s in (facts.get("splits") or "").split(",") if s and s != "base"]
    facts["splitCount"] = len(split_names)
    return facts


# --------------------------------------------------------------------------- import
def phase_inventory(engine, journal, packages, outdir):
    for package in packages:
        if journal.latest("inventory", package):
            continue
        facts = package_facts(package)
        journal.add(phase="inventory", **facts)
        print(f"[inv] {package:45} v{facts['versionName'][:14]:14} tgt={facts['targetSdk']:3} "
              f"min={facts['minSdk']:3} apk={facts['apkBytes'] // 1048576}MB splits={facts['splitCount']}", flush=True)


def existing_space_ids(engine):
    reply = engine.call("runtime_query", {"action": "spaces"})
    return {s.get("space") for s in ((reply.get("result") or {}).get("spaces") or []) if s.get("space")}


def ensure_space(engine, space, known):
    if space in known:
        return
    # The contract caps a space display name at 24 characters.
    label = ("兼容 " + slug(space))[:24]
    reply = engine.call(
        "space_manage",
        {"action": "create", "operationId": f"qa-space-{slug(space).lower()}-{int(time.time() * 1000) % 100000}", "space": space, "name": label},
    )
    if reply.get("ok") is False and "ALREADY" not in (reply.get("error") or ""):
        raise RuntimeError(((reply.get("errorCode") or "") + ":" + (reply.get("error") or ""))[:200])
    known.add(space)


def phase_import(engine, journal, packages, outdir):
    known = existing_space_ids(engine)
    # An installation created by an earlier session already owns this (space, package);
    # reuse its generation instead of asking for a conflicting second install.
    catalog = engine.call("runtime_query", {"action": "applications"}).get("result") or {}
    existing = {}
    for installation in catalog.get("installations") or []:
        if installation.get("state") == "COMMITTED":
            existing.setdefault((installation.get("space"), installation["package"]), installation.get("installGeneration") or installation.get("generation", ""))
    for package in packages:
        space = space_for(package)
        previous = journal.latest("import", package)
        if previous and previous.get("state") in ("SUCCEEDED", "UNSUPPORTED"):
            continue
        if previous and previous.get("state") in ("IMPORT_FAILED", "SCOPE_FENCED") and not RETRY_FAILED:
            continue
        if (space, package) in existing:
            journal.add(phase="import", package=package, space=space, state="SUCCEEDED", engineState="ALREADY", generation=existing[(space, package)], operationId="")
            print(f"[imp] {package:45} ALREADY        (previous installation retained)", flush=True)
            continue
        try:
            ensure_space(engine, space, known)
        except Exception as error:
            journal.add(phase="import", package=package, space=space, state="IMPORT_FAILED", error=("SPACE_CREATE:" + str(error))[:400])
            print(f"[imp] {package:45} SPACE_FAILED   {str(error)[:80]}", flush=True)
            continue
        operation = f"qa-imp-{slug(package)}-{int(time.time())}"
        started = time.time()
        try:
            reply = engine.call(
                "application_manage",
                {"action": "import_device", "operationId": operation, "space": space, "package": package, "expectedGeneration": ""},
                timeout=600,
            )
        except Exception as error:  # transport/timeout
            journal.add(phase="import", package=package, space=space, state="TRANSPORT_ERROR", error=str(error)[:400], operationId=operation)
            print(f"[imp] {package:45} TRANSPORT_ERROR {str(error)[:80]}", flush=True)
            continue
        result = reply.get("result") or {}
        state = result.get("state") or ("" if reply.get("ok") else "FAILED")
        error_text = reply.get("error") or result.get("error") or ""
        if state == "SUCCEEDED":
            verdict = "SUCCEEDED"
        elif "RECOVERY_SCOPE_UNKNOWN" in error_text:
            verdict = "SCOPE_FENCED"
        else:
            verdict = "IMPORT_FAILED"
        record = {
            "phase": "import",
            "package": package,
            "space": space,
            "state": verdict,
            "engineState": state,
            "generation": result.get("generation", ""),
            "operationId": operation,
            "seconds": round(time.time() - started, 1),
        }
        if verdict != "SUCCEEDED":
            record["errorCode"] = reply.get("errorCode", "")
            record["error"] = error_text[:600]
        journal.add(**record)
        print(f"[imp] {package:45} {verdict:14} {record['seconds']:6.1f}s {record.get('errorCode', '')} {error_text[:60]}", flush=True)


# --------------------------------------------------------------------------- launch
CRASH_PATTERNS = (
    "FATAL EXCEPTION",
    "Fatal signal",
    "ANR in ",
    "Process: ",
    "has died",
    "Force finishing",
    "Abort message",
)


def logcat_since(epoch, buffer):
    raw = adb(["logcat", "-d", "-v", "epoch", "-b", buffer, "-t", "3000"])
    lines = []
    for line in raw.splitlines():
        match = re.match(r"^\s*(\d+(?:\.\d+)?)\s", line)
        if match and float(match.group(1)) >= epoch - 2:
            lines.append(line)
    return lines


def crash_evidence(package, epoch, pid, outdir):
    blocks = []
    for buffer in ("crash", "main", "system"):
        for line in logcat_since(epoch, buffer):
            if any(pattern in line for pattern in CRASH_PATTERNS) or (pid and f" {pid} " in line and " E " in line):
                blocks.append(line.rstrip())
    text = "\n".join(blocks[-400:])
    if text:
        path = os.path.join(outdir, "logs", f"{slug(package)}.logcat.txt")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    fatals = [line for line in blocks if "FATAL EXCEPTION" in line or "Fatal signal" in line or "ANR in " in line]
    return text, fatals[:6]


def live_target(engine, package, generation, process_generation=""):
    reply = engine.call("application_query", {"action": "targets"})
    targets = (reply.get("result") or {}).get("targets") or []
    for target in targets:
        if target.get("package") != package:
            continue
        if generation and target.get("installGeneration") and target["installGeneration"] != generation:
            continue
        if process_generation and target.get("processGeneration") not in (None, process_generation):
            continue
        return target
    return None


def foreground_package():
    """The guest Activity owns the screen when either the focused window or the resumed
    activity belongs to the guest package. Both are read from the platform, so an engine
    bookkeeping bug cannot turn into a false pass."""
    focus = adb(["shell", "dumpsys window | grep -E 'mCurrentFocus' | tail -1"]).strip()
    match = re.search(r"u0 ([A-Za-z0-9_.]+)/", focus)
    focused = match.group(1) if match else ""
    resumed = adb(["shell", "dumpsys activity activities | grep -E 'ResumedActivity' | tail -2"]).strip()
    resumed_hits = set(re.findall(r"u0 ([A-Za-z0-9_.]+)/", resumed))
    line = focus[:160] + (" | resumed=" + ",".join(sorted(resumed_hits)) if resumed_hits else "")
    return focused, resumed_hits, line


def stop_target(engine, target):
    """Reclaim one slot. `application_stop` rejects any extra target field, so only the
    declared identity is sent; its business result is inspected, not just exceptions."""
    payload = {
        "space": target.get("space", ""),
        "package": target.get("package", ""),
        "installGeneration": target.get("installGeneration", ""),
    }
    try:
        reply = engine.call(
            "application_stop",
            {"operationId": f"qa-stop-{slug(payload['package'])}-{int(time.time() * 1000) % 100000}", "target": payload},
            timeout=120,
        )
    except Exception as error:
        return str(error)[:200]
    if reply.get("ok") is False:
        return (reply.get("errorCode") or "STOP_REJECTED") + ":" + (reply.get("error") or "")[:160]
    return ""


def cleanup_targets(engine, only_package=""):
    try:
        reply = engine.call("application_query", {"action": "targets"})
    except Exception:
        return
    for target in (reply.get("result") or {}).get("targets") or []:
        if only_package and target.get("package") != only_package:
            continue
        stop_target(engine, target)


def installation_mode(engine, target):
    """The engine's own per-project runtime mode, read before any switch."""
    try:
        reply = engine.call("application_query", {"action": "launch_entries", "target": target}, timeout=90)
    except Exception:
        return ""
    return ((reply.get("result") or {}).get("mode") or "")


def switch_mode(engine, target, mode):
    """`set_mode` only accepts a stopped installation; callers stop the guest first."""
    reply = engine.call(
        "application_manage",
        {"operationId": f"qa-mode-{slug(target['package'])}-{int(time.time())}",
         "action": "set_mode", "target": target, "mode": mode},
        timeout=120,
    )
    if reply.get("ok") is False or reply.get("isError"):
        raise RuntimeError((reply.get("errorCode") or "") + ":" + (reply.get("error") or "")[:200])
    return True


def recycle_spaces(engine, journal, pending, flush=False):
    """Give back the affinity leases the pool only releases on space deletion.

    A just-judged guest can still hold a WorkManager job (`INSTALL_IN_USE_BY_JOB`), and a
    delete that never succeeds leaks that lease for good: eight leaked leases exhaust the
    pool, after which every later launch is refused and every refused launch fences the
    space it touched (`RECOVERY_SCOPE_UNKNOWN`), which in turn refuses deletion. So stop the
    guest explicitly before retrying, and keep retrying across iterations; `flush=True` is
    the last chance after the loop.
    """
    queued = list(pending)
    pending.clear()
    for item in queued:
        space, attempts, target = item["space"], item["attempts"], item.get("target")
        try:
            reply = engine.call(
                "space_delete",
                {"operationId": f"qa-free-{slug(space)}-{int(time.time() * 1000) % 100000}", "space": space},
                timeout=120,
            )
            error = "" if not (reply.get("ok") is False or reply.get("isError")) else (reply.get("error") or "")[:300]
        except Exception as failure:
            error = str(failure)[:300]
        if error and target:
            # The guest outlived the judgement window; make the space deletable instead of
            # leaving its lease pinned.
            try:
                engine.call("application_stop", {"operationId": f"qa-stop-{slug(space)}-{int(time.time() * 1000) % 100000}", "target": target}, timeout=90)
            except Exception:
                pass
            try:
                reply = engine.call(
                    "space_delete",
                    {"operationId": f"qa-free2-{slug(space)}-{int(time.time() * 1000) % 100000}", "space": space},
                    timeout=120,
                )
                error = "" if not (reply.get("ok") is False or reply.get("isError")) else (reply.get("error") or "")[:300]
            except Exception as failure:
                error = str(failure)[:300]
        if error and attempts + 1 < 4 and not flush:
            pending.append({"space": space, "attempts": attempts + 1, "target": target})
            continue
        journal.add(phase="recycle", space=space, ok=not error, error=error, attempts=attempts + 1)
        print(f"[free] {space:45} {'OK' if not error else error[:70]}", flush=True)


def phase_launch(engine, journal, packages, outdir, wait_ms, settle_ms):
    cleanup_targets(engine)
    # A launch-only run against installations created by an earlier campaign has no import
    # rows in this journal; resolve those from the engine catalog instead of skipping them.
    catalog = {}
    try:
        reply = engine.call("runtime_query", {"action": "applications"}, timeout=120)
        for row in ((reply.get("result") or {}).get("installations") or []):
            if (row.get("space") or "").startswith("qa-"):
                catalog.setdefault(row.get("package"), row)
    except Exception as error:
        print(f"[campaign] catalog unavailable: {str(error)[:120]}", flush=True)
    switched = []  # (target, previous mode) so a comparison run never leaves the device switched
    pending = []   # spaces of already-judged apps, deleted to release their affinity leases
    for package in packages:
        if RECYCLE:
            recycle_spaces(engine, journal, pending)
        imported = journal.latest("import", package)
        if imported and imported.get("state") == "SUCCEEDED":
            generation = imported.get("generation", "")
            space = imported.get("space") or space_for(package)
        else:
            row = catalog.get(package)
            if not row:
                print(f"[run] {package:45} NO_INSTALLATION", flush=True)
                continue
            generation = row.get("installGeneration") or row.get("generation") or ""
            space = row.get("space") or ""
        last = journal.latest("launch", package)
        if last and last.get("verdict"):
            # Only a verdict that describes the guest is final. A lost controller lease or an
            # exhausted affinity pool describes the harness, so it must be retried, otherwise
            # a mid-run engine death silently freezes dozens of packages as unjudged.
            transient = last["verdict"] in ("TRANSPORT_ERROR", "MODE_SET_FAILED", "SCOPE_FENCED") or (
                last["verdict"] == "START_REJECTED" and "PROCESS_ALREADY_RUNNING" in (last.get("error") or "")
            ) or any(marker in (last.get("error") or "") for marker in (
                "ENGINE_NOT_CONNECTED", "ACTIVITY_AFFINITY_CAPACITY_EXHAUSTED", "WORKER_DIED",
                "MCP_", "DeadObjectException",
            ))
            if not transient:
                continue
        target = {"space": space, "package": package, "installGeneration": generation}
        if RECYCLE and space:
            pending.append({"space": space, "attempts": 0, "target": dict(target)})
        operation = f"qa-start-{slug(package)}-{int(time.time())}"
        epoch = time.time()
        started = time.time()
        record = {"phase": "launch", "package": package, "space": space, "operationId": operation, "installGeneration": generation}
        if MODE:
            before = installation_mode(engine, target)
            record["modeBefore"] = before
            if before != MODE:
                try:
                    switch_mode(engine, target, MODE)
                    record["modeChanged"] = f"{before or 'unknown'}->{MODE}"
                    switched.append((dict(target), before))
                except Exception as error:
                    record.update(verdict="MODE_SET_FAILED", error=str(error)[:300],
                                  seconds=round(time.time() - started, 1))
                    journal.add(**record)
                    print(f"[run] {package:45} MODE_SET_FAILED {str(error)[:90]}", flush=True)
                    continue
            record["mode"] = MODE
        # Android 10+ blocks background activity launches; the engine's own entry dispatch is
        # only allowed to surface the guest while the engine itself is visible, which is also
        # the real user flow (open the workbench, launch the app).
        adb(["shell", "am start -n dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity"])
        time.sleep(1.2)
        try:
            reply = engine.call("process_start", {"operationId": operation, "target": target, "component": "", "probeRevision": ""}, timeout=180)
        except Exception as error:
            record.update(verdict="TRANSPORT_ERROR", error=str(error)[:400])
            journal.add(**record)
            print(f"[run] {package:45} TRANSPORT_ERROR {str(error)[:80]}", flush=True)
            continue
        if reply.get("isError") and "PROCESS_ALREADY_RUNNING" in (reply.get("error") or ""):
            cleanup_targets(engine, package)
            operation = f"qa-start-{slug(package)}-{int(time.time())}-r"
            try:
                reply = engine.call("process_start", {"operationId": operation, "target": target, "component": "", "probeRevision": ""}, timeout=180)
                record["operationId"] = operation
                record["retriedAfterCleanup"] = True
            except Exception as error:
                record.update(verdict="TRANSPORT_ERROR", error=str(error)[:400])
                journal.add(**record)
                print(f"[run] {package:45} TRANSPORT_ERROR {str(error)[:80]}", flush=True)
                continue
        if reply.get("isError") or not (reply.get("result") or {}).get("pid"):
            message = reply.get("error") or ""
            record.update(
                verdict="SCOPE_FENCED" if "RECOVERY_SCOPE_UNKNOWN" in message else "START_REJECTED",
                errorCode=reply.get("errorCode", ""),
                error=message[:600],
                seconds=round(time.time() - started, 1),
            )
            journal.add(**record)
            print(f"[run] {package:45} {record['verdict']:18} {record['errorCode']} {message[:80]}", flush=True)
            continue

        result = reply["result"]
        pid = result.get("pid")
        process_generation = result.get("processGeneration", "")
        record.update(pid=pid, processGeneration=process_generation)

        # Signals 2+3 polled together: a cold guest may need most of the window before its
        # own Activity takes the foreground, so an early hit ends the window instead of
        # being mistimed into a false "no screen" verdict.
        deadline = time.time() + wait_ms / 1000.0
        alive = True
        focus_package, focus_line, resumed_hits = "", "", set()
        while time.time() < deadline:
            time.sleep(2)
            try:
                alive = live_target(engine, package, generation, process_generation) is not None
            except Exception:
                alive = True  # transport noise must not be read as a crash
            if not alive:
                break
            focus_package, resumed_hits, focus_line = foreground_package()
            if focus_package == package or package in resumed_hits:
                break
        foreground = focus_package == package or package in resumed_hits

        # Signal 4: engine-side proof that the guest built a real view hierarchy. This is
        # independent of the window manager, so a BAL restriction or a device screen state
        # cannot fake a blank first screen.
        ui_nodes = 0
        ui_error = ""
        if alive:
            try:
                tree = engine.call(
                    "process_inspect",
                    {"action": "ui_tree", "target": {**target, "processGeneration": process_generation}, "includeInvisible": False},
                    timeout=120,
                )
                if tree.get("ok") is False:
                    ui_error = (tree.get("errorCode") or "") + ":" + (tree.get("error") or "")[:160]
                else:
                    blob = json.dumps(tree.get("result") or {}, ensure_ascii=False)
                    ui_nodes = len(re.findall(r'"(?:text|className|bounds|children)"', blob))
                    if len(blob) < 80:
                        ui_nodes = min(ui_nodes, 0)
            except Exception as error:
                ui_error = str(error)[:160]

        text, fatals = crash_evidence(package, epoch, pid, outdir)
        anr = [line for line in text.splitlines() if f"ANR in {package}" in line]

        # Engine-side diagnostics for the failing guest (hook attach state, class loader,
        # native libraries it failed to satisfy) are the raw material of the root cause.
        diagnostics = ""
        if alive and ui_nodes < 3 and not foreground:
            try:
                diag = engine.call(
                    "process_inspect",
                    {"action": "diagnostics", "target": {**target, "processGeneration": process_generation}},
                    timeout=120,
                )
                diagnostics = json.dumps(diag.get("result") if diag.get("ok") is not False else diag, ensure_ascii=False)[:4000]
            except Exception as error:
                diagnostics = "DIAGNOSTICS_ERROR:" + str(error)[:200]

        rendered = ui_nodes >= 3 or foreground
        if not alive:
            verdict = "CRASH_ON_START" if fatals else "PROCESS_GONE"
        elif anr:
            verdict = "ANR"
        elif not rendered:
            verdict = "NO_FOREGROUND_SCREEN"
        else:
            verdict = "PASS"

        record.update(
            verdict=verdict,
            alive=alive,
            foreground=foreground,
            uiTreeNodes=ui_nodes,
            uiError=ui_error,
            diagnostics=diagnostics,
            focus=focus_line,
            fatals=fatals,
            seconds=round(time.time() - started, 1),
        )
        if verdict != "PASS":
            shot = os.path.join(outdir, "shots", f"{slug(package)}.png")
            os.makedirs(os.path.dirname(shot), exist_ok=True)
            adb(["shell", f"screencap -p /sdcard/_qa.png"])
            subprocess.run(["adb", "-s", "d861a4fa", "pull", "/sdcard/_qa.png", shot], capture_output=True, text=True)
            adb(["shell", "rm -f /sdcard/_qa.png"])
            record["screenshot"] = os.path.relpath(shot, ROOT)

        # Reclaim the slot; keep the record even when the stop itself fails.
        if alive or process_generation:
            error = stop_target(engine, {**target, "processGeneration": process_generation})
            if error:
                record["stopError"] = error
        journal.add(**record)
        print(f"[run] {package:45} {verdict:20} alive={alive} fg={foreground} {record['seconds']:5.1f}s "
              f"{fatals[0][:70] if fatals else ''}", flush=True)
        if settle_ms:
            time.sleep(settle_ms / 1000.0)

    # Restore every installation this run switched, including the ones whose start was
    # rejected (those take an early `continue` and would otherwise stay in MODE). A recycling
    # run deletes those installations instead, so the mode disappears with the space.
    if RECYCLE:
        recycle_spaces(engine, journal, pending, flush=True)
    if MODE and RESTORE_MODE and switched and not RECYCLE:
        for target, before in switched:
            base = {"phase": "mode_restore", "package": target["package"], "space": target["space"],
                    "installGeneration": target["installGeneration"]}
            try:
                switch_mode(engine, target, before)
                journal.add(**base, restored=before)
            except Exception as error:
                journal.add(**base, restoreError=str(error)[:200])


def main():
    global SPACE, SHARED_SPACE, MODE, RESTORE_MODE, RECYCLE, RETRY_FAILED
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", default="all", choices=["inventory", "import", "launch", "all"])
    parser.add_argument("--out", default="")
    parser.add_argument("--only", default="")
    parser.add_argument("--skip", default="", help="comma separated packages to exclude")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--space", default="", help="use one shared space instead of one space per package")
    parser.add_argument("--wait-ms", type=int, default=20000, help="stability window per guest")
    parser.add_argument("--settle-ms", type=int, default=1500, help="pause between guests")
    parser.add_argument("--mode", default="", choices=["", "thin", "thick"],
                        help="switch each stopped installation to this runtime mode before launching")
    parser.add_argument("--keep-mode", action="store_true", help="do not restore the previous mode after judging")
    parser.add_argument("--recycle", action="store_true",
                        help="delete each judged space so its activity-affinity lease returns to the 8-slot pool")
    parser.add_argument("--retry-failed", action="store_true",
                        help="re-attempt imports recorded as IMPORT_FAILED/SCOPE_FENCED instead of skipping them")
    options = parser.parse_args()

    if options.space:
        SPACE, SHARED_SPACE = options.space, True
    else:
        SPACE, SHARED_SPACE = "", False
    MODE, RESTORE_MODE = options.mode, not options.keep_mode
    RECYCLE = options.recycle
    RETRY_FAILED = options.retry_failed
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    outdir = os.path.join(OUT, options.out or f"compat-{stamp}")
    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, "campaign.json"), "w", encoding="utf-8") as handle:
        json.dump({"startedAt": dt.datetime.now().isoformat(timespec="seconds"), "options": vars(options), "spaceMode": "shared" if SHARED_SPACE else "per-app"},
                  handle, indent=1, ensure_ascii=False)

    journal = Journal(os.path.join(outdir, "journal.jsonl"))
    engine = Engine()
    packages = device_packages()
    order = {name: index for index, name in enumerate(PRIORITY)}
    packages.sort(key=lambda name: (order.get(name, len(PRIORITY)), name))
    if options.only:
        wanted = {p.strip() for p in options.only.split(",") if p.strip()}
        packages = [p for p in packages if p in wanted]
    if options.skip:
        unwanted = {p.strip() for p in options.skip.split(",") if p.strip()}
        packages = [p for p in packages if p not in unwanted]
    if options.limit:
        packages = packages[: options.limit]
    print(f"[campaign] out={outdir} packages={len(packages)} space_mode={'shared:' + SPACE if SHARED_SPACE else 'per-app'}", flush=True)

    if options.phase in ("inventory", "all"):
        phase_inventory(engine, journal, packages, outdir)
    if options.phase in ("import", "all"):
        phase_import(engine, journal, packages, outdir)
    if options.phase in ("launch", "all"):
        phase_launch(engine, journal, packages, outdir, options.wait_ms, options.settle_ms)
    journal.close()

    # Summary snapshot after every run.
    summary = {}
    for entry in journal.entries:
        if entry.get("phase") == "launch":
            summary[entry["package"]] = entry.get("verdict")
    counts = {}
    for verdict in summary.values():
        counts[verdict] = counts.get(verdict, 0) + 1
    print("[campaign] verdicts", json.dumps(counts, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
