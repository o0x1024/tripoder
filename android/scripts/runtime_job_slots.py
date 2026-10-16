"""Shared physical JobService pool. Every entry requires Android's binding permission."""
from pathlib import Path

PROCESSES = 8
PER_PROCESS = 4


def generate_job_slots(build: Path, manifest: Path):
    directory = build / 'job-slots'
    directory.mkdir(parents=True, exist_ok=True)
    sources, entries = [], []
    for process in range(PROCESSES):
        source = directory / f'JobP{process}.java'
        classes = []
        for lane in range(PER_PROCESS):
            classes.append(f'    public static final class J{lane} extends dev.tripoder.runtime.RuntimeJobGateway {{}}')
            entries.append(f'<service android:name="dev.tripoder.runtime.generated.JobP{process}$J{lane}" '
                           f'android:process=":slot{process}" android:exported="true" '
                           'android:permission="android.permission.BIND_JOB_SERVICE"/>')
        source.write_text(f'package dev.tripoder.runtime.generated;\npublic final class JobP{process} {{\n'
                          + '\n'.join(classes) + '\n}\n')
        sources.append(source)
    text = manifest.read_text()
    if text.count('<!-- JOB_COMPONENT_POOL -->') != 1:
        raise ValueError('JOB_COMPONENT_POOL_MARKER_REQUIRED')
    output = directory / 'AndroidManifest.xml'
    output.write_text(text.replace('<!-- JOB_COMPONENT_POOL -->', '\n'.join(entries)))
    return sources, output
