#ifndef TP_PROBE_MODULE_INSTANCES_H
#define TP_PROBE_MODULE_INSTANCES_H
struct tp_module_instances {unsigned checks,failures;const char *first;};
struct tp_module_instances tp_probe_module_instances(const char *path);
#endif
