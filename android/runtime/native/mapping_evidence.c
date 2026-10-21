#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define TP_MAP_LIMIT (2u*1024u*1024u)
static bool utf8(const unsigned char *s,size_t length){
    for(size_t i=0;i<length;){
        unsigned a=s[i++];if(a<0x80)continue;
        unsigned count=a>=0xc2&&a<=0xdf?1:a>=0xe0&&a<=0xef?2:a>=0xf0&&a<=0xf4?3:0;
        if(!count||count>length-i)return false;
        unsigned b=s[i];if((b&0xc0)!=0x80||(a==0xe0&&b<0xa0)||(a==0xed&&b>=0xa0)||(a==0xf0&&b<0x90)||(a==0xf4&&b>=0x90))return false;
        for(unsigned j=0;j<count;j++)if((s[i++]&0xc0)!=0x80)return false;
    }return true;
}
static bool number(const unsigned char **at,const unsigned char *end,bool hex,unsigned char delimiter){
    const unsigned char *start=*at;
    while(*at<end){unsigned c=**at;if(!(c>='0'&&c<='9')&&!(hex&&c>='a'&&c<='f'))break;(*at)++;}
    if(*at==start||*at>=end||**at!=delimiter)return false;(*at)++;return true;
}
static bool token(const unsigned char *text,size_t length,const char *word){
    size_t n=strlen(word);if(length<n)return false;
    for(size_t i=0;i<=length-n;i++){
        size_t j=0;for(;j<n;j++){unsigned c=text[i+j];if(c>='A'&&c<='Z')c+='a'-'A';if(c!=(unsigned char)word[j])break;}
        if(j==n)return true;
    }return false;
}
static bool suffix(const unsigned char *text,size_t length,const char *tail){size_t n=strlen(tail);return length>=n&&!memcmp(text+length-n,tail,n);}

/* Validate every complete raw row before selecting diagnostics. Full-byte
 * hashing and core inode/device/permission checks remain with the caller. */
int tp_mapping_evidence_rows(const unsigned char *input,size_t length,unsigned char *output,size_t *used,unsigned *rows){
    if(!input||!output||!used||!rows||!length||length>TP_MAP_LIMIT||input[length-1]!='\n')return 1;
    if(!utf8(input,length))return 2;
    const unsigned char *at=input,*end=input+length;*used=0;*rows=0;
    while(at<end){
        const unsigned char *start=at,*limit=memchr(at,'\n',(size_t)(end-at));if(!limit)return 1;
        if(!number(&at,limit,true,'-')||!number(&at,limit,true,' '))return 1;
        if(limit-at<5||(at[0]!='r'&&at[0]!='-')||(at[1]!='w'&&at[1]!='-')||(at[2]!='x'&&at[2]!='-')||(at[3]!='p'&&at[3]!='s')||at[4]!=' ')return 1;
        at+=5;if(!number(&at,limit,true,' ')||!number(&at,limit,true,':')||!number(&at,limit,true,' '))return 1;
        const unsigned char *inode=at;while(at<limit&&*at>='0'&&*at<='9')at++;
        if(at==inode||(at<limit&&*at!=' '))return 1;
        while(at<limit&&*at==' ')at++;
        bool non_ascii=false;for(const unsigned char *p=at;p<limit;p++){if(*p=='\r')return 1;if(*p>=0x80)non_ascii=true;}
        size_t name=(size_t)(limit-at);
        // Retain every non-ASCII name for Java's exact Unicode case/line rules.
        bool relevant=non_ascii||suffix(at,name,".so")||suffix(at,name,".so (deleted)")||token(at,name,"frida")||token(at,name,"gadget")||token(at,name,"tripoderio");
        if(relevant){size_t bytes=(size_t)(limit+1-start);memcpy(output+*used,start,bytes);*used+=bytes;}
        (*rows)++;at=limit+1;
    }return 0;
}

#ifndef TP_MAPPING_ROWS_HOST
#include <jni.h>
#include <stdlib.h>
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_runtime_RuntimeNativeMappingEvidence_relevantRowsNative(JNIEnv *env,jclass type,jbyteArray input,jlongArray counts){
    (void)type;jsize length=input?(*env)->GetArrayLength(env,input):0;
    if(length<1||(unsigned)length>TP_MAP_LIMIT||!counts||(*env)->GetArrayLength(env,counts)!=1)return NULL;
    jbyte *bytes=(*env)->GetByteArrayElements(env,input,NULL);if(!bytes)return NULL;
    unsigned char *selected=malloc((size_t)length);size_t used=0;unsigned rows=0;
    int status=selected?tp_mapping_evidence_rows((const unsigned char *)bytes,(size_t)length,selected,&used,&rows):3;
    (*env)->ReleaseByteArrayElements(env,input,bytes,JNI_ABORT);
    if(status){free(selected);jclass error=(*env)->FindClass(env,status==3?"java/lang/OutOfMemoryError":"java/lang/SecurityException");if(error)(*env)->ThrowNew(env,error,status==2?"RUNTIME_MAPS_UTF8":"RUNTIME_MAPS_ROW");return NULL;}
    jbyteArray result=(*env)->NewByteArray(env,(jsize)used);
    if(result){(*env)->SetByteArrayRegion(env,result,0,(jsize)used,(const jbyte *)selected);jlong total=rows;(*env)->SetLongArrayRegion(env,counts,0,1,&total);}
    free(selected);return result;
}
#endif
