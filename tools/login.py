#!/usr/bin/env python3
"""Login phase: use the first TP_QA_PHONES test number for SMS login, then browse."""
import sys, os, time, json, re, argparse
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drive import (Mcp, mcp_nodes, tap_xy, tap_node, text_of, shot, crash_check, set_mode, start_app,
                   stop_app, launcher_component, record, newest_sms, OUT, safe, full_for)

PHONE = os.environ.get("TP_QA_PHONES", "").split(",")[0].strip()

ENTRY = ["手机号登录", "手机验证码登录", "验证码登录", "手机号/验证码登录", "手机号", "立即登录", "我要登录",
         "登录/注册", "其他方式登录", "密码登录", "登录"]
SEND = ["获取验证码", "发送验证码", "获取短信验证码", "发送短信验证码", "获取动态码", "验证码"]
SUBMIT = ["登录", "确定", "确认", "下一步", "立即登录", "进入", "完成"]
GATE = ["登录", "手机号", "验证码", "注册"]
LOGGED = ["退出登录", "个人中心", "我的订单", "我的"]

def find(ns, words, clickable_only=True):
    for w in words:
        for n in ns:
            v = n["text"] or n["desc"]
            if not v:
                continue
            if v == w or (len(v) <= 14 and w in v):
                if (not clickable_only) or n["click"] or n["focus"]:
                    return n, w
    return None, None

def fields_of(ns):
    out = [n for n in ns if n["cls"] in ("EditText", "AutoCompleteTextView", "ExtractedText")]
    if not out:
        out = [n for n in ns if n["focus"]]
    return out

def attempt_login(mcp, pkg, gen, pg, name, verbose=True):
    log = []
    def d():
        ns, acts, _ = mcp_nodes(mcp, pkg, gen, pg)
        return ns, [a.get("activity") for a in acts]
    ns, acts = d()
    texts = text_of(ns)
    log.append({"step": "initial", "activity": acts, "texts": texts[:14]})
    has_gate = any(g in t for t in texts for g in GATE)
    if not has_gate:
        return {"result": "no_gate", "log": log}
    # 1) open phone login entry
    n, w = find(ns, ENTRY)
    if n is None:
        return {"result": "no_entry", "log": log}
    tap_node(n); time.sleep(2.5)
    ns, acts = d(); log.append({"step": f"tap_entry:{w}", "activity": acts, "texts": text_of(ns)[:14]})
    # 2) phone field
    fs = fields_of(ns)
    phone_field = None
    for f in fs:
        if "手机" in (f["text"] + f["desc"]) or "phone" in (f["text"] + f["desc"]).lower():
            phone_field = f; break
    if phone_field is None and fs:
        phone_field = fs[0]
    if phone_field is None:
        # try tapping a label like 请输入手机号
        n2, w2 = find(ns, ["请输入手机号", "手机号", "请输入手机号码"], clickable_only=False)
        if n2:
            tap_node(n2); time.sleep(1.2)
            ns, acts = d(); fs = fields_of(ns)
            phone_field = fs[0] if fs else None
    if phone_field is None:
        return {"result": "no_phone_field", "log": log}
    tap_node(phone_field); time.sleep(1.0)
    os.system(f"adb -s ${{ANDROID_SERIAL:-d861a4fa}} shell input text {PHONE} >/dev/null 2>&1")
    time.sleep(1.5)
    ns, acts = d(); log.append({"step": "typed_phone", "texts": text_of(ns)[:14]})
    # 3) send code
    n3, w3 = find(ns, SEND)
    if n3 is None:
        return {"result": "no_send_button", "log": log}
    ts = int(time.time() * 1000)
    tap_node(n3); time.sleep(3)
    ns, acts = d(); log.append({"step": f"tap_send:{w3}", "texts": text_of(ns)[:14]})
    code, body, addr = newest_sms(ts, timeout=70)
    if not code:
        return {"result": "no_sms", "log": log}
    log.append({"step": "sms", "code": code, "addr": addr, "body": body[:120]})
    # 4) code field
    ns, acts = d()
    fs = fields_of(ns)
    code_field = None
    for f in fs:
        if "验证码" in (f["text"] + f["desc"]) or "code" in (f["text"] + f["desc"]).lower():
            code_field = f; break
    if code_field is None:
        cands = [f for f in fs if f["b"][1] > 300]
        code_field = cands[0] if cands else (fs[0] if fs else None)
    if code_field is None:
        return {"result": "no_code_field", "log": log}
    tap_node(code_field); time.sleep(1.0)
    os.system(f"adb -s ${{ANDROID_SERIAL:-d861a4fa}} shell input text {code} >/dev/null 2>&1")
    time.sleep(1.5)
    ns, acts = d(); log.append({"step": "typed_code", "texts": text_of(ns)[:14]})
    # 5) submit
    n5, w5 = find(ns, SUBMIT)
    if n5:
        tap_node(n5); time.sleep(5)
    ns, acts = d(); log.append({"step": f"submit:{w5}", "activity": acts, "texts": text_of(ns)[:18]})
    texts = text_of(ns)
    ok = not any(t in ("登录", "获取验证码") for t in texts) or any(l in t for t in texts for l in LOGGED)
    return {"result": "logged_in" if ok else "uncertain", "log": log, "final_texts": texts[:18]}

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--pkgs", default="")
    ap.add_argument("--mode", default="thin")
    ap.add_argument("--limit", type=int, default=3)
    a = ap.parse_args()
    if not re.fullmatch(r"1[3-9]\d{9}", PHONE):
        ap.error("TP_QA_PHONES must provide a test mobile number")
    os.makedirs(OUT, exist_ok=True)
    st = json.load(open(os.path.join(OUT, "state.json")))
    gen = {i["package"]: i["installGeneration"] for i in st["installations"]}
    dev = {x["package"]: x.get("name", x["package"]) for x in json.load(open(os.path.join(OUT, "apps.json")))}
    pkgs = [p for p in a.pkgs.split(",") if p][:a.limit]
    mcp = Mcp()
    try:
        for pkg in pkgs:
            g = gen[pkg]
            rec = {"package": pkg, "name": dev.get(pkg, pkg), "mode": a.mode, "phone": PHONE,
                   "ts": time.strftime("%F %T")}
            print(f"=== login {pkg} ({a.mode})", flush=True)
            try:
                stop_app(mcp, pkg, g)
                set_mode(mcp, pkg, g, a.mode)
                comp = launcher_component(mcp, pkg, g)
                pg, pid, _ = start_app(mcp, pkg, g, comp)
                rec["processGeneration"] = pg
                if not pg:
                    rec["outcome"] = "start_failed"
                else:
                    time.sleep(8)
                    out = attempt_login(mcp, pkg, g, pg, rec["name"])
                    rec["outcome"] = out.get("result")
                    rec["login_log"] = out.get("log")
                    rec["final_texts"] = out.get("final_texts")
                    shot(f"login_{a.mode}_{pkg}")
                    rec["crash"] = crash_check(mcp, pkg, g, pg)
                    print("   ->", rec["outcome"], rec.get("final_texts", [])[:8], flush=True)
            except Exception as e:
                rec["exception"] = repr(e)
                print("   EXC", repr(e), flush=True)
            record(os.path.join(OUT, f"login-{a.mode}.json"), rec)
    finally:
        mcp.close()
