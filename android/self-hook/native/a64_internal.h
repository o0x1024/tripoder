#ifndef TRIPODER_A64_INTERNAL_H
#define TRIPODER_A64_INTERNAL_H
#include "tripoder_a64.h"
enum tp_reference_kind { TP_REF_NONE, TP_REF_BRANCH, TP_REF_ADDRESS, TP_REF_LITERAL };
struct tp_reference { enum tp_reference_kind kind; uint64_t address; uint32_t bytes; };
enum tp_a64_status tp_a64_reference(uint32_t instruction, uint64_t pc, struct tp_reference *result);
#endif
