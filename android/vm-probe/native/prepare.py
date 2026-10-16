#!/usr/bin/env python3
"""Verify pinned native sources/toolchain, then extract and apply reviewed patches."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
BASE = HERE.parent / "build/native"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    lock = json.loads((HERE / "dependencies.lock.json").read_text())
    downloads = BASE / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    for name, item in lock["downloads"].items():
        path = downloads / name
        if not path.exists():
            if not args.download:
                raise SystemExit(f"Missing {path}; use --download to fetch pinned source")
            temporary = path.with_suffix(path.suffix + ".part")
            with urllib.request.urlopen(item["url"], timeout=60) as src, temporary.open("wb") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)
            if temporary.stat().st_size != item["bytes"] or digest(temporary) != item["sha256"]:
                raise SystemExit(f"Download hash mismatch: {name}")
            temporary.replace(path)
        if path.stat().st_size != item["bytes"] or digest(path) != item["sha256"]:
            raise SystemExit(f"Source hash mismatch: {name}")
        print(f"Verified {name}", flush=True)
        if args.verify_only:
            continue
        if name == "ndk.zip":
            if not (BASE / "tools/android-ndk-r30/source.properties").exists():
                (BASE / "tools").mkdir(exist_ok=True)
                # unzip preserves the executable modes shipped by the NDK.
                subprocess.run(["unzip", "-q", str(path), "-d", str(BASE / "tools")], check=True)
            continue
        source_name = name.split(".tar.")[0]
        source = BASE / "src" / source_name
        stamp = source / ".tripoder-patches.json"
        patch = HERE / "patches" / (source_name + ".patch")
        expected = {"archive": item["sha256"], "patch": digest(patch) if patch.exists() else None}
        if source.exists():
            if not stamp.exists() or json.loads(stamp.read_text()) != expected:
                raise SystemExit(f"Unverified/changed source tree: {source}; use a fresh source directory")
            continue
        with tarfile.open(path) as archive:
            # Firmware is separately pinned. Do not unpack unrelated EDK2 ROM
            # build trees, which include upstream absolute symlinks.
            members = [m for m in archive.getmembers()
                       if not m.issym() and not m.islnk()
                       and not (source_name == "qemu-5.1.0" and m.name.startswith(source_name + "/roms/"))]
            archive.extractall(BASE / "src", members=members, filter="data")
        if patch.exists():
            subprocess.run(["patch", "--batch", "-p1", "-i", str(patch)], cwd=source, check=True)
        stamp.write_text(json.dumps(expected, indent=2) + "\n")


if __name__ == "__main__":
    main()
