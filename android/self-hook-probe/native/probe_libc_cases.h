#ifndef TRIPODER_PROBE_LIBC_CASES_H
#define TRIPODER_PROBE_LIBC_CASES_H
#include <stdint.h>
struct tp_libc_case_result { unsigned checks, failures; const char *first; };
void tp_probe_libc_cases(uintptr_t base,const char *logical_directory,const char *artifact_directory,
                         const char *foreign_owned_path,struct tp_libc_case_result *result);
#endif
