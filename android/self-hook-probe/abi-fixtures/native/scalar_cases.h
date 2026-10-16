#ifndef TRIPODER_SCALAR_CASES_H
#define TRIPODER_SCALAR_CASES_H
#include "scalar_capture.h"
struct tp_scalar_fixture_case {const char *name;struct tp_scalar_signature signature;struct tp_scalar_value values[16];uint64_t return_bits;void (*compiler_call)(struct tp_scalar_return *);void (*compiler_entry)(void (*)(void),struct tp_scalar_return *);};
const struct tp_scalar_fixture_case *tp_scalar_fixture_cases(size_t *count);
#endif
