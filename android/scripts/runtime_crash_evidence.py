"""Keep raw Android fatal observations; classify only a completed owned failure fixture."""
import re


def java_fatal_evidence(log, result, phase):
    events = []
    current = {}
    for line in log.splitlines():
        match = re.fullmatch(r"\S+\s+\S+\s+(\d+)\s+(\d+)\s+E\s+AndroidRuntime\s*:\s?(.*)", line)
        if not match:
            continue
        pid, tid, message = int(match[1]), int(match[2]), match[3]
        key = (pid, tid)
        if message.startswith("FATAL EXCEPTION:"):
            event = {"pid": pid, "tid": tid, "messages": [message]}
            events.append(event)
            current[key] = event
        elif key in current:
            current[key]["messages"].append(message)

    approved = set()
    if phase in {"p2", "failure", "providers", "provider-identity"} and result and result.get("passed") is True:
        for run in result.get("runs", []):
            checks = run.get("checks", [])
            if (run.get("completed") is True and run.get("passed") is True
                    and run.get("uid") == result.get("hostUid")
                    and checks and all(item.get("passed") is True for item in checks)
                    and any(item.get("name") == "uncaught-observation-preserves-app-handler-and-throwable" for item in checks)):
                approved.add(run.get("pid"))

    expected = []
    unexpected = []
    for event in events:
        lines = event["messages"]
        valid = (event["pid"] in approved and len(lines) >= 5
                 and lines[0] == "FATAL EXCEPTION: owned-uncaught-handler"
                 and re.fullmatch(r"Process: dev\.tripoder\.runtimeprobe:slot[0-7], PID: " + str(event["pid"]), lines[1])
                 and lines[2] == "java.lang.RuntimeException: owned-failure-dispatch-contract"
                 and re.fullmatch(r"\s+at dev\.tripoder\.provideridentity\.FailureDispatchChecks\.inspect\(FailureDispatchChecks.java:\d+\)", lines[3])
                 and all(re.fullmatch(r"\s+at (?:dev\.tripoder\.provideridentity\.(?:FailureDispatchChecks|PackageResourcesActivity|FailureDispatchActivity)[^ ]*|java\.lang\.Thread\.run)\([^\n]+\)", line) for line in lines[3:]))
        if valid:
            # Exactly one real dispatch was requested by each completed fixture.
            expected.append({"pid": event["pid"], "tid": event["tid"], "kind": "owned-handler-preserved"})
            approved.remove(event["pid"])
        else:
            unexpected.append(event)
    raw = "FATAL EXCEPTION:" in log
    # Unparsed fatal formats remain failures instead of disappearing in the parser.
    unknown_format = log.count("FATAL EXCEPTION:") != len(events)
    return {"javaFatalObserved": raw, "expectedFixtureUncaught": expected,
            "unexpectedJavaFatalObserved": bool(unexpected) or unknown_format}
