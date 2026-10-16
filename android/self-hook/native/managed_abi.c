#include "managed_abi.h"
#include <string.h>
static struct {jclass type;jmethodID unbox,box;} primitives[8];
static bool ready;
static const char kinds[]="ZBSCIJFD";
bool tp_managed_abi_bind(JNIEnv *env){
    if(ready)return true;
    const char *types[]={"java/lang/Boolean","java/lang/Byte","java/lang/Short","java/lang/Character","java/lang/Integer","java/lang/Long","java/lang/Float","java/lang/Double"};
    const char *names[]={"booleanValue","byteValue","shortValue","charValue","intValue","longValue","floatValue","doubleValue"};
    const char *unbox[]={"()Z","()B","()S","()C","()I","()J","()F","()D"};
    const char *box[]={"(Z)Ljava/lang/Boolean;","(B)Ljava/lang/Byte;","(S)Ljava/lang/Short;","(C)Ljava/lang/Character;","(I)Ljava/lang/Integer;","(J)Ljava/lang/Long;","(F)Ljava/lang/Float;","(D)Ljava/lang/Double;"};
    for(unsigned i=0;i<8;i++){
        jclass local=(*env)->FindClass(env,types[i]);if(!local)return false;
        primitives[i].type=(*env)->NewGlobalRef(env,local);(*env)->DeleteLocalRef(env,local);
        if(!primitives[i].type)return false;
        primitives[i].unbox=(*env)->GetMethodID(env,primitives[i].type,names[i],unbox[i]);
        primitives[i].box=(*env)->GetStaticMethodID(env,primitives[i].type,"valueOf",box[i]);
        if(!primitives[i].unbox||!primitives[i].box)return false;
    }
    ready=true;return true;
}
static void invalid(JNIEnv *env){jclass cls=(*env)->FindClass(env,"java/lang/IllegalArgumentException");if(cls)(*env)->ThrowNew(env,cls,"NATIVE_ABI_ARGUMENT");}
jobject tp_managed_abi_original(JNIEnv *env,jclass owner,jmethodID original,bool instance,const char *shorty,jobject receiver,jobjectArray incoming){
    size_t count=strlen(shorty)-1;
    if(!ready||count>32||!incoming||(*env)->GetArrayLength(env,incoming)!=(jsize)count||
        (instance?(!receiver||!(*env)->IsInstanceOf(env,receiver,owner)):receiver!=NULL)){invalid(env);return NULL;}
    jvalue args[32]={{0}};jobject refs[32]={0};jvalue result={0};
    for(unsigned i=0;i<count;i++){
        refs[i]=(*env)->GetObjectArrayElement(env,incoming,(jsize)i);char kind=shorty[i+1];
        if((*env)->ExceptionCheck(env))goto done;
        if(kind=='L'){args[i].l=refs[i];continue;}
        const char *type=strchr(kinds,kind);
        if(!type||!refs[i]){invalid(env);goto done;}
        unsigned k=(unsigned)(type-kinds);
        if(!(*env)->IsInstanceOf(env,refs[i],primitives[k].type)){invalid(env);goto done;}
#define UNBOX(letter,Member,Type) case letter:args[i].Member=(*env)->Call##Type##Method(env,refs[i],primitives[k].unbox);break
        switch(kind){UNBOX('Z',z,Boolean);UNBOX('B',b,Byte);UNBOX('S',s,Short);UNBOX('C',c,Char);UNBOX('I',i,Int);UNBOX('J',j,Long);UNBOX('F',f,Float);UNBOX('D',d,Double);}
#undef UNBOX
        if((*env)->ExceptionCheck(env))goto done;
    }
#define INVOKE(letter,Member,Type) case letter:result.Member=instance?(*env)->CallNonvirtual##Type##MethodA(env,receiver,owner,original,args):(*env)->CallStatic##Type##MethodA(env,owner,original,args);break
    switch(shorty[0]){
        INVOKE('L',l,Object);INVOKE('Z',z,Boolean);INVOKE('B',b,Byte);INVOKE('S',s,Short);INVOKE('C',c,Char);
        INVOKE('I',i,Int);INVOKE('J',j,Long);INVOKE('F',f,Float);INVOKE('D',d,Double);
        case 'V':if(instance)(*env)->CallNonvirtualVoidMethodA(env,receiver,owner,original,args);else(*env)->CallStaticVoidMethodA(env,owner,original,args);break;
        default:invalid(env);
    }
#undef INVOKE
done:
    for(unsigned i=0;i<count;i++)if(refs[i])(*env)->DeleteLocalRef(env,refs[i]);
    if((*env)->ExceptionCheck(env)||shorty[0]=='V')return NULL;
    if(shorty[0]=='L')return result.l;
    unsigned k=(unsigned)(strchr(kinds,shorty[0])-kinds);
    return (*env)->CallStaticObjectMethodA(env,primitives[k].type,primitives[k].box,&result);
}
