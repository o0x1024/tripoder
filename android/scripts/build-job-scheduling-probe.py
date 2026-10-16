#!/usr/bin/env python3
"""Build durable scheduling acceptance in its own UID; no guest execution or native Hook acceptance."""
import argparse
import json
from pathlib import Path
import shutil
from runtime_apk_builder import ApkBuilder, sha
from runtime_job_slots import generate_job_slots
from runtime_activity_slots import generate_activity_slots


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'java-home', 'key'):
        parser.add_argument('--' + name, required=True, type=Path)
    for name in ('platform', 'build-tools'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    android = repo / 'android'
    build = android / 'job-scheduling-probe/build'
    builder = ApkBuilder(repo, build, args.sdk, args.platform, args.build_tools, args.java_home, args.key)
    roots = ['runtime/src', 'self-hook/java', 'job-scheduling-probe/src', 'runtime-fixtures/jobs']
    files = sorted(p for root in roots for p in (android / root).rglob('*') if p.is_file())
    files += [android / 'job-scheduling-probe/AndroidManifest.xml', android / 'runtime-probe/src/dev/tripoder/runtimeprobe/ProbeReports.java']
    files += [Path(__file__).resolve(), *[android / 'scripts' / name for name in ('runtime_apk_builder.py', 'runtime_job_slots.py', 'runtime_activity_slots.py', 'validate-job-scheduling-probe.py')]]
    before = {str(p.relative_to(repo)): sha(p) for p in files}
    fixture = builder.apk('jobs-fixture', sorted((android / 'runtime-fixtures/jobs').rglob('*.java')), android / 'runtime-fixtures/jobs/AndroidManifest.xml')
    assets = build / 'assets'
    assets.mkdir(exist_ok=True)
    shutil.copyfile(fixture, assets / fixture.name)
    fixture_info = {'asset': fixture.name, 'sha256': sha(fixture), 'packageName': 'dev.tripoder.jobsfixture'}
    (assets / 'jobs-fixture.json').write_text(json.dumps(fixture_info))
    job_sources, manifest = generate_job_slots(build, android / 'job-scheduling-probe/AndroidManifest.xml')
    activity_sources, manifest = generate_activity_slots(build, manifest, 'SelfHookTaskP')
    sources = sorted(p for root in roots[:3] for p in (android / root).rglob('*.java'))
    sources += [android / 'runtime-probe/src/dev/tripoder/runtimeprobe/ProbeReports.java', *job_sources, *activity_sources]
    artifact = builder.apk('jobs-probe', sources, manifest, assets=assets, jars=[builder.apksig()])
    after = {str(p.relative_to(repo)): sha(p) for p in files}
    if before != after:
        raise RuntimeError('SOURCES_CHANGED_DURING_BUILD')
    receipt = {'schema': 1, 'package': 'dev.tripoder.jobsprobe', 'hostApkSha256': sha(artifact), 'fixture': fixture_info,
               'sourceSha256': before, 'certificateSha256': builder.cert_sha256, 'generatedManifestSha256': sha(manifest),
               'scope': 'durable-scheduling-only', 'nativeHookIncluded': False}
    (build / 'build-receipt.json').write_text(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
