// Native half of the anti-detect fixture: purely observational probes of this
// process's own state. It mirrors techniques commercial app-hardening SDKs use
// (debugger/tracer, emulator, Frida/hook, and -- most relevant to an app
// virtualization engine -- container/dual-app detection). It never tries to
// disable, evade, or tamper with anything it finds; it reports.
#include <jni.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <dirent.h>
#include <time.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <string>

static std::string readTracerLine(const char *path) {
    std::string value;
    FILE *f = fopen(path, "r");
    if (!f) return value;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (!strncmp(line, "TracerPid:", 10)) { value = line + 10; break; }
    }
    fclose(f);
    return value;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeTracerPid(JNIEnv *, jclass) {
    std::string value = readTracerLine("/proc/self/status");
    return strtol(value.c_str(), nullptr, 10) != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativePtraceSelf(JNIEnv *, jclass) {
    // Classic self-attach probe in a throwaway forked child so the app's own
    // process is never left traced: the child tries PTRACE_TRACEME (fails only
    // if something already traces it), reports via exit code, then exits.
    pid_t child = fork();
    if (child == 0) {
        long result = ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
        _exit(result == -1 ? 1 : 0);
    }
    if (child < 0) return JNI_FALSE;
    int status = 0;
    if (waitpid(child, &status, 0) != child) return JNI_FALSE;
    return (WIFEXITED(status) && WEXITSTATUS(status) == 1) ? JNI_TRUE : JNI_FALSE;
}

// Scan TracerPid of every thread, not just the group leader: some hooks attach
// to a single worker thread. Returns true if any thread reports a tracer.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeThreadTracerPid(JNIEnv *, jclass) {
    DIR *dir = opendir("/proc/self/task");
    if (!dir) return JNI_FALSE;
    bool traced = false;
    struct dirent *entry;
    while (!traced && (entry = readdir(dir))) {
        if (entry->d_name[0] == '.') continue;
        std::string path = std::string("/proc/self/task/") + entry->d_name + "/status";
        if (strtol(readTracerLine(path.c_str()).c_str(), nullptr, 10) != 0) traced = true;
    }
    closedir(dir);
    return traced ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeMapsHits(JNIEnv *env, jclass) {
    static const char *markers[] = { "frida", "gum-js-loop", "xposed", "substrate", "re.frida.server", "gadget" };
    std::string hits;
    FILE *f = fopen("/proc/self/maps", "r");
    if (f) {
        char line[8192];
        while (fgets(line, sizeof(line), f)) {
            for (const char *marker : markers) {
                if (strstr(line, marker)) {
                    if (hits.find(marker) == std::string::npos) { if (!hits.empty()) hits += ", "; hits += marker; }
                    break;
                }
            }
        }
        fclose(f);
    }
    return env->NewStringUTF(hits.c_str());
}

// Container/virtualization tell: mapped executable code (.so/.dex/.apk/.odex/.oat)
// whose on-disk path belongs to a DIFFERENT package than our own. A virtual-app
// engine runs the guest out of the host's artifact tree, so the host's code
// shows up here even when the process name has been made to look like the guest.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeForeignCodeMaps(JNIEnv *env, jclass, jstring ownPackage) {
    const char *pkg = ownPackage ? env->GetStringUTFChars(ownPackage, nullptr) : nullptr;
    std::string hits;
    FILE *f = fopen("/proc/self/maps", "r");
    if (f) {
        char line[8192];
        while (fgets(line, sizeof(line), f)) {
            // Only executable file-backed regions with a real path.
            const char *path = strchr(line, '/');
            if (!path) continue;
            if (!strstr(line, "r-xp") && !strstr(line, "r--p")) continue;
            bool interesting = strstr(path, ".apk") || strstr(path, ".dex") ||
                               strstr(path, ".odex") || strstr(path, ".oat") || strstr(path, ".vdex");
            if (!interesting) continue;
            bool appTree = strstr(path, "/data/app/") || strstr(path, "/data/user/") || strstr(path, "/data/data/");
            if (!appTree) continue;
            if (pkg && pkg[0] && strstr(path, pkg)) continue; // our own package tree: expected
            std::string entry(path);
            if (!entry.empty() && entry.back() == '\n') entry.pop_back();
            // Collapse to the first ~80 chars to keep the report readable.
            if (entry.size() > 96) entry = entry.substr(0, 96) + "...";
            if (hits.find(entry) == std::string::npos) { if (!hits.empty()) hits += " | "; hits += entry; }
            if (hits.size() > 600) break;
        }
        fclose(f);
    }
    if (pkg) env->ReleaseStringUTFChars(ownPackage, pkg);
    return env->NewStringUTF(hits.c_str());
}

