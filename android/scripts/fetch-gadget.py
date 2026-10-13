#!/usr/bin/env python3
"""Download the pinned official ARM64 Gadget; verify both archive and library."""
import hashlib
import json
import lzma
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'gadget.lock.json').read_text())
target = root / 'app/src/main/jniLibs/arm64-v8a/libgadget.so'


def digest(data):
    return hashlib.sha256(data).hexdigest()


if target.exists() and digest(target.read_bytes()) == lock['librarySha256']:
    print('Pinned Gadget already verified')
else:
    request = urllib.request.Request(lock['url'], headers={'User-Agent': 'Tripoder-build/0.1'})
    with urllib.request.urlopen(request, timeout=120) as response:
        archive = response.read()
    if digest(archive) != lock['archiveSha256']:
        raise SystemExit('Gadget archive checksum mismatch')
    library = lzma.decompress(archive)
    if digest(library) != lock['librarySha256']:
        raise SystemExit('Gadget library checksum mismatch')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(library)
    print('Pinned Gadget downloaded and verified')
