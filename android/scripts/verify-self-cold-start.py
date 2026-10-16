#!/usr/bin/env python3
"""Bind a verified owned Hook run to a later process loading its runtime image."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def read(directory):
    result_path = directory / 'device/result.json'
    result = json.loads(result_path.read_text())
    verified = json.loads((directory / 'verification.json').read_text())
    build = json.loads((directory / 'build-receipt.json').read_text())
    require(result['passed'] is True and verified['passed'] is True
            and verified['resultSha256'] == sha(result_path), 'VERIFIED_RESULT_REQUIRED')
    require(result['runId'] == verified['runId'] == directory.name, 'RUN_IDENTITY')
    require(result['apkSha256'] == build['apkSha256'] == verified['apkSha256']
            and result['build']['buildId'] == build['buildId'] == verified['buildId']
            and result['build']['sourceDigest'] == build['sourceDigest'] == verified['sourceDigest'], 'BUILD_IDENTITY')
    require(result['package'] == 'dev.tripoder.selfhookprobe' and result['debuggable'] is False
            and result['uid'] >= 10000 and result['pid'] > 0, 'OWNED_PROCESS')
    return result, verified, build


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--image-kind', choices=['runtime', 'aot'], default='runtime')
    args = parser.parse_args()
    before, before_verified, original = read(args.before)
    after, after_verified, later = read(args.after)
    require(before['phase'] in ['H3g-owned-active-entry', 'H3h-managed-abi'] and before['storage']['published'] > 0
            and before['storage']['rootedOutsideDeclaredTables'] == before['storage']['published'], 'ROOTED_ORIGINAL_RUN_REQUIRED')
    require(before['apkSha256'] == after['apkSha256'] and original['buildId'] == later['buildId']
            and original['sourceDigest'] == later['sourceDigest'], 'SAME_APK_REQUIRED')
    require(before['pid'] != after['pid'] and before['uid'] == after['uid']
            and before['timestampMs'] < after['timestampMs'], 'LATER_INDEPENDENT_PROCESS_REQUIRED')
    if after['phase'] == 'H3h-managed-abi':
        require(before['phase'] == 'H3h-managed-abi'
                and before_verified['rootEvidence']['ownerMovementProven'] is True
                and before_verified['rootEvidence']['containerSlotMoves'] > 0
                and after_verified['rootEvidence']['containerSlotMoves'] > 0, 'SAME_BUILD_MOVING_OWNER_PROOF_REQUIRED')
    maps_path = args.after / 'device/maps.txt'
    require(sha(maps_path) == after['mapsSha256'], 'COLD_MAPS_IDENTITY')
    maps = maps_path.read_text()
    image = (r'/data/(?:user/0|data)/dev\.tripoder\.selfhookprobe/cache/oat_primary/arm64/base\.art'
             if args.image_kind == 'runtime' else r'/data/app/[^/\s]+/dev\.tripoder\.selfhookprobe-[^/\s]+/oat/arm64/base\.art')
    image_lines = [line for line in maps.splitlines() if re.search(image + r'(?:\]|$)', line)]
    require(any(re.search(r'\s[1-9][0-9]*\s+/data/', line) for line in image_lines), 'APP_IMAGE_FILE_MUST_BE_MAPPED')
    if args.image_kind == 'runtime':
        require(any('[anon:dalvik-' in line for line in image_lines), 'RUNTIME_APP_IMAGE_MUST_BE_LOADED')
    else:
        require(after['phase'] == 'H3h-managed-abi', 'AOT_IMAGE_METHOD_EVIDENCE_REQUIRED')
        for case in after['abi']['roundtrips']:
            address = int(case['targetBefore']['method'], 16)
            regions = []
            for line in maps.splitlines():
                match = re.match(r'^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+\S+\s+\S+\s+(\d+)\s*(.*)$', line)
                if match and int(match[1], 16) <= address < int(match[2], 16):
                    regions.append(match)
            require(len(regions) == 1 and regions[0][3] == 'rw-p' and regions[0][4] == '0'
                    and regions[0][5].startswith('[anon:dalvik-/data/app/')
                    and regions[0][5].endswith('/oat/arm64/base.art]'), 'AOT_IMAGE_METADATA_MUST_BE_LOADED')
    report = {'passed': True, 'beforeRunId': before['runId'], 'afterRunId': after['runId'],
              'beforePid': before['pid'], 'afterPid': after['pid'], 'uid': before['uid'],
              'apkSha256': before['apkSha256'], 'sourceDigest': original['sourceDigest'],
              'imageKind': args.image_kind, 'appImageMappings': image_lines, 'mapsSha256': sha(maps_path),
              'beforeResultSha256': sha(args.before / 'device/result.json'),
              'afterResultSha256': sha(args.after / 'device/result.json'),
              'verifierSha256': sha(Path(__file__)),
              'scope': 'Same APK, separate successful process and actual runtime-image mappings; not generic ART or product acceptance'}
    with args.output.open('x') as stream:
        stream.write(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