// Bind-mount / overlay / fuse redirection of the app's private dirs is how most
// virtual-app engines graft a per-guest data directory onto the host UID.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeMountRedirect(JNIEnv *env, jclass) {
    std::string hits;
    FILE *f = fopen("/proc/self/mountinfo", "r");
    if (f) {
        char line[8192];
        while (fgets(line, sizeof(line), f)) {
            bool onAppDir = strstr(line, " /data/data/") || strstr(line, " /data/user/") ||
                            strstr(line, " /data/app/") || strstr(line, " /storage/emulated/");
            if (!onAppDir) continue;
            bool redirected = strstr(line, "fuse") || strstr(line, "overlay") ||
                              strstr(line, "sdcardfs") || strstr(line, " - bind");
            if (!redirected) continue;
            // Report just the mount target field (5th space-separated token).
            char *save = nullptr, *copy = strdup(line), *tok = strtok_r(copy, " ", &save);
            for (int i = 0; tok && i < 4; i++) tok = strtok_r(nullptr, " ", &save);
            if (tok) {
                std::string target(tok);
                if (hits.find(target) == std::string::npos) { if (!hits.empty()) hits += ", "; hits += target; }
            }
            free(copy);
            if (hits.size() > 400) break;
        }
        fclose(f);
    }
    return env->NewStringUTF(hits.c_str());
}

// Parent of a normal Android app is zygote/zygote64. A different parent (an
// engine supervisor, app_process launcher, shell) is a strong container signal.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeParentCmdline(JNIEnv *env, jclass) {
    std::string result;
    FILE *stat = fopen("/proc/self/stat", "r");
    long ppid = 0;
    if (stat) {
        // field 4 is ppid; skip comm (which may contain spaces/parens) via last ')'.
        char buf[1024];
        size_t n = fread(buf, 1, sizeof(buf) - 1, stat); buf[n] = 0; fclose(stat);
        char *close = strrchr(buf, ')');
        if (close) sscanf(close + 1, " %*c %ld", &ppid);
    }
    if (ppid > 0) {
        std::string path = "/proc/" + std::to_string(ppid) + "/cmdline";
        FILE *f = fopen(path.c_str(), "r");
        if (f) {
            char buf[512];
            size_t n = fread(buf, 1, sizeof(buf) - 1, f); fclose(f);
            for (size_t i = 0; i < n; i++) if (buf[i] == 0) buf[i] = ' ';
            buf[n] = 0;
            result = buf;
            while (!result.empty() && (result.back() == ' ' || result.back() == '\n')) result.pop_back();
        }
    }
    return env->NewStringUTF(result.c_str());
}

