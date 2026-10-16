#include "pac_platform.h"
#include <stdarg.h>
#include <stdio.h>

struct writer { char *data; size_t capacity, used; int failed; };
static void append(struct writer *w, const char *format, ...) {
    if (w->failed) return;
    va_list args; va_start(args, format);
    int size = vsnprintf(w->data + w->used, w->capacity - w->used, format, args);
    va_end(args);
    if (size < 0 || (size_t)size >= w->capacity - w->used) { w->failed = 1; return; }
    w->used += (size_t)size;
}
static void string(struct writer *w, const char *value, size_t limit) {
    append(w, "\"");
    size_t i;
    for (i = 0; i < limit && value[i]; i++) {
        unsigned char c = (unsigned char)value[i];
        if (c == '\\' || c == '"') append(w, "\\%c", c);
        else if (c < 32 || c >= 127) append(w, "\\u%04x", c);
        else append(w, "%c", c);
    }
    if (i == limit) { w->failed = 1; return; }
    append(w, "\"");
}
static const char *value_status(enum tp_pac_value_status value) {
    switch (value) {
        case TP_PAC_VALUE_UNREAD: return "unread";
        case TP_PAC_VALUE_PRESENT: return "present";
        case TP_PAC_VALUE_MISSING: return "missing";
        case TP_PAC_VALUE_ERROR: return "error";
    }
    return "invalid";
}
static void value(struct writer *w, const char *name, const struct tp_pac_value *entry) {
    append(w, "\"%s\":{\"status\":\"%s\",\"errno\":%d,\"valueHex\":", name, value_status(entry->status), entry->error_number);
    if (entry->status == TP_PAC_VALUE_PRESENT) append(w, "\"0x%016llx\"", (unsigned long long)entry->value);
    else append(w, "null");
    append(w, "}");
}
static void id_value(struct writer *w, const char *name, uint64_t value, int observed) {
    append(w, "\"%s\":", name);
    if (observed) append(w, "\"0x%016llx\"", (unsigned long long)value);
    else append(w, "null");
}
size_t tp_pac_platform_json(const struct tp_pac_platform_report *r,
    const struct tp_pac_platform_assessment *a, char *output, size_t capacity) {
    if (output && capacity) output[0] = 0;
    if (!r || !a || !output || !capacity) return 0;
    struct writer w = {output, capacity, 0, 0};
    append(&w, "{\"schema\":%u,\"pointerBits\":%u,\"pid\":%d,\"tid\":%d,\"monotonicNs\":\"%llu\",",
        r->schema, r->pointer_bits, r->pid, r->tid, (unsigned long long)r->monotonic_ns);
    append(&w, "\"clockErrno\":%d,\"cpuBefore\":%d,\"cpuAfter\":%d,\"midrScope\":\"one_read_only_not_all_cpus\",\"kernel\":{\"release\":", r->clock_error, r->cpu_before, r->cpu_after);
    string(&w, r->kernel_release, sizeof(r->kernel_release));
    append(&w, ",\"version\":"); string(&w, r->kernel_version, sizeof(r->kernel_version));
    append(&w, ",\"machine\":"); string(&w, r->machine, sizeof(r->machine));
    append(&w, ",\"errno\":%d,\"contractVerified\":false},\"auxv\":{", r->uname_error);
    value(&w, "hwcap", &r->hwcap); append(&w, ","); value(&w, "hwcap2", &r->hwcap2);
    append(&w, ","); value(&w, "hwcap3", &r->hwcap3); append(&w, ","); value(&w, "hwcap4", &r->hwcap4);
    append(&w, ","); value(&w, "pageSize", &r->page_size);
    append(&w, "},\"prctl\":{\"scope\":\"calling_thread_only\","); value(&w, "pacEnabledKeys", &r->enabled_keys);
    int observed = r->id_status == TP_PAC_IDS_LINUX_USER_VIEW;
    append(&w, "},\"idRegisters\":{\"status\":\"%s\",\"absenceProven\":false,",
        observed ? "linux_user_view" : r->id_status == TP_PAC_IDS_NO_CPUID_ABI ? "no_cpuid_abi" : "unread");
    id_value(&w, "idAa64Isar1", r->isar1, observed); append(&w, ",");
    id_value(&w, "idAa64Isar2", r->isar2, observed); append(&w, ",");
    id_value(&w, "idAa64Isar3", r->isar3, observed); append(&w, ",");
    id_value(&w, "idAa64Pfr1", r->pfr1, observed); append(&w, ",");
    id_value(&w, "midr", r->midr, observed);
    if (observed) append(&w, "},\"fields\":{\"apa\":%u,\"api\":%u,\"apa3\":%u,\"pacm\":%u},",
        a->apa, a->api, a->apa3, a->pacm);
    else append(&w, "},\"fields\":{\"apa\":null,\"api\":null,\"apa3\":null,\"pacm\":null},");
    append(&w, "\"assessment\":\"%s\",\"plannerProfile\":\"unknown\",\"classicProfileAdmitted\":false,\"reasonBits\":%u,\"reasons\":[",
        a->status == TP_PAC_PLATFORM_REJECTED ? "rejected" : "unknown", a->reasons);
    static const char *const names[] = {"not_aarch64", "auxv_unobserved", "address_auth_not_advertised",
        "bti_not_advertised", "cpuid_abi_unavailable", "key_state_unobserved", "apia_key_disabled", "ids_unobserved",
        "pauth_lr_observed", "id_encoding_unknown", "page_size_unsupported", "kernel_contract_unverified", "zero_id_is_not_absence"};
    int comma = 0;
    for (unsigned i = 0; i < sizeof(names) / sizeof(names[0]); i++) if (a->reasons & (1u << i)) {
        append(&w, "%s\"%s\"", comma ? "," : "", names[i]); comma = 1;
    }
    append(&w, "],\"signalHandlersInstalled\":false,\"processSettingsChanged\":false}");
    if (w.failed) { output[0] = 0; return 0; }
    return w.used;
}
