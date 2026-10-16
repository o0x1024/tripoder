#!/usr/bin/env python3
"""Check the real image patch through independent QCOW2/GPT/FAT reads and a full byte diff."""
import argparse
import hashlib
import json
import shutil
import struct
import subprocess
import sys
from pathlib import Path

sys.dont_write_bytecode = True
from qcow_readonly import QcowReader, EspReader
from vendor_readonly import VendorReader

HERE = Path(__file__).resolve().parent
PROBE = HERE.parent


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    source = PROBE / "build/media/vda.qcow2"
    locked = json.loads((PROBE / "dependencies.lock.json").read_text())
    source_digest = digest(source)
    assert source_digest == locked["media"]["vda.qcow2"]["sha256"], "Test requires the locked source image"
    work = PROBE / "build/boot-config-test"
    work.mkdir(exist_ok=True)
    target = work / "vda.qcow2"
    shutil.copyfile(source, target)
    java = args.java_home / "bin/java"
    production = PROBE / "src/dev/tripoder/vmprobe"
    subprocess.run([args.java_home / "bin/javac", "--release", "17", "-d", work,
                    production / "QcowImage.java", production / "GuestBootConfig.java",
                    production / "GuestSoftwareProfile.java",
                    HERE / "BootConfigCheck.java"], check=True)

    def patch(check=True):
        return subprocess.run([java, "-cp", work, "dev.tripoder.vmprobe.BootConfigCheck", target],
                              check=check, capture_output=True, text=True)

    patch()
    before_second = digest(target)
    patch()
    assert digest(target) == before_second, "Second application changed the disk"
    reader = QcowReader(source)
    esp = EspReader(reader)
    original = esp.read_file("/boot/grub/grub.cfg")
    changed_reader = QcowReader(target)
    patched = EspReader(changed_reader).read_file("/boot/grub/grub.cfg")
    assert original.count(b"\tboot_android\n") == 1
    expected = original.replace(b"\tboot_android\n",
                                b"\tboot_android androidboot.seriallogging.logcat_buffer=all androidboot.low_perf=1\n")
    assert patched == expected, "Independent filesystem read differs from the sole allowed edit"
    profile_path = "/etc/init/hw/init.low_performance.rc"
    original_profile = VendorReader(reader).find_file(profile_path)
    normal_profile = VendorReader(changed_reader).find_file(profile_path)
    active_properties = [line.strip() for line in normal_profile.decode().splitlines()
                         if line.strip().startswith("setprop ")]
    assert active_properties == ["setprop debug.sf.nobootanimation 1",
                                 "setprop ro.hw_timeout_multiplier 50", "setprop ro.setupwizard.mode DISABLED"]
    assert len(original_profile) == len(normal_profile) == 1416
    assert hashlib.sha256(normal_profile).hexdigest() == "b833b7224aca0517c538b1c77ac8857a460f63f250f9fd978089255a199e5291"

    def physical(virtual):
        size = reader.cluster_size
        cluster, inside = divmod(virtual, size)
        table = reader.l1[cluster // (size // 8)] & 0x00FFFFFFFFFFFE00
        reader.file.seek(table + cluster % (size // 8) * 8)
        return (struct.unpack(">Q", reader.file.read(8))[0] & 0x00FFFFFFFFFFFE00) + inside

    # These are audited virtual extents of the locked image, not arbitrary write permissions.
    start = physical(30003712)
    entry_size = physical(8112476)
    profile_offset = physical(3720466432)
    inode_offset = physical(3752897024)
    allowed = [(start, start + len(patched)), (entry_size, entry_size + 4),
               (profile_offset, profile_offset + len(normal_profile))]
    assert source.stat().st_size == target.stat().st_size
    changed_bytes = 0
    with source.open("rb") as left, target.open("rb") as right:
        offset = 0
        while True:
            a, b = left.read(65536), right.read(65536)
            if not a:
                break
            if a != b:
                diffs = [offset + index for index, (old, new) in enumerate(zip(a, b)) if old != new]
                assert all(any(lo <= value < hi for lo, hi in allowed) for value in diffs)
                changed_bytes += len(diffs)
            offset += len(a)
    reader.file.close()
    changed_reader.file.close()
    # Exercise the exact preceding diagnostic configuration as an in-place upgrade,
    # preserving a sentinel elsewhere on the writable guest disk.
    logged = original.replace(b"\tboot_android\n",
                              b"\tboot_android androidboot.seriallogging.logcat_buffer=all\n")
    sentinel_offset = target.stat().st_size - 1
    with target.open("r+b") as stream:
        stream.seek(start)
        stream.write(logged + bytes(len(patched) - len(logged)))
        stream.seek(entry_size)
        stream.write(struct.pack("<I", len(logged)))
        stream.seek(sentinel_offset)
        old_sentinel = stream.read(1)
        sentinel = bytes([old_sentinel[0] ^ 0x5a])
        stream.seek(sentinel_offset)
        stream.write(sentinel)
    patch()
    upgraded = QcowReader(target)
    assert EspReader(upgraded).read_file("/boot/grub/grub.cfg") == expected
    upgraded.file.close()
    with target.open("rb") as stream:
        stream.seek(sentinel_offset)
        assert stream.read(1) == sentinel, "Migration reset unrelated guest data"
    # Migrate the immediately preceding low-RAM run, whose GRUB was already current.
    with target.open("r+b") as stream:
        stream.seek(profile_offset)
        stream.write(original_profile)
    patch()
    migrated = QcowReader(target)
    assert VendorReader(migrated).find_file(profile_path) == normal_profile
    migrated.file.close()
    for bad_offset, expected_error in [(profile_offset, "Unrecognized guest software profile content"),
                                        (inode_offset, "Unrecognized guest software profile inode")]:
        with target.open("r+b") as stream:
            stream.seek(bad_offset)
            old = stream.read(1)
            stream.seek(bad_offset)
            stream.write(bytes([old[0] ^ 1]))
        rejected_hash = digest(target)
        rejected = patch(check=False)
        assert rejected.returncode != 0 and expected_error in rejected.stderr
        assert digest(target) == rejected_hash, "Rejected profile was modified"
        with target.open("r+b") as stream:
            stream.seek(bad_offset)
            stream.write(old)
    with target.open("r+b") as stream:
        stream.seek(start)
        stream.write(b"X")
    unknown_hash = digest(target)
    rejected = patch(check=False)
    assert rejected.returncode != 0 and "Unrecognized guest GRUB configuration" in rejected.stderr
    assert digest(target) == unknown_hash, "Rejected image was modified"
    assert digest(source) == source_digest, "Original image was modified"
    receipt = {"sourceImageSha256": source_digest, "changedBytes": changed_bytes,
               "patchedConfigSha256": hashlib.sha256(patched).hexdigest(),
               "patchedSoftwareProfileSha256": hashlib.sha256(normal_profile).hexdigest(),
               "independentFilesystemRead": True, "allOtherBytesUnchanged": True,
               "idempotent": True, "previousDiagnosticMigrationPreservedData": True,
               "previousLowRamProfileMigrated": True, "unknownProfileAndInodeRejectedWithoutWriting": True,
               "unknownConfigRejectedWithoutWriting": True}
    (work / "result.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