// Emulator system properties -- read through the native property API, which is
// not subject to the non-SDK-interface reflection restrictions.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeEmulatorProps(JNIEnv *env, jclass) {
    static const char *keys[] = {
        "ro.kernel.qemu", "ro.hardware", "ro.product.cpu.abi", "ro.bootloader",
        "ro.boot.hardware", "qemu.hw.mainkeys", "init.svc.qemud", "init.svc.goldfish-setup",
    };
    static const char *badValues[] = { "goldfish", "ranchu", "vbox", "qemu", "ttVM", "nox", "generic" };
    std::string hits;
    char value[PROP_VALUE_MAX];
    for (const char *key : keys) {
        if (__system_property_get(key, value) <= 0) continue;
        if (!strcmp(key, "ro.kernel.qemu") && value[0] == '1') {
            if (!hits.empty()) hits += ", "; hits += "ro.kernel.qemu=1"; continue;
        }
        for (const char *bad : badValues) {
            if (strstr(value, bad)) { if (!hits.empty()) hits += ", "; hits += std::string(key) + "=" + value; break; }
        }
    }
    return env->NewStringUTF(hits.c_str());
}

// Listening Frida control ports (27042/27043 = 0x69A2/0x69A3) seen in the
// kernel's own socket table, rather than a connect() that a proxy could answer.
static bool scanNetFile(const char *path, std::string &found) {
    FILE *f = fopen(path, "r");
    if (!f) return false;
    char line[512];
    fgets(line, sizeof(line), f); // header
    while (fgets(line, sizeof(line), f)) {
        // columns: sl local_address rem_address st ...  st==0A is LISTEN.
        if (strstr(line, ":69A2 ") && strstr(line, " 0A ")) { if (found.find("27042") == std::string::npos) { if (!found.empty()) found += ", "; found += "27042"; } }
        if (strstr(line, ":69A3 ") && strstr(line, " 0A ")) { if (found.find("27043") == std::string::npos) { if (!found.empty()) found += ", "; found += "27043"; } }
    }
    fclose(f);
    return true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeFridaPorts(JNIEnv *env, jclass) {
    std::string found;
    bool readable = false;
    readable |= scanNetFile("/proc/net/tcp", found);
    readable |= scanNetFile("/proc/net/tcp6", found);
    if (!readable) found = "proc-net-unreadable";
    return env->NewStringUTF(found.c_str());
}

// Software breakpoints a debugger/hook plants show up as AArch64 BRK encodings
// (0xD4200000 | imm16<<5) inside our own .text. Scan a sample function.
__attribute__((noinline)) static int sampleTarget(int x) { return x * 2 + 1; }

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeBreakpointScan(JNIEnv *, jclass) {
    volatile int warm = sampleTarget(3); (void)warm;
    const uint32_t *code = reinterpret_cast<const uint32_t *>(&sampleTarget);
    for (int i = 0; i < 24; i++) {
        if ((code[i] & 0xFFE0001Fu) == 0xD4200000u) return JNI_TRUE; // BRK #imm
    }
    return JNI_FALSE;
}

// Wall time vs. this thread's CPU time across a fixed busy loop. Normal: nearly
// equal (ratio ~1). Single-stepping / heavy instrumentation inflates wall time.
extern "C" JNIEXPORT jlong JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeClockDivergence(JNIEnv *, jclass) {
    struct timespec w0, w1, c0, c1;
    clock_gettime(CLOCK_MONOTONIC, &w0);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &c0);
    volatile long sink = 0;
    for (int i = 0; i < 2000000; i++) sink += i;
    clock_gettime(CLOCK_MONOTONIC, &w1);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &c1);
    long wall = (w1.tv_sec - w0.tv_sec) * 1000000000L + (w1.tv_nsec - w0.tv_nsec);
    long cpu = (c1.tv_sec - c0.tv_sec) * 1000000000L + (c1.tv_nsec - c0.tv_nsec);
    if (cpu <= 0) return 100;
    return (wall * 100L) / cpu; // ratio * 100; ~100 is normal
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_tripoder_antidetect_AntiDetectChecks_nativeTimingDeltaNanos(JNIEnv *, jclass) {
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);
    volatile long sink = 0;
    for (int i = 0; i < 200000; i++) sink += i;
    clock_gettime(CLOCK_MONOTONIC, &end);
    return (end.tv_sec - start.tv_sec) * 1000000000L + (end.tv_nsec - start.tv_nsec);
}
