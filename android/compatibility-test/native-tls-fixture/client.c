#include <jni.h>
#include <openssl/ssl.h>
#include <openssl/pem.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/mman.h>

__attribute__((noinline,optnone,visibility("default")))
int owned_buffer_case(uint64_t id,const void *buffer,int capacity,int result){
    (void)id;(void)buffer;(void)capacity;return result;
}
JNIEXPORT void JNICALL Java_dev_tripoder_nativetlsfixture_NativeClient_probe(JNIEnv *env,jclass type){
    (void)type;unsigned char data[64];memset(data,0x41,sizeof(data));
    long page=sysconf(_SC_PAGESIZE);void *mapped=mmap(NULL,(size_t)page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if(mapped==MAP_FAILED||mprotect((char *)mapped+page,(size_t)page,PROT_NONE))goto failed;
    const void *pointers[]={data,data,data,(void *)1,data,data,(char *)mapped+page-2};
    int capacities[]={8,64,1,8,8,8,4},results[]={3,32,2,2,0,-1,4};
    for(unsigned i=0;i<7;i++){
        errno=EAGAIN;int value=owned_buffer_case(i+1,pointers[i],capacities[i],results[i]);
        if(value!=results[i]||errno!=EAGAIN){munmap(mapped,(size_t)page*2);goto failed;}
    }
    munmap(mapped,(size_t)page*2);return;
failed:
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"NATIVE_BUFFER_PROBE_FAILED");
}

/* Own loopback client. Observation hooks the actual linked SSL_read/SSL_write,
 * SSL_new/SSL_free exports, not a fixture telemetry wrapper. */
