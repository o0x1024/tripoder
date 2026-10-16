#ifndef TRIPODER_SCALAR_ABI_H
#define TRIPODER_SCALAR_ABI_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#define TP_SCALAR_MAX_ARGS 16u
#define TP_SCALAR_STACK_SLOTS 8u
enum tp_scalar_type {TP_SCALAR_VOID=0,TP_SCALAR_I32=1,TP_SCALAR_U32=2,TP_SCALAR_I64=3,TP_SCALAR_U64=4,TP_SCALAR_F32=5,TP_SCALAR_F64=6};
enum tp_scalar_bank {TP_SCALAR_GPR=0,TP_SCALAR_FPR=1,TP_SCALAR_STACK=2};
enum tp_scalar_error {TP_SCALAR_OK=0,TP_SCALAR_NULL,TP_SCALAR_SIGNATURE,TP_SCALAR_BUDGET,TP_SCALAR_VALUE,TP_SCALAR_TARGET};
/* No variadic, aggregate, vector, pointer, hidden-result or unspecified-signature mode exists. */
struct tp_scalar_signature {uint32_t flags;uint32_t count;enum tp_scalar_type result;enum tp_scalar_type arguments[TP_SCALAR_MAX_ARGS];};
struct tp_scalar_location {uint8_t bank,index,width,reserved;};
struct tp_scalar_layout {uint32_t count,stack_size;struct tp_scalar_location arguments[TP_SCALAR_MAX_ARGS];struct tp_scalar_location result;};
/* Canonical raw representations: low 32 bits for I32/U32/F32; exact 64 bits for I64/U64/F64. */
struct tp_scalar_value {enum tp_scalar_type type;uint64_t bits;};
/* Fixed assembly contract, asserted by the C implementation. Stack padding is deterministic zero. */
struct tp_scalar_frame {uint64_t gpr[8],fpr[8],stack[TP_SCALAR_STACK_SLOTS];uint32_t stack_size,reserved;};
struct tp_scalar_return {uint64_t gpr,fpr;};
struct tp_scalar_result {struct tp_scalar_value value;int error_number;};
enum tp_scalar_error tp_scalar_plan(const struct tp_scalar_signature *signature,struct tp_scalar_layout *output);
enum tp_scalar_error tp_scalar_pack(const struct tp_scalar_signature *signature,const struct tp_scalar_value *values,struct tp_scalar_frame *output);
enum tp_scalar_error tp_scalar_capture(const struct tp_scalar_signature *signature,const struct tp_scalar_frame *frame,struct tp_scalar_value *output);
enum tp_scalar_error tp_scalar_result(const struct tp_scalar_signature *signature,const struct tp_scalar_return *raw,struct tp_scalar_value *output);
/* Caller supplies a retained, independently attested AArch64 function entry. No address discovery or patching. */
enum tp_scalar_error tp_scalar_call(const struct tp_scalar_signature *signature,void (*target)(void),const struct tp_scalar_value *values,int incoming_errno,struct tp_scalar_result *output);
/* Internal AArch64 entry. Do not call directly with an unvalidated frame/stack_size. */
void tp_scalar_call_frame(void (*target)(void),const struct tp_scalar_frame *frame,struct tp_scalar_return *output);

#endif
