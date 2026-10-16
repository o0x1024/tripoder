#ifndef TP_PROBE_MODULE_CONCURRENCY_H
#define TP_PROBE_MODULE_CONCURRENCY_H
#include <stdbool.h>
struct tp_module_concurrency_report {unsigned threads,cycles,errors;bool removed;};
struct tp_module_concurrency_report tp_probe_module_concurrency(const char *path);
#endif
