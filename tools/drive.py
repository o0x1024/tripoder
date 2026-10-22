#!/usr/bin/env python3
"""Automated dual-mode guest test driver for Tripoder (MCP over stdio + adb UI control)."""
import subprocess, json, sys, time, os, re, threading, uuid, argparse, xml.etree.ElementTree as ET

ROOT = "/Users/len/code/tripoder"
ADB = "/opt/homebrew/bin/adb"
SERIAL = os.environ.get("ANDROID_SERIAL", "d861a4fa")
CONN = os.path.expanduser("~/.tripoder/tripoder-connection.json")
SHOTS = os.path.join(ROOT, "reports/shots")
OUT = os.path.join(ROOT, "reports/run")

# ---------------- MCP client ----------------
class Mcp:
    def __init__(self, timeout=180):
        self.timeout = timeout
        self.pending = {}
        self.log = open(os.path.join(OUT, "mcp.log"), "a")
        self.child = subprocess.Popen(
            ["node", os.path.join(ROOT, "agent/server.mjs"), "--connection", CONN],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
        threading.Thread(target=self._reader, daemon=True).start()
        self._rpc("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                 "clientInfo": {"name": "driver", "version": "1"}})
        self._notify("notifications/initialized", {})

    def _reader(self):
        for line in self.child.stdout:
            try:
                msg = json.loads(line)
            except Exception:
                continue
            slot = self.pending.pop(msg.get("id"), None)
            if slot:
                slot(msg)

    def _notify(self, method, params):
        self.child.stdin.write(json.dumps({"jsonrpc": "2.0", "method": method, "params": params}) + "\n")
        self.child.stdin.flush()

    def _rpc(self, method, params):
        rid = str(uuid.uuid4())
        ev = threading.Event()
        box = {}
        self.pending[rid] = lambda m: (box.update(m), ev.set())
        self.child.stdin.write(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}) + "\n")
        self.child.stdin.flush()
        if not ev.wait(self.timeout):
            self.pending.pop(rid, None)
            raise TimeoutError(f"MCP timeout {method}")
        if "error" in box:
            raise RuntimeError(f"MCP error {method}: {box['error']}")
        return box

    def call(self, tool, args, timeout=None):
        self.timeout = timeout or self.timeout
        r = self._rpc("tools/call", {"name": tool, "arguments": args})
        res = r.get("result", {})
        text = "\n".join(c.get("text", "") for c in res.get("content", []) if isinstance(c, dict))
        try:
            parsed = json.loads(text)
        except Exception:
            parsed = {"raw": text}
        self.log.write(json.dumps({"tool": tool, "args": args, "ok": not res.get("isError"),
                                   "out": parsed}, ensure_ascii=False)[:4000] + "\n")
        self.log.flush()
        parsed["_isError"] = bool(res.get("isError"))
        return parsed

    def close(self):
        try:
            self.child.stdin.close()
            self.child.terminate()
        except Exception:
            pass

# ---------------- adb helpers ----------------
def sh(cmd, timeout=90):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr

def ui_xml():
    sh(f"{ADB} -s {SERIAL} shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1", 60)
    return sh(f"{ADB} -s {SERIAL} shell cat /sdcard/ui.xml", 60)

def ui_nodes():
    x = ui_xml()
    out = []
    try:
        root = ET.fromstring(x[x.index("<?xml"):])
    except Exception:
        return out
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", "") or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"text": (n.get("text") or "").strip(), "desc": (n.get("content-desc") or "").strip(),
                    "cls": (n.get("class") or "").split(".")[-1], "pkg": n.get("package") or "",
                    "click": n.get("clickable") == "true", "focus": n.get("focusable") == "true",
                    "scroll": n.get("scrollable") == "true", "sel": n.get("selected") == "true",
                    "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "b": (x1, y1, x2, y2)})
    return out

