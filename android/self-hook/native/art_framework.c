#define _GNU_SOURCE
#include "art_access.h"
#include "art_framework_contract.h"
#include "art_storage_contract.h"
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#ifdef __ANDROID__
#include <errno.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>
static FILE *raw_maps_open(void){
    int fd=(int)syscall(SYS_openat,AT_FDCWD,"/proc/self/maps",O_RDONLY|O_CLOEXEC,0);
    if(fd<0)return NULL;
    FILE *maps=fdopen(fd,"r");
    if(!maps){int saved=errno;syscall(SYS_close,fd);errno=saved;}
    return maps;
}
#else
#define raw_maps_open() fopen("/proc/self/maps","r")
#endif

/* Only this registry is exposed. Raw metadata never crosses into Java. The
 * three FastNative calls retain ART's Runnable/shared-mutator transition. Each
 * call resolves at most one member; no I/O or application callback runs there.
 * The measured process uses ART kPointer JNI IDs. This ABI is explicitly gated;
 * index IDs are rejected. The actual JNI encoder/decoder bodies and 16 ordinary
 * JNI members independently calibrate the representation. */
struct string_view { const char *data; size_t size; };
struct range { uintptr_t begin,end; };
static struct {
    struct tp_art_access access;
    jclass classes[87];
    struct range ranges[2048];
    unsigned range_count;
    void *manager;
    uintptr_t (*decode_object)(void *,jobject);
    uintptr_t (*read_root)(const uint32_t *);
    void *(*find_field)(uintptr_t,struct string_view,struct string_view);
    jfieldID (*encode_field)(void *);
    void *(*decode_field)(void *,jfieldID);
    void *(*decode_method)(void *,jmethodID);
} registry;
static _Atomic bool ready;
static _Atomic unsigned calls;
/* Diagnostic only: why resolve_method returned NULL (owner/array, declaring, index, clinit, decode). */
static _Atomic unsigned null_reason[5];
static _Atomic unsigned last_mismatch_index;
static _Atomic uint32_t last_mismatch_declaring;
static _Atomic uintptr_t last_mismatch_klass;
static _Atomic uintptr_t last_mismatch_canonical;
static _Atomic unsigned root_reads,root_adjustments;
static _Atomic bool attempted;
static const char *stage="NOT_STARTED";
static uint32_t fast_flags[3];
static uint32_t id_type=UINT32_MAX;
static const char *last="NONE";
static const char *names[]={"android/app/Application","android/app/ActivityThread","android/app/LoadedApk",
    "android/app/ContextImpl","android/content/pm/ApplicationInfo","android/app/QueuedWork","android/content/ContextWrapper",
    "android/app/ActivityManager","android/net/ConnectivityManager","android/telephony/TelephonyManager",
    "android/media/AudioManager","android/os/BatteryManager","android/os/PowerManager",
    "android/app/Service","android/app/IActivityManager",
    "android/content/BroadcastReceiver","android/content/pm/IPackageManager","android/content/Context$BindServiceFlags",
    "android/content/ContentProvider","android/content/ContentProviderOperation","android/content/ContentProviderOperation$BackReference",
    "android/util/SparseArray","android/net/ConnectivityManager$NetworkCallback","android/app/ApplicationStartInfo",
    "android/content/ContentResolver","android/content/BroadcastReceiver$PendingResult","android/content/ContentResolver$1",
    "android/app/Activity",
    "android/app/ComponentCaller",
    "android/app/Instrumentation",
    "android/content/pm/ActivityInfo",
    "android/view/ViewRootImpl",
    "android/view/ContextThemeWrapper",
    "java/lang/Thread",
    "java/lang/ThreadGroup",
    "java/lang/Runtime",
    "android/app/usage/NetworkStatsManager",
    "android/net/TrafficStats",
    "android/app/NotificationManager",
    "android/app/NotificationChannel",
    "android/app/NotificationChannelGroup",
    "android/app/ApplicationPackageManager",
    "android/content/pm/PackageManager",
    "android/content/pm/PackageItemInfo",
    "android/os/ServiceManager",
    "android/content/pm/ShortcutManager",
    "android/net/wifi/p2p/WifiP2pManager",
    "android/net/wifi/p2p/WifiP2pManager$Channel",
    "android/app/ActivityThread$ActivityClientRecord","android/os/UserHandle",
    "android/app/JobSchedulerImpl","android/app/job/JobInfo","android/app/job/JobParameters","android/app/job/JobService","android/app/job/IJobCallback$Stub$Proxy",
    "android/app/PendingIntent","android/content/IntentSender","android/app/AlarmManager","android/app/ApplicationExitInfo",
    "android/appwidget/AppWidgetManager","android/view/autofill/AutofillClientController","android/hardware/biometrics/BiometricPrompt","android/content/pm/SharedLibraryInfo","android/content/res/Resources","android/os/UserManager","android/util/Xml","android/app/ActivityThread$AppBindData","android/service/notification/StatusBarNotification","com/android/internal/os/ClassLoaderFactory","android/content/res/AssetManager","android/content/res/ApkAssets","android/provider/Settings$System","android/provider/Settings$Secure","android/provider/Settings$Global","android/provider/Settings$Config","android/provider/Settings$NameValueCache","android/provider/Settings$ContentProviderHolder","android/app/ResourcesManager","android/app/ResourcesManager$SharedLibraryAssets","android/app/IntentService","android/net/http/internal/org/chromium/net/NetworkChangeNotifierAutoDetect","android/net/http/internal/org/chromium/net/ProxyBroadcastReceiver","android/net/http/internal/org/chromium/net/ProxyChangeListener$ProxyReceiver","android/app/servertransaction/LaunchActivityItem","android/app/role/RoleManager","dalvik/system/BaseDexClassLoader","android/location/LocationManager"};
