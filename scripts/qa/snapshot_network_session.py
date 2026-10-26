#!/usr/bin/env python3
"""Freeze explicit build inputs so independent workspace edits cannot alter an acceptance APK."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

repo=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,default=repo/'.tripoder-local/network-session/source')
args=parser.parse_args()
target=args.output.resolve()
if target.exists():raise RuntimeError('SNAPSHOT_ALREADY_EXISTS')
roots=['android/runtime/src','android/runtime/native','android/runtime/licenses','android/control','android/control-test',
       'android/engine','android/workbench','android/self-hook','android/scripts','android/runtime-fixtures/preview','src','agent']
files=[repo/'package.json',repo/'android/self-hook-probe/platform.json']
for name in roots:
    files.extend(p for p in (repo/name).rglob('*') if p.is_file() and not any(part in ('build','__pycache__','.gradle') for part in p.relative_to(repo/name).parts))
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
before={str(p.relative_to(repo)):digest(p) for p in files}
for p in files:
    out=target/p.relative_to(repo);out.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,out)
if before!={str(p.relative_to(repo)):digest(p) for p in files}:raise RuntimeError('SOURCE_CHANGED_WHILE_FREEZING')
for relative in ('node_modules','android/runtime-probe/build'):
    link=target/relative;link.parent.mkdir(parents=True,exist_ok=True);link.symlink_to(repo/relative,target_is_directory=True)
(target.parent/'snapshot-inputs.json').write_text(json.dumps(before,indent=2)+'\n')
print(json.dumps({'source':str(target),'files':len(files)}))
