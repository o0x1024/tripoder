#ifndef TRIPODER_PATH_POLICY_H
#define TRIPODER_PATH_POLICY_H
#include <limits.h>
#include <stdbool.h>

enum tp_access { TP_READ, TP_WRITE, TP_METADATA };
struct tp_path { int parent; bool close_parent; bool owned; bool trailing_slash; char leaf[PATH_MAX]; };
int tp_configure(const char *data, const char *data_canonical, const char *artifacts,
                 const char *artifacts_canonical, const char *package);
int tp_resolve(int dirfd, const char *path, enum tp_access access, struct tp_path *out);
void tp_release(struct tp_path *path);
#endif