_Static_assert(sizeof(names)/sizeof(names[0])==sizeof(registry.classes)/sizeof(registry.classes[0]),"fixed boot class roots");
static const struct { unsigned owner;const char *name,*type; } fields[]={
    {1,"mInitialApplication","Landroid/app/Application;"},{0,"mLoadedApk","Landroid/app/LoadedApk;"},
    {2,"mApplication","Landroid/app/Application;"},{1,"mInstrumentation","Landroid/app/Instrumentation;"},
    {2,"mClassLoader","Ljava/lang/ClassLoader;"},{2,"mDefaultClassLoader","Ljava/lang/ClassLoader;"},
    {2,"mResources","Landroid/content/res/Resources;"},{2,"mAppComponentFactory","Landroid/app/AppComponentFactory;"},
    {2,"mApplicationInfo","Landroid/content/pm/ApplicationInfo;"},{4,"credentialProtectedDataDir","Ljava/lang/String;"},
    {6,"mBase","Landroid/content/Context;"},{1,"sCurrentActivityThread","Landroid/app/ActivityThread;"},
    {13,"mActivityManager","Landroid/app/IActivityManager;"},{13,"mToken","Landroid/os/IBinder;"},
    {19,"mSelectionArgs","Landroid/util/SparseArray;"},{20,"fromIndex","I"},{20,"fromKey","Ljava/lang/String;"},
    {22,"mFlags","I"},{8,"mService","Landroid/net/IConnectivityManager;"},
    {23,"START_TIMESTAMP_LAUNCH","I"},{23,"START_TIMESTAMP_APPLICATION_ONCREATE","I"},
    {46,"mService","Landroid/net/wifi/p2p/IWifiP2pManager;"},{40,"mId","Ljava/lang/String;"},{40,"mChannels","Ljava/util/List;"},
    {29,"mThread","Landroid/app/ActivityThread;"},
    {48,"intent","Landroid/content/Intent;"},
    {48,"token","Landroid/os/IBinder;"},
    {32,"mResources","Landroid/content/res/Resources;"},
    {32,"mTheme","Landroid/content/res/Resources$Theme;"},
    {32,"mThemeResource","I"},
    {27,"mApplication","Landroid/app/Application;"},
    {27,"mComponent","Landroid/content/ComponentName;"},
    {27,"mActivityInfo","Landroid/content/pm/ActivityInfo;"},
    {27,"mResultCode","I"},
    {27,"mResultData","Landroid/content/Intent;"},
    {41,"mPM","Landroid/content/pm/IPackageManager;"},
    {1,"sPackageManager","Landroid/content/pm/IPackageManager;"},
    {44,"sCache","Ljava/util/Map;"},
    {3,"mPackageManager","Landroid/content/pm/PackageManager;"},
    {52,"callback","Landroid/os/IBinder;"},{52,"extras","Landroid/os/PersistableBundle;"},{52,"mJobNamespace","Ljava/lang/String;"},{60,"mActivity","Landroid/app/Activity;"},
    {61,"mContext","Landroid/content/Context;"},{48,"state","Landroid/os/Bundle;"},
    {27,"mSavedInstanceState","Landroid/os/Bundle;"},{64,"mService","Landroid/os/IUserManager;"},
    {1,"mBoundApplication","Landroid/app/ActivityThread$AppBindData;"},
    {66,"appInfo","Landroid/content/pm/ApplicationInfo;"},{66,"info","Landroid/app/LoadedApk;"},
    {66,"processName","Ljava/lang/String;"},{66,"providers","Ljava/util/List;"},{1,"mPackages","Landroid/util/ArrayMap;"},{4,"sharedLibraryInfos","Ljava/util/List;"},
    {3,"mPackageInfo","Landroid/app/LoadedApk;"},{3,"mMainThread","Landroid/app/ActivityThread;"},{41,"mContext","Landroid/app/ContextImpl;"},
    {71,"sNameValueCache","Landroid/provider/Settings$NameValueCache;"},{72,"sNameValueCache","Landroid/provider/Settings$NameValueCache;"},
    {73,"sNameValueCache","Landroid/provider/Settings$NameValueCache;"},{74,"sNameValueCache","Landroid/provider/Settings$NameValueCache;"},
    {75,"mProviderHolder","Landroid/provider/Settings$ContentProviderHolder;"},{76,"mLock","Ljava/lang/Object;"},{76,"mContentProvider","Landroid/content/IContentProvider;"},
    {77,"mLock","Ljava/lang/Object;"},{77,"mSharedLibAssetsMap","Landroid/util/ArrayMap;"},
    {25,"mType","I"},{83,"mIntent","Landroid/content/Intent;"},{83,"mInfo","Landroid/content/pm/ActivityInfo;"},{84,"mService","Landroid/app/role/IRoleManager;"},
    {85,"sharedLibraryLoaders","[Ljava/lang/ClassLoader;"},{85,"sharedLibraryLoadersAfter","[Ljava/lang/ClassLoader;"},
    {86,"mService","Landroid/location/ILocationManager;"}};
