// Owned native service. It is written against the public NDK contract (<android/native_service.h>) only and
// reports what a client can independently verify: the process it really runs in and the callbacks it really gets.
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <android/log.h>
#include <android/native_service.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define TAG "TripoderNativeEcho"
enum { REPORT = FIRST_CALL_TRANSACTION, ECHO, COUNTERS };
static AIBinder_Class *klass;
static AIBinder *binder;
static int created, binds, unbinds, rebinds, destroys, trims;
static char last_action[160];
static uint64_t last_token;

static void *class_create(void *args) { return args; }
static void class_destroy(void *user) { (void)user; }

static binder_status_t transact(AIBinder *self, transaction_code_t code, const AParcel *in, AParcel *out) {
    (void)self;
    char text[512];
    if (code == REPORT) {
        snprintf(text, sizeof text, "uid=%d pid=%d action=%s token=%llu", getuid(), getpid(), last_action, (unsigned long long)last_token);
        return AParcel_writeString(out, text, (int32_t)strlen(text));
    }
    if (code == ECHO) {
        int32_t value = 0;
        binder_status_t status = AParcel_readInt32(in, &value);
        if (status != STATUS_OK) return status;
        return AParcel_writeInt32(out, value + 1);
    }
    if (code == COUNTERS) {
        snprintf(text, sizeof text, "created=%d binds=%d unbinds=%d rebinds=%d destroys=%d trims=%d", created, binds, unbinds, rebinds, destroys, trims);
        return AParcel_writeString(out, text, (int32_t)strlen(text));
    }
    return STATUS_UNKNOWN_TRANSACTION;
}

static AIBinder *on_bind(ANativeService *service, uint64_t token, const char *action, const char *data) {
    (void)service; (void)data;
    binds++;
    last_token = token;
    snprintf(last_action, sizeof last_action, "%s", action ? action : "(none)");
    __android_log_print(ANDROID_LOG_INFO, TAG, "onBind token=%llu action=%s uid=%d", (unsigned long long)token, last_action, getuid());
    if (!binder) binder = AIBinder_new(klass, NULL);
    AIBinder_incStrong(binder);  // ownership of this reference passes to the caller
    return binder;
}
static bool on_unbind(ANativeService *service, uint64_t token) { (void)service; (void)token; unbinds++; __android_log_print(ANDROID_LOG_INFO, TAG, "onUnbind"); return true; }
static void on_rebind(ANativeService *service, uint64_t token) { (void)service; (void)token; rebinds++; __android_log_print(ANDROID_LOG_INFO, TAG, "onRebind"); }
static void on_destroy(ANativeService *service) { (void)service; destroys++; __android_log_print(ANDROID_LOG_INFO, TAG, "onDestroy"); }
static void on_trim(ANativeService *service, ANativeServiceTrimMemoryLevel level) { (void)service; (void)level; trims++; }

void NativeEcho_onCreate(ANativeService *service) {
    created++;
    __android_log_print(ANDROID_LOG_INFO, TAG, "onCreate uid=%d pid=%d", getuid(), getpid());
    klass = AIBinder_Class_define("dev.tripoder.nativeservicefixture.Echo", class_create, class_destroy, transact);
    ANativeService_setOnBindCallback(service, on_bind);
    ANativeService_setOnUnbindCallback(service, on_unbind);
    ANativeService_setOnRebindCallback(service, on_rebind);
    ANativeService_setOnDestroyCallback(service, on_destroy);
    ANativeService_setOnTrimMemoryCallback(service, on_trim);
}

// The platform's default entry-point name, used by the service that names no property.
void ANativeService_onCreate(ANativeService *service) { NativeEcho_onCreate(service); }
