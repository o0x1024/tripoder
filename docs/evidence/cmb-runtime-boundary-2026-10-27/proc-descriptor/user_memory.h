#ifndef TRIPODER_USER_MEMORY_H
#define TRIPODER_USER_MEMORY_H
#include <stddef.h>
int tp_user_string(const char *source,char *target,size_t capacity);
int tp_user_write(void *target,const void *source,size_t size);
int tp_user_read(const void *source,void *target,size_t size);
#include <stdint.h>
uintptr_t tp_stack_acquire(void);
void tp_stack_release(uintptr_t base);
#endif
