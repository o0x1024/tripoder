#ifndef TRIPODER_PATH_POLICY_H
#define TRIPODER_PATH_POLICY_H
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <sys/types.h>

enum tp_access { TP_READ, TP_WRITE, TP_METADATA };
struct tp_path { int parent; bool close_parent; bool owned; bool trailing_slash; char leaf[PATH_MAX]; };
int tp_configure(const char *data, const char *data_canonical, const char *artifacts,
                 const char *artifacts_canonical, const char *package);
int tp_project_module(const char *path,uintptr_t base);
int tp_configure_host_storage(const char *data,const char *canonical,const char *device);
int tp_configure_process(const char *process_name);
/* Set once before publishing the libc adapters. Logical data/identity routing
 * remains active; thin skips deep procfs concealment. */
void tp_configure_thin(bool thin);
int tp_resolve(int dirfd, const char *path, enum tp_access access, struct tp_path *out);
int tp_open_proc(const char *path, int flags);
ssize_t tp_unresolve(const char *physical, size_t physical_len, char *out, size_t size);
void tp_release(struct tp_path *path);
#endif
