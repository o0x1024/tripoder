#include "art_access.h"
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
static jobject guest;static struct tp_art_access access;
static _Atomic unsigned calls,errors;
static const uint64_t expected_words[32]={UINT64_C(0xfedcba9876543210),UINT64_C(0xfdb97530eca86421),UINT64_C(0xfb72ea61d950c843),UINT64_C(0xf6e5d4c3b2a19087),UINT64_C(0xedcba9876543210f),UINT64_C(0xdb97530eca86421f),UINT64_C(0xb72ea61d950c843f),UINT64_C(0x6e5d4c3b2a19087f),UINT64_C(0xdcba9876543210fe),UINT64_C(0xb97530eca86421fd),UINT64_C(0x72ea61d950c843fb),UINT64_C(0xe5d4c3b2a19087f6),UINT64_C(0xcba9876543210fed),UINT64_C(0x97530eca86421fdb),UINT64_C(0x2ea61d950c843fb7),UINT64_C(0x5d4c3b2a19087f6e),UINT64_C(0xba9876543210fedc),UINT64_C(0x7530eca86421fdb9),UINT64_C(0xea61d950c843fb72),UINT64_C(0xd4c3b2a19087f6e5),UINT64_C(0xa9876543210fedcb),UINT64_C(0x530eca86421fdb97),UINT64_C(0xa61d950c843fb72e),UINT64_C(0x4c3b2a19087f6e5d),UINT64_C(0x9876543210fedcba),UINT64_C(0x30eca86421fdb975),UINT64_C(0x61d950c843fb72ea),UINT64_C(0xc3b2a19087f6e5d4),UINT64_C(0x876543210fedcba9),UINT64_C(0xeca86421fdb9753),UINT64_C(0x1d950c843fb72ea6),UINT64_C(0x3b2a19087f6e5d4c)};
static jlong result(const uint64_t *values,unsigned variant){uint64_t hash=0;if(memcmp(values,expected_words,sizeof(expected_words)))atomic_fetch_add(&errors,1);for(unsigned i=0;i<32;i++)hash=((hash<<3)|(hash>>61))^values[i];hash+=variant*100;atomic_fetch_add(&calls,1);jlong value;memcpy(&value,&hash,8);return value;}
static void state(JNIEnv *env,jclass owner,bool fast){
    void *thread=access.current();bool runnable=thread&&(atomic_load_explicit((_Atomic uint32_t *)thread,memory_order_relaxed)>>24)==0;
    jclass found=(*env)->FindClass(env,"dev/tripoder/selfhookprobe/jniguest/JniSdkTarget");
    if(runnable!=fast||!found||!(*env)->IsSameObject(env,found,guest)||!(*env)->IsSameObject(env,owner,guest))atomic_fetch_add(&errors,1);
    if(found)(*env)->DeleteLocalRef(env,found);
}
static jlong values1(JNIEnv *env,jclass owner,jlong p0,jlong p1,jlong p2,jlong p3,jlong p4,jlong p5,jlong p6,jlong p7,jlong p8,jlong p9,jlong p10,jlong p11,jlong p12,jlong p13,jlong p14,jlong p15,jlong p16,jlong p17,jlong p18,jlong p19,jlong p20,jlong p21,jlong p22,jlong p23,jlong p24,jlong p25,jlong p26,jlong p27,jlong p28,jlong p29,jlong p30,jlong p31){uint64_t values[32]={(uint64_t)p0,(uint64_t)p1,(uint64_t)p2,(uint64_t)p3,(uint64_t)p4,(uint64_t)p5,(uint64_t)p6,(uint64_t)p7,(uint64_t)p8,(uint64_t)p9,(uint64_t)p10,(uint64_t)p11,(uint64_t)p12,(uint64_t)p13,(uint64_t)p14,(uint64_t)p15,(uint64_t)p16,(uint64_t)p17,(uint64_t)p18,(uint64_t)p19,(uint64_t)p20,(uint64_t)p21,(uint64_t)p22,(uint64_t)p23,(uint64_t)p24,(uint64_t)p25,(uint64_t)p26,(uint64_t)p27,(uint64_t)p28,(uint64_t)p29,(uint64_t)p30,(uint64_t)p31};state(env,owner,false);return result(values,1);}
static jlong values2(JNIEnv *env,jclass owner,jlong p0,jlong p1,jlong p2,jlong p3,jlong p4,jlong p5,jlong p6,jlong p7,jlong p8,jlong p9,jlong p10,jlong p11,jlong p12,jlong p13,jlong p14,jlong p15,jlong p16,jlong p17,jlong p18,jlong p19,jlong p20,jlong p21,jlong p22,jlong p23,jlong p24,jlong p25,jlong p26,jlong p27,jlong p28,jlong p29,jlong p30,jlong p31){uint64_t values[32]={(uint64_t)p0,(uint64_t)p1,(uint64_t)p2,(uint64_t)p3,(uint64_t)p4,(uint64_t)p5,(uint64_t)p6,(uint64_t)p7,(uint64_t)p8,(uint64_t)p9,(uint64_t)p10,(uint64_t)p11,(uint64_t)p12,(uint64_t)p13,(uint64_t)p14,(uint64_t)p15,(uint64_t)p16,(uint64_t)p17,(uint64_t)p18,(uint64_t)p19,(uint64_t)p20,(uint64_t)p21,(uint64_t)p22,(uint64_t)p23,(uint64_t)p24,(uint64_t)p25,(uint64_t)p26,(uint64_t)p27,(uint64_t)p28,(uint64_t)p29,(uint64_t)p30,(uint64_t)p31};state(env,owner,false);return result(values,2);}
static jlong fast1(JNIEnv *env,jclass owner,jlong p0,jlong p1,jlong p2,jlong p3,jlong p4,jlong p5,jlong p6,jlong p7,jlong p8,jlong p9,jlong p10,jlong p11,jlong p12,jlong p13,jlong p14,jlong p15,jlong p16,jlong p17,jlong p18,jlong p19,jlong p20,jlong p21,jlong p22,jlong p23,jlong p24,jlong p25,jlong p26,jlong p27,jlong p28,jlong p29,jlong p30,jlong p31){uint64_t values[32]={(uint64_t)p0,(uint64_t)p1,(uint64_t)p2,(uint64_t)p3,(uint64_t)p4,(uint64_t)p5,(uint64_t)p6,(uint64_t)p7,(uint64_t)p8,(uint64_t)p9,(uint64_t)p10,(uint64_t)p11,(uint64_t)p12,(uint64_t)p13,(uint64_t)p14,(uint64_t)p15,(uint64_t)p16,(uint64_t)p17,(uint64_t)p18,(uint64_t)p19,(uint64_t)p20,(uint64_t)p21,(uint64_t)p22,(uint64_t)p23,(uint64_t)p24,(uint64_t)p25,(uint64_t)p26,(uint64_t)p27,(uint64_t)p28,(uint64_t)p29,(uint64_t)p30,(uint64_t)p31};state(env,owner,true);return result(values,1);}
static jlong fast2(JNIEnv *env,jclass owner,jlong p0,jlong p1,jlong p2,jlong p3,jlong p4,jlong p5,jlong p6,jlong p7,jlong p8,jlong p9,jlong p10,jlong p11,jlong p12,jlong p13,jlong p14,jlong p15,jlong p16,jlong p17,jlong p18,jlong p19,jlong p20,jlong p21,jlong p22,jlong p23,jlong p24,jlong p25,jlong p26,jlong p27,jlong p28,jlong p29,jlong p30,jlong p31){uint64_t values[32]={(uint64_t)p0,(uint64_t)p1,(uint64_t)p2,(uint64_t)p3,(uint64_t)p4,(uint64_t)p5,(uint64_t)p6,(uint64_t)p7,(uint64_t)p8,(uint64_t)p9,(uint64_t)p10,(uint64_t)p11,(uint64_t)p12,(uint64_t)p13,(uint64_t)p14,(uint64_t)p15,(uint64_t)p16,(uint64_t)p17,(uint64_t)p18,(uint64_t)p19,(uint64_t)p20,(uint64_t)p21,(uint64_t)p22,(uint64_t)p23,(uint64_t)p24,(uint64_t)p25,(uint64_t)p26,(uint64_t)p27,(uint64_t)p28,(uint64_t)p29,(uint64_t)p30,(uint64_t)p31};state(env,owner,true);return result(values,2);}
static jlong critical1(jlong p0,jlong p1,jlong p2,jlong p3,jlong p4,jlong p5,jlong p6,jlong p7,jlong p8,jlong p9,jlong p10,jlong p11,jlong p12,jlong p13,jlong p14,jlong p15,jlong p16,jlong p17,jlong p18,jlong p19,jlong p20,jlong p21,jlong p22,jlong p23,jlong p24,jlong p25,jlong p26,jlong p27,jlong p28,jlong p29,jlong p30,jlong p31){uint64_t values[32]={(uint64_t)p0,(uint64_t)p1,(uint64_t)p2,(uint64_t)p3,(uint64_t)p4,(uint64_t)p5,(uint64_t)p6,(uint64_t)p7,(uint64_t)p8,(uint64_t)p9,(uint64_t)p10,(uint64_t)p11,(uint64_t)p12,(uint64_t)p13,(uint64_t)p14,(uint64_t)p15,(uint64_t)p16,(uint64_t)p17,(uint64_t)p18,(uint64_t)p19,(uint64_t)p20,(uint64_t)p21,(uint64_t)p22,(uint64_t)p23,(uint64_t)p24,(uint64_t)p25,(uint64_t)p26,(uint64_t)p27,(uint64_t)p28,(uint64_t)p29,(uint64_t)p30,(uint64_t)p31};void *thread=access.current();if(!thread||(atomic_load_explicit((_Atomic uint32_t *)thread,memory_order_relaxed)>>24)!=0)atomic_fetch_add(&errors,1);return result(values,1);}
static jlong critical2(jlong p0,jlong p1,jlong p2,jlong p3,jlong p4,jlong p5,jlong p6,jlong p7,jlong p8,jlong p9,jlong p10,jlong p11,jlong p12,jlong p13,jlong p14,jlong p15,jlong p16,jlong p17,jlong p18,jlong p19,jlong p20,jlong p21,jlong p22,jlong p23,jlong p24,jlong p25,jlong p26,jlong p27,jlong p28,jlong p29,jlong p30,jlong p31){uint64_t values[32]={(uint64_t)p0,(uint64_t)p1,(uint64_t)p2,(uint64_t)p3,(uint64_t)p4,(uint64_t)p5,(uint64_t)p6,(uint64_t)p7,(uint64_t)p8,(uint64_t)p9,(uint64_t)p10,(uint64_t)p11,(uint64_t)p12,(uint64_t)p13,(uint64_t)p14,(uint64_t)p15,(uint64_t)p16,(uint64_t)p17,(uint64_t)p18,(uint64_t)p19,(uint64_t)p20,(uint64_t)p21,(uint64_t)p22,(uint64_t)p23,(uint64_t)p24,(uint64_t)p25,(uint64_t)p26,(uint64_t)p27,(uint64_t)p28,(uint64_t)p29,(uint64_t)p30,(uint64_t)p31};void *thread=access.current();if(!thread||(atomic_load_explicit((_Atomic uint32_t *)thread,memory_order_relaxed)>>24)!=0)atomic_fetch_add(&errors,1);return result(values,2);}
static jdouble mixed(jdouble d0,jlong p0,jdouble d1,jlong p1,jdouble d2,jlong p2,jdouble d3,jlong p3,jdouble d4,jlong p4,jdouble d5,jlong p5,jdouble d6,jlong p6,jdouble d7,jlong p7,jdouble d8,jlong p8,jdouble d9,jlong p9,jdouble d10,jlong p10,jdouble d11,jlong p11,jdouble d12,jlong p12,jdouble d13,jlong p13,jdouble d14,jlong p14,jdouble d15,jlong p15){
    uint64_t d0bits;memcpy(&d0bits,&d0,8);if(d0bits!=UINT64_C(0x3ff0000000000000)||(uint64_t)p0!=UINT64_C(0xfedcba9876543210))atomic_fetch_add(&errors,1);
    uint64_t d1bits;memcpy(&d1bits,&d1,8);if(d1bits!=UINT64_C(0x3ff0000000000001)||(uint64_t)p1!=UINT64_C(0xfdb97530eca86421))atomic_fetch_add(&errors,1);
    uint64_t d2bits;memcpy(&d2bits,&d2,8);if(d2bits!=UINT64_C(0x3ff0000000000002)||(uint64_t)p2!=UINT64_C(0xfb72ea61d950c843))atomic_fetch_add(&errors,1);
    uint64_t d3bits;memcpy(&d3bits,&d3,8);if(d3bits!=UINT64_C(0x3ff0000000000003)||(uint64_t)p3!=UINT64_C(0xf6e5d4c3b2a19087))atomic_fetch_add(&errors,1);
    uint64_t d4bits;memcpy(&d4bits,&d4,8);if(d4bits!=UINT64_C(0x3ff0000000000004)||(uint64_t)p4!=UINT64_C(0xedcba9876543210f))atomic_fetch_add(&errors,1);
    uint64_t d5bits;memcpy(&d5bits,&d5,8);if(d5bits!=UINT64_C(0x3ff0000000000005)||(uint64_t)p5!=UINT64_C(0xdb97530eca86421f))atomic_fetch_add(&errors,1);
    uint64_t d6bits;memcpy(&d6bits,&d6,8);if(d6bits!=UINT64_C(0x3ff0000000000006)||(uint64_t)p6!=UINT64_C(0xb72ea61d950c843f))atomic_fetch_add(&errors,1);
    uint64_t d7bits;memcpy(&d7bits,&d7,8);if(d7bits!=UINT64_C(0x3ff0000000000007)||(uint64_t)p7!=UINT64_C(0x6e5d4c3b2a19087f))atomic_fetch_add(&errors,1);
    uint64_t d8bits;memcpy(&d8bits,&d8,8);if(d8bits!=UINT64_C(0x3ff0000000000008)||(uint64_t)p8!=UINT64_C(0xdcba9876543210fe))atomic_fetch_add(&errors,1);
    uint64_t d9bits;memcpy(&d9bits,&d9,8);if(d9bits!=UINT64_C(0x3ff0000000000009)||(uint64_t)p9!=UINT64_C(0xb97530eca86421fd))atomic_fetch_add(&errors,1);
    uint64_t d10bits;memcpy(&d10bits,&d10,8);if(d10bits!=UINT64_C(0x3ff000000000000a)||(uint64_t)p10!=UINT64_C(0x72ea61d950c843fb))atomic_fetch_add(&errors,1);
    uint64_t d11bits;memcpy(&d11bits,&d11,8);if(d11bits!=UINT64_C(0x3ff000000000000b)||(uint64_t)p11!=UINT64_C(0xe5d4c3b2a19087f6))atomic_fetch_add(&errors,1);
    uint64_t d12bits;memcpy(&d12bits,&d12,8);if(d12bits!=UINT64_C(0x3ff000000000000c)||(uint64_t)p12!=UINT64_C(0xcba9876543210fed))atomic_fetch_add(&errors,1);
    uint64_t d13bits;memcpy(&d13bits,&d13,8);if(d13bits!=UINT64_C(0x3ff000000000000d)||(uint64_t)p13!=UINT64_C(0x97530eca86421fdb))atomic_fetch_add(&errors,1);
    uint64_t d14bits;memcpy(&d14bits,&d14,8);if(d14bits!=UINT64_C(0x3ff000000000000e)||(uint64_t)p14!=UINT64_C(0x2ea61d950c843fb7))atomic_fetch_add(&errors,1);
    uint64_t d15bits;memcpy(&d15bits,&d15,8);if(d15bits!=UINT64_C(0x3ff000000000000f)||(uint64_t)p15!=UINT64_C(0x5d4c3b2a19087f6e))atomic_fetch_add(&errors,1);
    atomic_fetch_add(&calls,1);return -0.0;
}
static jstring text(JNIEnv *env,jobject receiver,jstring value,jdouble fraction,jlong exact,jobjectArray references){
    (void)receiver;jclass found=(*env)->FindClass(env,"dev/tripoder/selfhookprobe/jniguest/JniSdkTarget");
    if(!found||!(*env)->IsSameObject(env,found,guest)||fraction!=-0.0||exact!=INT64_MIN||!references||(*env)->GetArrayLength(env,references)!=2)atomic_fetch_add(&errors,1);
    return value;
}
static void fail(JNIEnv *env,jclass owner){jfieldID id=(*env)->GetStaticFieldID(env,owner,"FAILURE","Ljava/lang/IllegalStateException;");jthrowable failure=id?(*env)->GetStaticObjectField(env,owner,id):NULL;if(failure)(*env)->Throw(env,failure);}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_JniNamedTarget_named(JNIEnv *env,jclass owner,jint value){(void)env;(void)owner;return value+13;}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_JniSdkChecks_bind(JNIEnv *env,jclass cls,jclass owner,jint variant){
    (void)cls;if(!tp_art_access_bind(&access)||variant<0||variant>2)return JNI_FALSE;
    if(!guest)guest=(*env)->NewGlobalRef(env,owner);if(!guest||!(*env)->IsSameObject(env,guest,owner))return JNI_FALSE;
    if(!variant)return (*env)->UnregisterNatives(env,owner)==JNI_OK;
    JNINativeMethod entries[]={
        {"values","(JJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJ)J",variant==1?(void *)values1:(void *)values2},
        {"fast","(JJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJ)J",variant==1?(void *)fast1:(void *)fast2},
        {"critical","(JJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJJ)J",variant==1?(void *)critical1:(void *)critical2},
        {"mixed","(DJDJDJDJDJDJDJDJDJDJDJDJDJDJDJDJ)D",(void *)mixed},{"text","(Ljava/lang/String;DJ[Ljava/lang/Object;)Ljava/lang/String;",(void *)text},{"fail","()V",(void *)fail}};
    return (*env)->RegisterNatives(env,owner,entries,6)==JNI_OK;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_JniSdkChecks_nativeReport(JNIEnv *env,jclass cls){(void)cls;char text[128];snprintf(text,sizeof(text),"{\"calls\":%u,\"errors\":%u}",atomic_load(&calls),atomic_load(&errors));return (*env)->NewStringUTF(env,text);}

static jobject product_guest;
static jint product_integer(JNIEnv *env,jclass owner,jint value){
    jclass found=(*env)->FindClass(env,"dev/tripoder/selfhookprobe/OwnedProductJavaTarget");
    if(!found||!(*env)->IsSameObject(env,found,product_guest)||!(*env)->IsSameObject(env,owner,product_guest)){
        jclass error=(*env)->FindClass(env,"java/lang/AssertionError");if(error)(*env)->ThrowNew(env,error,"PRODUCT_NATIVE_ORIGINAL_LOADER");return 0;}
    return value+11;
}
static jfloat product_float(JNIEnv *env,jclass owner,jfloat value){(void)env;(void)owner;return value;}
static jdouble product_double(jdouble value){return value;}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_runtime_OwnedJavaBackendChecks_bindNative(JNIEnv *env,jclass cls,jclass owner){
    (void)cls;if(product_guest)return JNI_FALSE;product_guest=(*env)->NewGlobalRef(env,owner);if(!product_guest)return JNI_FALSE;
    JNINativeMethod entries[]={{"nativeValue","(I)I",(void *)product_integer},{"nativeFloat","(F)F",(void *)product_float},{"nativeDouble","(D)D",(void *)product_double}};
    return (*env)->RegisterNatives(env,owner,entries,3)==JNI_OK;
}
