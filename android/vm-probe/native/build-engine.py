#!/usr/bin/env python3
"""Build the Android ARM64 system emulator as an APK-loadable shared library."""
import argparse
import importlib.util
import os
import shlex
import hashlib
import json
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("dependencies", HERE / "build-dependencies.py")
dep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dep)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--configure", action="store_true")
    args = parser.parse_args()
    source = dep.SOURCE / "qemu-5.1.0"
    objects = dep.BASE / "objects/qemu"
    objects.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env["PYTHONPYCACHEPREFIX"] = str(dep.BASE / "pycache")
    pkg_config = dep.HOST / "bin/pkgconf-target"
    pkg_config.write_text("#!/bin/sh\nexec " + shlex.quote(str(dep.HOST / "bin/pkgconf")) + " --static \"$@\"\n")
    pkg_config.chmod(0o755)
    env.update(AR=str(dep.NDK / "bin/llvm-ar"), NM=str(dep.NDK / "bin/llvm-nm"),
               RANLIB=str(dep.NDK / "bin/llvm-ranlib"), STRIP=str(dep.NDK / "bin/llvm-strip"),
               OBJCOPY=str(dep.NDK / "bin/llvm-objcopy"),
               PKG_CONFIG=str(pkg_config),
               PKG_CONFIG_LIBDIR=str(dep.PREFIX / "lib/pkgconfig"), PKG_CONFIG_PATH="")
    if args.configure or not (objects / "config-host.mak").exists():
        disabled = "docs guest-agent modules module-upgrades sdl sdl-image gtk vte curses vnc cocoa virtfs mpath xen xen-pci-passthrough brlapi curl kvm hax hvf whpx rdma pvrdma vde netmap linux-aio linux-io-uring cap-ng attr vhost-net vhost-vsock vhost-scsi vhost-crypto vhost-kernel vhost-user vhost-vdpa spice rbd libiscsi libnfs smartcard libusb usb-redir lzo snappy bzip2 lzfse zstd seccomp glusterfs tpm libssh numa libxml2 tcmalloc jemalloc replication opengl virglrenderer xfsctl tools bochs cloop dmg qcow1 vdi vvfat qed parallels sheepdog crypto-afalg capstone libpmem xkbcommon libdaxctl slirp containers blobs gnutls nettle gcrypt auth-pam".split()
        dep.invoke([source / "configure", "--target-list=aarch64-softmmu", "--cpu=aarch64",
                    "--cc=" + str(dep.NDK / "bin/aarch64-linux-android33-clang"),
                    "--cxx=" + str(dep.NDK / "bin/aarch64-linux-android33-clang++"),
                    "--host-cc=/usr/bin/clang", "--python=/usr/bin/python3", "--with-coroutine=sigaltstack",
                    "--enable-coroutine-pool", "--disable-werror", "--disable-strip",
                    "--audio-drv-list=", "--with-pkgversion=Tripoder-source-PAC-audit",
                    "--extra-cflags=-O2 -g -fPIC -mbranch-protection=standard -I" + str(dep.PREFIX / "include"),
                    "--extra-ldflags=-L" + str(dep.PREFIX / "lib") + " -Wl,-z,relro,-z,now,-z,noexecstack",
                    *["--disable-" + flag for flag in disabled]], objects, env, "qemu-configure")
        with (objects / "config-host.mak").open("a") as out:
            out.write("\n# APK native DSO remains position independent.\nQEMU_CFLAGS += -fPIC\n")
    dep.invoke([dep.NDK / "bin/aarch64-linux-android33-clang", "-O2", "-g", "-fPIC",
                "-mbranch-protection=standard", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                "-c", HERE / "crash-diagnostics.c", "-o", objects / "crash-diagnostics.o"],
               objects, env, "crash-diagnostics-compile")
    dep.invoke([dep.NDK / "bin/aarch64-linux-android33-clang", "-O2", "-g", "-fPIC",
                "-mbranch-protection=standard", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                "-c", HERE / "vm-jni.c", "-o", objects / "tripoder-jni.o"],
               objects, env, "jni-compile")
    dep.invoke([dep.NDK / "bin/aarch64-linux-android33-clang", "-O2", "-g", "-fPIC",
                "-mbranch-protection=standard", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                "-I" + str(source / "include"), "-I" + str(objects),
                "-I" + str(dep.PREFIX / "include/glib-2.0"),
                "-I" + str(dep.PREFIX / "lib/glib-2.0/include"),
                "-c", HERE / "coroutine-selftest.c", "-o", objects / "coroutine-selftest.o"],
               objects, env, "coroutine-selftest-compile")
    dep.invoke([dep.NDK / "bin/aarch64-linux-android33-clang", "-O2", "-g", "-fPIC",
                "-mbranch-protection=standard", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                "-c", HERE / "tcg-branch-selftest.c", "-o", objects / "tcg-branch-selftest.o"],
               objects, env, "tcg-branch-selftest-compile")
    dep.invoke([dep.NDK / "bin/aarch64-linux-android33-clang", "-O2", "-g", "-fPIC",
                "-mbranch-protection=standard", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                "-c", HERE / "tcg-simd-selftest.c", "-o", objects / "tcg-simd-selftest.o"],
               objects, env, "tcg-simd-selftest-compile")
    dep.invoke([dep.NDK / "bin/aarch64-linux-android33-clang", "-O2", "-g", "-fPIC",
                "-mbranch-protection=standard", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                "-c", HERE / "panel-edid.c", "-o", objects / "panel-edid.o"],
               objects, env, "panel-edid-compile")
    dep.invoke(["make", "-j8"], objects, env, "qemu-make")
    output = dep.BASE / "output"
    output.mkdir(exist_ok=True)
    library = output / "libtripoder-qemu.so"
    shutil.copy2(objects / "aarch64-softmmu" / library.name, library)
    dep.invoke([dep.NDK / "bin/llvm-strip", "--strip-debug", library], objects, env, "engine-strip-debug")
    runtime = output / "libc++_shared.so"
    shutil.copy2(dep.NDK / "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so", runtime)
    inputs = [p for p in HERE.rglob("*") if p.is_file() and p.suffix in (".c", ".py", ".json", ".patch")]
    receipt = {"schema": 1, "engine": "QEMU 5.1.0 from source", "targetSdk": 36,
               "branchProtection": "standard", "hostRootRequired": False,
               "libraries": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (library, runtime)},
               "inputs": {str(p.relative_to(HERE)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    licenses = HERE.parent / "build/assets/licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    for source_name, license_name in [("qemu-5.1.0", "COPYING"), ("qemu-5.1.0", "COPYING.LIB"),
                                      ("glib-2.56.1", "COPYING"), ("pixman-0.40.0", "COPYING"),
                                      ("libffi-3.3", "LICENSE"), ("gettext-0.22.5", "COPYING")]:
        shutil.copy2(dep.SOURCE / source_name / license_name, licenses / (source_name + "-" + license_name + ".txt"))


if __name__ == "__main__":
    main()