static const bool field_static[]={
    false,false,false,false,false,false,false,false,false,false,false,true,
    false,false,false,false,false,false,false,true,true,false,false,false,
    false,false,false,false,false,false,false,false,false,false,false,false,
    true,true,false,false,false,false,false,false,false,false,false,false,
    false,false,false,false,false,false,false,false,false,true,true,true,
    true,false,false,false,false,false,false,false,false,false,false,false,false};
_Static_assert(sizeof(field_static)/sizeof(field_static[0])==sizeof(fields)/sizeof(fields[0]),"fixed field storage kinds");

static bool readable(uintptr_t address,size_t bytes) {
    address&=UINT64_C(0x00ffffffffffffff);
    if(!address||bytes>UINTPTR_MAX-address)return false;
    for(unsigned i=0;i<registry.range_count;i++)if(registry.ranges[i].begin<=address&&address+bytes<=registry.ranges[i].end)return true;
    return false;
}
static uintptr_t owner(int index) {
    if(!atomic_load_explicit(&ready,memory_order_acquire)||index<0||(unsigned)index>=sizeof(names)/sizeof(names[0]))return 0;
    unsigned count=atomic_load_explicit(&calls,memory_order_relaxed);
    do {if(count>=8192)return 0;}while(!atomic_compare_exchange_weak_explicit(&calls,&count,count+1,memory_order_relaxed,memory_order_relaxed));
    void *thread=registry.access.current();
    if(!thread)return 0;
    uintptr_t value=registry.decode_object(thread,registry.classes[index]);
    return readable(value,0x38)?value:0;
}
static uintptr_t method_array(uintptr_t klass,unsigned *count) {
    if(!klass)return 0;
    uintptr_t array=*(uintptr_t *)(klass+0x30);
    if(!readable(array,8))return 0;
    *count=*(uint32_t *)array;
    if(!*count||*count>1024||!readable(array,8+(size_t)*count*32))return 0;
    return array+8;
}
/* ArtMethod/ArtField declaring_class_ is a native GcRoot, not a JNI handle.
 * A rooted JNI Class can already point into to-space while this slot still
 * contains from-space. Resolve the actual slot through ART's pinned read
 * barrier in the same FastNative/Runnable interval as owner(). Do not feed a
 * fabricated jobject to DecodeJObject: its stack-handle path is a raw load. */
