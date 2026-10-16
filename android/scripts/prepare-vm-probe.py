#!/usr/bin/env python3
"""Prepare pinned VM media; --download explicitly enables network retrieval."""
import argparse
import bz2
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "vm-probe"
    lock = json.loads((root / "dependencies.lock.json").read_text())
    deps, media, assets = root / "build/deps", root / "build/media", root / "build/assets"
    for path in (deps, media, assets / "licenses"):
        path.mkdir(parents=True, exist_ok=True)
    for name, item in lock["downloads"].items():
        destination = deps / name
        if not destination.exists():
            if not args.download:
                raise SystemExit(f"Missing {name}; explicit --download required")
            temporary = destination.with_suffix(destination.suffix + ".part")
            request = urllib.request.Request(item["url"], headers={"User-Agent": "Tripoder-VM-Probe"})
            with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output, 1024 * 1024)
            if temporary.stat().st_size != item["bytes"] or sha256(temporary) != item["sha256"]:
                raise SystemExit(f"Downloaded dependency hash mismatch: {name}")
            temporary.replace(destination)
        if destination.stat().st_size != item["bytes"] or sha256(destination) != item["sha256"]:
            raise SystemExit(f"Dependency hash mismatch: {name}")
        print(f"Verified {name}", flush=True)
    shutil.copy2(deps / "edk2-licenses.txt", assets / "licenses/edk2-licenses.txt")
    with zipfile.ZipFile(deps / "lineage-arm64.zip") as archive:
        for name in ("vda.qcow2", "vdb.qcow2"):
            destination = media / name
            if not destination.exists():
                with archive.open("LineageOS_on_arm64.utm/Data/" + name) as stream, destination.open("wb") as output:
                    shutil.copyfileobj(stream, output, 1024 * 1024)
    for source, name in [("edk2-aarch64-code.fd.bz2", "efi-code.fd"), ("edk2-arm-vars.fd.bz2", "efi-vars.fd")]:
        destination = media / name
        if not destination.exists():
            with bz2.open(deps / source) as stream, destination.open("wb") as output:
                shutil.copyfileobj(stream, output, 1024 * 1024)
    for name, item in lock["media"].items():
        if (media / name).stat().st_size != item["bytes"] or sha256(media / name) != item["sha256"]:
            raise SystemExit(f"Prepared media hash mismatch: {name}")
    (assets / "media.json").write_text(json.dumps(lock["media"], indent=2) + "\n")
    shutil.copy2(root / "dependencies.lock.json", assets / "dependencies.lock.json")
    print("Pinned dependencies and original guest media verified. No device changes.")


if __name__ == "__main__":
    main()