def mcp_nodes(mcp, pkg, gen, pg, include_invisible=False):
    """Authoritative in-process hierarchy read (uiautomator caches stale windows)."""
    r = mcp.call("ui_dump_hierarchy", {"target": full_for(pkg, gen, pg),
                                      "includeInvisible": include_invisible}, 90)
    res = r.get("result") or {}
    acts = res.get("activities") or []
    nodes = []
    for a in acts:
        for n in (a.get("nodes") or []):
            b = n.get("bounds") or [0, 0, 0, 0]
            if len(b) != 4:
                continue
            x1, y1, x2, y2 = b
            nodes.append({"text": (n.get("text") or "").strip(), "desc": (n.get("contentDescription") or "").strip(),
                          "cls": (n.get("className") or "").split(".")[-1],
                          "click": bool(n.get("clickable")), "focus": bool(n.get("focusable")),
                          "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "b": (x1, y1, x2, y2)})
    return nodes, acts, r

def tap_xy(x, y):
    sh(f"{ADB} -s {SERIAL} shell input tap {int(x)} {int(y)}")

def tap_node(n):
    tap_xy(n["cx"], n["cy"])

def text_of(ns):
    seen, out = set(), []
    for n in ns:
        v = n["text"] or n["desc"]
        if v and v not in seen:
            seen.add(v)
            out.append(v)
    return out

def top_activity():
    o = sh(f"{ADB} -s {SERIAL} shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity' | head -1")
    return o.strip()

def shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    p = os.path.join(SHOTS, name + ".png")
    sh(f"{ADB} -s {SERIAL} shell screencap -p /sdcard/s.png >/dev/null 2>&1")
    sh(f"{ADB} -s {SERIAL} pull /sdcard/s.png {p} >/dev/null 2>&1")
    return p

def newest_sms(since_ts, keyword=None, timeout=45):
    """Poll inbox for an SMS newer than since_ts (ms). Returns (code, body, addr)."""
    end = time.time() + timeout
    while time.time() < end:
        raw = sh(f"{ADB} -s {SERIAL} shell content query --uri content://sms/inbox --projection address:body:date 2>/dev/null", 120)
        best = None
        for line in raw.split("\n"):
            m = re.match(r"Row: \d+ address=(.*?), body=(.*), date=(\d+)", line)
            if not m:
                continue
            addr, body, date = m.group(1), m.group(2), int(m.group(3))
            if date < since_ts - 5000:
                continue
            if keyword and keyword not in body:
                continue
            c = re.search(r"(?<!\d)(\d{4,6})(?!\d)", body)
            if not c:
                continue
            if best is None or date > best[0]:
                best = (date, c.group(1), body, addr)
        if best:
            return best[1], best[2], best[3]
        time.sleep(4)
    return None, None, None

DISMISS = ["同意并继续", "同意并登录", "同意", "仅在使用中允许", "始终允许", "使用应用时允许", "允许",
           "我知道了", "跳过", "以后再说", "稍后再说", "暂不", "关闭", "开始使用", "进入应用", "立即体验",
           "确认", "好的", "我知道了"]

def dismiss(ns, rounds=4, delay=1.4, words=None):
    tapped = []
    words = words or DISMISS
    for _ in range(rounds):
        hit = None
        for w in words:
            for n in ns:
                v = n["text"] or n["desc"]
                if v and (v == w or (len(v) <= 12 and w in v)) and (n["click"] or n["focus"]):
                    hit = (w, n)
                    break
            if hit:
                break
        if not hit:
            break
        tap_node(hit[1])
        tapped.append(hit[0])
        time.sleep(delay)
        ns = ui_nodes()
    return tapped, ns

# ---------------- driver actions ----------------
def safe(pkg):
    return re.sub(r"[^A-Za-z0-9_-]", "-", pkg)[:40]

def targets_for(pkg, gen):
    return {"space": "default", "package": pkg, "installGeneration": gen}

def full_for(pkg, gen, pg):
    return {"space": "default", "package": pkg, "installGeneration": gen, "processGeneration": pg}

def stop_app(mcp, pkg, gen):
    try:
        mcp.call("application_stop", {"operationId": f"stop-{safe(pkg)}-{int(time.time())}", "target": targets_for(pkg, gen)}, 120)
    except Exception as e:
        return str(e)
    return "ok"

def set_mode(mcp, pkg, gen, mode):
    r = mcp.call("application_mode_set", {"operationId": f"mode-{safe(pkg)}-{mode}-{int(time.time())}",
                                          "target": targets_for(pkg, gen), "mode": mode}, 180)
    return r

def start_app(mcp, pkg, gen, component=""):
    r = mcp.call("process_start", {"operationId": f"start-{safe(pkg)}-{int(time.time()*1000)%1000000}",
                                   "target": targets_for(pkg, gen), "component": component,
                                   "probeRevision": ""}, 180)
    res = r.get("result") or {}
    return res.get("processGeneration"), res.get("pid"), r

def launcher_component(mcp, pkg, gen):
    r = mcp.call("application_launch_targets", {"target": targets_for(pkg, gen)}, 120)
    ts = ((r.get("result") or {}).get("targets")) or []
    for t in ts:
        if t.get("launcher") and t.get("supported"):
            return t["component"]
    for t in ts:
        if t.get("supported"):
            return t["component"]
    return ""

def crash_check(mcp, pkg, gen, pg):
    out = {"mcp_failures": [], "logcat": [], "top": top_activity()}
    if pg:
        try:
            r = mcp.call("crash_diagnostics_get", {"target": full_for(pkg, gen, pg)}, 90)
            out["mcp_failures"] = ((r.get("result") or {}).get("failures") or [])[:5]
        except Exception as e:
            out["mcp_failures"] = [f"diag_error:{e}"]
    lg = sh(f"{ADB} -s {SERIAL} logcat -d -t 600 2>/dev/null", 90)
    for line in lg.split("\n"):
        if re.search(r"FATAL EXCEPTION|begin of crash|guest crash|ANR in |DeadSystemException|Process .* has died", line):
            out["logcat"].append(line.strip()[:180])
    out["logcat"] = out["logcat"][:8]
    return out

def browse_tabs(mcp=None, pkg=None, gen=None, pg=None, delay=3.0, max_tabs=5):
    """Tap bottom nav nodes; return per-tab text snapshots."""
    ns = mcp_nodes(mcp, pkg, gen, pg)[0] if mcp else ui_nodes()
    h = max((n["b"][3] for n in ns), default=2624)
    cands = {}
    for n in ns:
        if n["click"] and n["b"][1] > h - 300 and (n["b"][3] - n["b"][1]) < 300:
            label = n["text"] or n["desc"]
            if label and n["cx"] not in cands:
                cands[n["cx"]] = label
    res = []
    for cx, label in sorted(cands.items())[:max_tabs]:
        tap_xy(cx, h - 130)
        time.sleep(delay)
        ns2 = mcp_nodes(mcp, pkg, gen, pg)[0] if mcp else ui_nodes()
        snap = {"tab": label, "texts": text_of(ns2)[:18], "nodes": len(ns2)}
        res.append(snap)
    return res

def record(path, rec):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = []
    if os.path.exists(path):
        try:
            data = json.load(open(path))
        except Exception:
            data = []
    data.append(rec)
    json.dump(data, open(path, "w"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["ping", "one", "pass"])
    ap.add_argument("--pkg")
    ap.add_argument("--gen")
    ap.add_argument("--mode", default="thin")
    ap.add_argument("--apps", default=os.path.join(ROOT, "reports/run/apps.json"))
    ap.add_argument("--limit", type=int, default=100)
    ap.add_argument("--skip", default="")
    ap.add_argument("--browse", default="1")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    mcp = Mcp()
    try:
        if a.cmd == "ping":
            print(json.dumps(mcp.call("runtime_status", {}))[:400])
        elif a.cmd == "one":
            gen = a.gen
            print(json.dumps({"component": launcher_component(mcp, a.pkg, gen)}))
        elif a.cmd == "pass":
            apps = json.load(open(a.apps))
            st = json.load(open(os.path.join(OUT, "state.json")))
            gen = {i["package"]: i["installGeneration"] for i in st["installations"]}
            skip = set(x for x in a.skip.split(",") if x)
            # free process slots: stop any guest apps still running from previous sessions
            try:
                rr = mcp.call("runtime_resources", {}, 60)
                running = {t.get("package") for t in ((rr.get("result") or {}).get("tasks") or []) if t.get("package")}
                for rp in running:
                    if rp in gen:
                        print(f"   cleanup stop {rp}", flush=True)
                        stop_app(mcp, rp, gen[rp])
            except Exception as e:
                print("   cleanup error", e, flush=True)
            done = 0
            for entry in apps:
                pkg = entry["package"] if isinstance(entry, dict) else entry
                if pkg in skip or pkg not in gen:
                    continue
                if done >= a.limit:
                    break
                done += 1
                g = gen[pkg]
                name = entry.get("name", pkg) if isinstance(entry, dict) else pkg
                rec = {"package": pkg, "name": name, "mode": a.mode, "ts": time.strftime("%F %T")}
                print(f"=== [{done}] {pkg} {name} ({a.mode})", flush=True)
                try:
                    stop_app(mcp, pkg, g)
                    mr = set_mode(mcp, pkg, g, a.mode)
                    rec["mode_set_ok"] = not mr.get("_isError")
                    comp = launcher_component(mcp, pkg, g)
                    rec["component"] = comp
                    pg, pid, sr = start_app(mcp, pkg, g, comp)
                    rec["processGeneration"] = pg
                    rec["pid"] = pid
                    rec["start_ok"] = bool(pg)
                    if not pg:
                        rec["start_error"] = json.dumps(sr.get("error") or sr)[:400]
                        print("   start failed:", rec["start_error"], flush=True)
                        record(os.path.join(OUT, f"results-{a.mode}.json"), rec)
                        continue
                    ns, acts, dr = mcp_nodes(mcp, pkg, g, pg)
                    for _ in range(6):
                        if ns:
                            break
                        time.sleep(4)
                        ns, acts, dr = mcp_nodes(mcp, pkg, g, pg)
                    time.sleep(2)
                    ns, acts, dr = mcp_nodes(mcp, pkg, g, pg)
                    rec["activities"] = [a.get("activity") for a in acts]
                    tapped, ns = dismiss(ns, rounds=4)
                    rec["dismissed"] = tapped
                    rec["initial_texts"] = text_of(ns)[:18]
                    shot(f"{a.mode}_{pkg}_1")
                    if a.browse == "1":
                        rec["tabs"] = browse_tabs(mcp, pkg, g, pg)
                        shot(f"{a.mode}_{pkg}_last")
                    cc = crash_check(mcp, pkg, g, pg)
                    rec.update({"crash": cc})
                    alive = top_activity()
                    rec["alive"] = "tripoder" in alive
                    print(f"   texts={rec['initial_texts'][:6]}", flush=True)
                    print(f"   alive={rec['alive']} failures={len(cc['mcp_failures'])} logcat={cc['logcat'][:2]}", flush=True)
                except Exception as e:
                    rec["exception"] = repr(e)
                    print("   EXC", repr(e), flush=True)
                record(os.path.join(OUT, f"results-{a.mode}.json"), rec)
    finally:
        mcp.close()