static uintptr_t declaring_class(const uint32_t *slot) {
    uint32_t raw=*slot;
    uintptr_t value=registry.read_root(slot);
    atomic_fetch_add_explicit(&root_reads,1,memory_order_relaxed);
    if(value!=raw)atomic_fetch_add_explicit(&root_adjustments,1,memory_order_relaxed);
    return value;
}
static jobject resolve_field(JNIEnv *env,jclass unused,jint slot) {
    (void)unused;
    if(slot<0||(unsigned)slot>=sizeof(fields)/sizeof(fields[0]))return NULL;
    last="FIELD_OWNER";
    uintptr_t klass=owner((int)fields[slot].owner);
    if(!klass)return NULL;
    struct string_view name={fields[slot].name,strlen(fields[slot].name)};
    struct string_view type={fields[slot].type,strlen(fields[slot].type)};
    last="FIELD_METADATA";
    void *field=registry.find_field(klass,name,type);
    bool is_static=field_static[slot];
    if(!readable((uintptr_t)field,16)||declaring_class((const uint32_t *)field)!=klass||(((*((uint32_t *)field+1)&8)!=0)!=is_static))return NULL;
    last="FIELD_ENCODE";
    jfieldID id=registry.encode_field(field);
    last="FIELD_ID_ROUNDTRIP";
    if(!id||registry.decode_field(registry.manager,id)!=field)return NULL;
    last="FIELD_REFLECT";
    return (*env)->ToReflectedField(env,registry.classes[fields[slot].owner],id,is_static?JNI_TRUE:JNI_FALSE);
}
static jint count_methods(JNIEnv *env,jclass unused,jint index) {
    (void)env;(void)unused;
    unsigned count=0;return method_array(owner(index),&count)?(jint)count:-1;
}
static jobject resolve_method(JNIEnv *env,jclass unused,jint index,jint ordinal) {
    (void)unused;
    uintptr_t klass=owner(index);unsigned count=0;
    uintptr_t array=method_array(klass,&count);
    if(!array||ordinal<0||(unsigned)ordinal>=count){atomic_fetch_add(&null_reason[0],1);return NULL;}
    uint32_t *method=(uint32_t *)(array+(size_t)ordinal*32);
    uint32_t flags=method[1];
    // Skip inherited copies, runtime helpers and <clinit>, none of which is a
    // declared Method/Constructor target in this fixed registry.
    uint32_t raw_declaring=method[0];
    uintptr_t declared=declaring_class(method);
    if(declared!=klass){atomic_fetch_add(&null_reason[1],1);atomic_store(&last_mismatch_index,(unsigned)index);atomic_store(&last_mismatch_declaring,raw_declaring);atomic_store(&last_mismatch_canonical,declared);atomic_store(&last_mismatch_klass,klass);return NULL;}
    if(method[2]==UINT32_MAX){atomic_fetch_add(&null_reason[2],1);return NULL;}
    if((flags&UINT32_C(0x10008))==UINT32_C(0x10008)){atomic_fetch_add(&null_reason[3],1);return NULL;}
    // The pinned JNI FindMethodID body returns this pointer for kPointer IDs
    // after canonicalization. Inherited/copied methods were excluded above.
    jmethodID id=(jmethodID)method;
    if(!id||registry.decode_method(registry.manager,id)!=method){atomic_fetch_add(&null_reason[4],1);return NULL;}
    return (*env)->ToReflectedMethod(env,registry.classes[index],id,(flags&8)?JNI_TRUE:JNI_FALSE);
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_FrameworkAccess_prepareNative(JNIEnv *env,jclass type,
    jobject field,jobject count,jobject method) {
    bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true))return JNI_FALSE;
    struct tp_art_method_view views[3];jobject fast[]={field,count,method};
    stage="OWN_METHOD_SNAPSHOT";
    for(unsigned i=0;i<3;i++){
        if(!tp_art_method_snapshot(env,fast[i],&views[i]))return JNI_FALSE;
        fast_flags[i]=views[i].flags;
        if((views[i].flags&UINT32_C(0x108))!=UINT32_C(0x108))return JNI_FALSE;
    }
    stage="BASE_ART_CONTRACT";
    TP_ART_MAPS_SCOPE;
    if(!tp_art_access_bind(&registry.access))return JNI_FALSE;
