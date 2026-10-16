#ifndef TRIPODER_CORE_CHECKS_H
#define TRIPODER_CORE_CHECKS_H
struct tp_check_result { unsigned checks; unsigned failures; const char *first_failure; };
struct tp_check_result tp_core_checks(void);
#endif
