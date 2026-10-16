#!/usr/bin/env python3
"""Generate owned small declaring classes for the full process-budget boundary."""
from pathlib import Path

def generate(directory):
    package=Path(directory)/'dev/tripoder/selfhookprobe/capacity'
    package.mkdir(parents=True,exist_ok=True)
    sources=[]
    for group in range(32):
        lines=['package dev.tripoder.selfhookprobe.capacity;',f'public final class Group{group} {{']
        for method in range(128):
            slot=group*128+method
            lines.append(f'    public static int value{method}(int n) {{ dev.tripoder.selfhookprobe.CapacityChecks.body(); return n+{slot}; }}')
        lines.append('}')
        path=package/f'Group{group}.java';path.write_text('\n'.join(lines)+'\n');sources.append(path)
    return sources