#define VERIFY(name,NAME) stage=#NAME; if(!tp_art_validate_code(&registry.access,TP_FRAMEWORK_##NAME##_RVA,tp_framework_##name##_bytes,sizeof(tp_framework_##name##_bytes)))return JNI_FALSE
    VERIFY(decode_object,DECODE_OBJECT);VERIFY(find_field,FIND_FIELD);
    VERIFY(encode_field,ENCODE_FIELD);VERIFY(method_id_layout,METHOD_ID_LAYOUT);
    VERIFY(decode_field,DECODE_FIELD);VERIFY(decode_method,DECODE_METHOD);
    VERIFY(method_layout,METHOD_LAYOUT);VERIFY(manager_layout,MANAGER_LAYOUT);
#undef VERIFY
    stage="DECLARING_CLASS_READ_BARRIER";
    if(!tp_art_validate_code(&registry.access,TP_STORAGE_READ_ROOT_RVA,tp_storage_read_root_bytes,sizeof(tp_storage_read_root_bytes))
        ||!tp_art_validate_code(&registry.access,TP_STORAGE_MARK_ROOT_RVA,tp_storage_mark_root_bytes,sizeof(tp_storage_mark_root_bytes)))return JNI_FALSE;
    uintptr_t base=registry.access.base;
    stage="JNI_ID_MANAGER";
    uintptr_t slot=base+TP_FRAMEWORK_RUNTIME_RVA;
    if(!tp_art_readable(slot,8))return JNI_FALSE;
    uintptr_t runtime=*(uintptr_t *)slot;
    if(!runtime||!tp_art_readable(runtime+TP_FRAMEWORK_MANAGER_OFFSET,8))return JNI_FALSE;
    stage="JNI_ID_TYPE";
    if(!tp_art_readable(runtime+TP_FRAMEWORK_ID_TYPE_OFFSET,4))return JNI_FALSE;
    id_type=*(uint32_t *)(runtime+TP_FRAMEWORK_ID_TYPE_OFFSET);
    if(id_type!=0)return JNI_FALSE;
    registry.manager=*(void **)(runtime+TP_FRAMEWORK_MANAGER_OFFSET);
    if(!tp_art_readable((uintptr_t)registry.manager,8))return JNI_FALSE;
    registry.decode_object=(uintptr_t (*)(void *,jobject))(base+TP_FRAMEWORK_DECODE_OBJECT_RVA);
    registry.read_root=(uintptr_t (*)(const uint32_t *))(base+TP_STORAGE_READ_ROOT_RVA);
    registry.find_field=(void *(*)(uintptr_t,struct string_view,struct string_view))(base+TP_FRAMEWORK_FIND_FIELD_RVA);
    registry.encode_field=(jfieldID (*)(void *))(base+TP_FRAMEWORK_ENCODE_FIELD_RVA);
    registry.decode_field=(void *(*)(void *,jfieldID))(base+TP_FRAMEWORK_DECODE_FIELD_RVA);
    registry.decode_method=(void *(*)(void *,jmethodID))(base+TP_FRAMEWORK_DECODE_METHOD_RVA);
    stage="BOOT_CLASS_ROOTS";
    for(unsigned i=0;i<sizeof(names)/sizeof(names[0]);i++){
        jclass local=(*env)->FindClass(env,names[i]);if(!local)return JNI_FALSE;
        registry.classes[i]=(*env)->NewGlobalRef(env,local);(*env)->DeleteLocalRef(env,local);
        if(!registry.classes[i])return JNI_FALSE;
    }
    stage="READABLE_RANGES";
    FILE *maps=raw_maps_open();if(!maps)return JNI_FALSE;
    char line[2048],mode[5];unsigned long start,end;
    while(fgets(line,sizeof(line),maps))if(sscanf(line,"%lx-%lx %4s",&start,&end,mode)==3&&mode[0]=='r'){
        // Validation needs the readable address union, not one slot per VMA.
        // Android splits adjacent boot-image mappings into thousands of VMAs.
        if(registry.range_count&&registry.ranges[registry.range_count-1].end==start){
            registry.ranges[registry.range_count-1].end=end;continue;
        }
        if(registry.range_count==2048){fclose(maps);return JNI_FALSE;}
        registry.ranges[registry.range_count++]=(struct range){start,end};
    }
    fclose(maps);
    JNINativeMethod methods[]={
        {"fieldNative","(I)Ljava/lang/reflect/Field;",(void *)resolve_field},
        {"countNative","(I)I",(void *)count_methods},
        {"methodNative","(II)Ljava/lang/Object;",(void *)resolve_method}};
    stage="REGISTER_OWN_NATIVE";
    if((*env)->RegisterNatives(env,type,methods,3)!=JNI_OK)return JNI_FALSE;
    stage="FAST_NATIVE_FLAGS";
    for(unsigned i=0;i<3;i++){
        if(!tp_art_method_snapshot(env,fast[i],&views[i]))return JNI_FALSE;
        fast_flags[i]=views[i].flags;
        if((views[i].flags&UINT32_C(0x80108))!=UINT32_C(0x80108))return JNI_FALSE;
    }
    atomic_store_explicit(&ready,true,memory_order_release);
    stage="READY";
    return JNI_TRUE;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_FrameworkAccess_statusNative(JNIEnv *env,jclass unused) {
    (void)unused;char text[768];
    snprintf(text,sizeof(text),"{\"stage\":\"%s\",\"last\":\"%s\",\"ready\":%s,\"fastFlags\":[%u,%u,%u],\"calls\":%u,\"readableRanges\":%u,\"jniIdType\":%u,\"resolveNull\":[%u,%u,%u,%u,%u],\"lastMismatch\":[%u,\"%x\",\"%lx\"],\"lastMismatchCanonical\":\"%lx\",\"declaringRootsRead\":%u,\"declaringRootsAdjusted\":%u}",
        stage,last,atomic_load(&ready)?"true":"false",fast_flags[0],fast_flags[1],fast_flags[2],atomic_load(&calls),registry.range_count,id_type,
        atomic_load(&null_reason[0]),atomic_load(&null_reason[1]),atomic_load(&null_reason[2]),atomic_load(&null_reason[3]),atomic_load(&null_reason[4]),
        atomic_load(&last_mismatch_index),(unsigned)atomic_load(&last_mismatch_declaring),(unsigned long)atomic_load(&last_mismatch_klass),
        (unsigned long)atomic_load(&last_mismatch_canonical),atomic_load(&root_reads),atomic_load(&root_adjustments));
    return (*env)->NewStringUTF(env,text);
}
