#!/usr/bin/env python3
"""Build the fixed QEMU development dependencies inside the ignored build directory."""
import os
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "build/native"
SOURCE = BASE / "src"
PREFIX = BASE / "prefix"
HOST = BASE / "host"
NDK = BASE / "tools/android-ndk-r30/toolchains/llvm/prebuilt/darwin-x86_64"
LOGS = BASE / "logs"
SKIP_CONFIGURE = False


def invoke(command, directory, environment, name):
    LOGS.mkdir(parents=True, exist_ok=True)
    print(name, flush=True)
    with (LOGS / (name + ".log")).open("w") as log:
        result = subprocess.run(list(map(str, command)), cwd=directory, env=environment,
                                stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((LOGS / (name + ".log")).read_text()[-10000:], flush=True)
        raise SystemExit(f"{name} failed: {result.returncode}")


def build(name, options, environment, prefix):
    directory = BASE / "objects" / name
    directory.mkdir(parents=True, exist_ok=True)
    if not SKIP_CONFIGURE or not (directory / "Makefile").exists():
        invoke([SOURCE / name / "configure", "--prefix=" + str(prefix), *options],
               directory, environment, name.replace("/", "-") + "-configure")
    invoke(["make", "-j8", "PYTHON=/usr/bin/python3"], directory, environment, name.replace("/", "-") + "-make")
    invoke(["make", "install", "PYTHON=/usr/bin/python3"], directory, environment, name.replace("/", "-") + "-install")


def main():
    global SKIP_CONFIGURE
    parser = argparse.ArgumentParser()
    parser.add_argument("--from-stage", choices=["pkgconf", "libffi", "gettext", "glib", "pixman"], default="pkgconf")
    parser.add_argument("--skip-configure", action="store_true")
    args = parser.parse_args()
    SKIP_CONFIGURE = args.skip_configure
    stage = ["pkgconf", "libffi", "gettext", "glib", "pixman"].index(args.from_stage)
    env = dict(os.environ)
    env["PYTHON"] = "/usr/bin/python3"
    env["PYTHONPYCACHEPREFIX"] = str(BASE / "pycache")
    env["PATH"] = str(HOST / "bin") + ":/opt/homebrew/opt/gettext/bin:" + env["PATH"]
    if stage <= 0:
        build("pkgconf-2.3.0", ["--disable-shared"], env, HOST)
    target = dict(env)
    target.update(CC=str(NDK / "bin/aarch64-linux-android33-clang"),
                  CXX=str(NDK / "bin/aarch64-linux-android33-clang++"),
                  AR=str(NDK / "bin/llvm-ar"), RANLIB=str(NDK / "bin/llvm-ranlib"),
                  NM=str(NDK / "bin/llvm-nm"), OBJDUMP=str(NDK / "bin/llvm-objdump"),
                  STRIP=str(NDK / "bin/llvm-strip"),
                  CFLAGS="-O2 -g -fPIC -mbranch-protection=standard",
                  CXXFLAGS="-O2 -g -fPIC -mbranch-protection=standard",
                  LDFLAGS="-Wl,-z,relro,-z,now,-z,noexecstack",
                  PKG_CONFIG=str(HOST / "bin/pkgconf"),
                  PKG_CONFIG_LIBDIR=str(PREFIX / "lib/pkgconfig"),
                  PKG_CONFIG_PATH="")
    common = ["--host=aarch64-linux-android", "--build=aarch64-apple-darwin",
              "--disable-shared", "--enable-static"]
    if stage <= 1:
        build("libffi-3.3", [*common, "--disable-docs"], target, PREFIX)
    if stage <= 2:
        build("gettext-0.22.5/gettext-runtime", [*common, "--disable-java", "--disable-csharp",
              "--disable-libasprintf", "--disable-openmp", "--with-included-gettext"], target, PREFIX)
    target["CPPFLAGS"] = "-I" + str(PREFIX / "include")
    target["LDFLAGS"] += " -L" + str(PREFIX / "lib")
    if stage <= 3:
        # AAPCS64: descending stack; ELF has no leading underscore; uint64_t
        # is unsigned long on LP64 Android (verified by the NDK headers).
        target.update(glib_cv_stack_grows="no", glib_cv_uscore="no",
                      glib_cv_long_long_format="ll")
        build("glib-2.56.1", [*common, "--with-pcre=internal", "--with-libiconv=no",
          "--disable-maintainer-mode", "--disable-libmount", "--disable-selinux", "--disable-fam",
          "--disable-dtrace", "--disable-systemtap", "--disable-libelf", "--disable-gtk-doc",
          "--disable-gtk-doc-html", "--disable-man", "--disable-installed-tests"], target, PREFIX)
    build("pixman-0.40.0", [*common, "--disable-gtk", "--disable-libpng",
          "--disable-loongson-mmi", "--disable-arm-simd"], target, PREFIX)


if __name__ == "__main__":
    main()