JNIEXPORT jobjectArray JNICALL Java_dev_tripoder_nativetlsfixture_NativeClient_exchange(JNIEnv *env,jclass type,jint port,jbyteArray caBytes,jbyteArray certBytes,jbyteArray keyBytes,jobjectArray inputs,jintArray responseLengths,jboolean pipelined){
    (void)type;const char *failure="NATIVE_TLS_SETUP";jobjectArray output=NULL;
    SSL_CTX *ctx=NULL;SSL *ssl=NULL;X509 *ca=NULL,*cert=NULL;EVP_PKEY *key=NULL;BIO *bio=NULL;
    int fd=-1;const char *request=NULL;unsigned char *data=NULL;jstring input=NULL;
    jsize messages=(*env)->GetArrayLength(env,inputs);jint lengths[8];
    if(messages<1||messages>8||(*env)->GetArrayLength(env,responseLengths)!=messages)goto done;
    (*env)->GetIntArrayRegion(env,responseLengths,0,messages,lengths);for(jsize i=0;i<messages;i++)if(lengths[i]<1||lengths[i]>4096)goto done;
    ctx=SSL_CTX_new(TLS_client_method());if(!ctx)goto done;
    SSL_CTX_set_min_proto_version(ctx,TLS1_3_VERSION);SSL_CTX_set_max_proto_version(ctx,TLS1_3_VERSION);
    SSL_CTX_set_verify(ctx,SSL_VERIFY_PEER,NULL);
    jbyteArray arrays[]={caBytes,certBytes,keyBytes};
    for(int i=0;i<3;i++){
        jsize n=(*env)->GetArrayLength(env,arrays[i]);if(n<=0||n>16384)goto done;
        data=malloc((size_t)n);if(!data)goto done;(*env)->GetByteArrayRegion(env,arrays[i],0,n,(jbyte *)data);if((*env)->ExceptionCheck(env))goto done;
        if(i<2){bio=BIO_new_mem_buf(data,n);if(!bio)goto done;X509 *value=PEM_read_bio_X509(bio,NULL,NULL,NULL);BIO_free(bio);bio=NULL;if(!value)goto done;if(i==0)ca=value;else cert=value;}
        else{const unsigned char *cursor=data;key=d2i_AutoPrivateKey(NULL,&cursor,n);if(!key)goto done;}
        free(data);data=NULL;
    }
    if(X509_STORE_add_cert(SSL_CTX_get_cert_store(ctx),ca)!=1||SSL_CTX_use_certificate(ctx,cert)!=1||SSL_CTX_use_PrivateKey(ctx,key)!=1||SSL_CTX_check_private_key(ctx)!=1)goto done;
    fd=socket(AF_INET,SOCK_STREAM,0);if(fd<0)goto done;
    struct timeval timeout={5,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    struct sockaddr_in address={.sin_family=AF_INET,.sin_port=htons((uint16_t)port),.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    if(connect(fd,(struct sockaddr *)&address,sizeof(address)))goto done;
    ssl=SSL_new(ctx);if(!ssl||SSL_set_fd(ssl,fd)!=1||SSL_set1_host(ssl,"localhost")!=1)goto done;
    failure="NATIVE_TLS_HANDSHAKE";if(SSL_connect(ssl)!=1||SSL_get_verify_result(ssl)!=X509_V_OK)goto done;
    /* Server waits for our request: this nonblocking read must be retryable and
     * expose no plaintext. The subsequent blocking reads must remain correct. */
    int flags=fcntl(fd,F_GETFL);char pending[61];
    failure="NATIVE_TLS_RETRY";if(flags<0||fcntl(fd,F_SETFL,flags|O_NONBLOCK))goto done;
    int retry=SSL_read(ssl,pending,sizeof(pending));
    if(retry>0||SSL_get_error(ssl,retry)!=SSL_ERROR_WANT_READ||fcntl(fd,F_SETFL,flags))goto done;
    output=(*env)->NewObjectArray(env,messages,(*env)->FindClass(env,"java/lang/String"),NULL);if(!output)goto done;
    for(jsize i=0;i<messages;i++){
        failure="NATIVE_TLS_WRITE";
        if(pipelined?i==0:1)for(jsize index=pipelined?0:i;index<(pipelined?messages:i+1);index++){
            input=(jstring)(*env)->GetObjectArrayElement(env,inputs,index);request=(*env)->GetStringUTFChars(env,input,NULL);if(!request){output=NULL;goto done;}
            size_t size=strlen(request);for(size_t offset=0;offset<size;){int count=(int)(size-offset);if(count>47)count=47;int wrote=SSL_write(ssl,request+offset,count);if(wrote<=0||wrote>count){output=NULL;goto done;}offset+=(size_t)wrote;}
            (*env)->ReleaseStringUTFChars(env,input,request);request=NULL;(*env)->DeleteLocalRef(env,input);input=NULL;
        }
        char response[4097];size_t total=0;failure="NATIVE_TLS_READ";
        while(total<(size_t)lengths[i]){
            int count=lengths[i]-(int)total;if(count>61)count=61;
            int received=SSL_read(ssl,response+total,count);if(received<=0||received>count){output=NULL;goto done;}total+=(size_t)received;
        }
        response[total]=0;jstring received=(*env)->NewStringUTF(env,response);if(!received){output=NULL;goto done;}(*env)->SetObjectArrayElement(env,output,i,received);(*env)->DeleteLocalRef(env,received);
    }
    jclass main=(*env)->FindClass(env,"dev/tripoder/nativetlsfixture/Main");if(!main){output=NULL;goto done;}
    jmethodID ready=(*env)->GetStaticMethodID(env,main,"clientReady","()V");if(!ready){output=NULL;goto done;}(*env)->CallStaticVoidMethod(env,main,ready);if((*env)->ExceptionCheck(env))output=NULL;
done:
    if(request)(*env)->ReleaseStringUTFChars(env,input,request);
    if(ssl)SSL_free(ssl);if(fd>=0)close(fd);if(ctx)SSL_CTX_free(ctx);if(ca)X509_free(ca);if(cert)X509_free(cert);if(key)EVP_PKEY_free(key);if(bio)BIO_free(bio);free(data);
    if(!output&&!(*env)->ExceptionCheck(env))(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),failure);
    return output;
}
