#include "tripoder_a64.h"
#include "a64_internal.h"
#include <stdbool.h>
#include <string.h>

static bool aligned(uint64_t address) { return (address & 3) == 0; }
static int64_t extend(uint32_t value, unsigned bits) {
    uint32_t sign = UINT32_C(1) << (bits - 1);
    return (int64_t)(value ^ sign) - sign;
}
static bool add_offset(uint64_t base, int64_t offset, uint64_t *result) {
    if (offset >= 0) {
        if (UINT64_MAX - base < (uint64_t)offset) return false;
        *result = base + (uint64_t)offset;
    } else {
        uint64_t magnitude = (uint64_t)(-(offset + 1)) + 1;
        if (base < magnitude) return false;
        *result = base - magnitude;
    }
    return true;
}
enum tp_a64_status tp_a64_reference(uint32_t instruction, uint64_t pc, struct tp_reference *result) {
    struct tp_reference ref = {TP_REF_NONE, 0, 0};
    unsigned bits = 0, field_shift = 5;
    if ((instruction & 0xfc000000) == 0x14000000) { bits = 26; field_shift = 0; }
    else if (((instruction & 0xff000010) == 0x54000000 && (instruction & 15) < 14) ||
             (instruction & 0x7e000000) == 0x34000000) bits = 19;
    else if ((instruction & 0x7e000000) == 0x36000000) bits = 14;
    int64_t offset = 0;
    if (bits != 0) {
        ref.kind = TP_REF_BRANCH; ref.bytes = 4;
        offset = extend((instruction >> field_shift) & ((UINT32_C(1) << bits) - 1), bits) * 4;
    } else if ((instruction & 0x9f000000) == 0x10000000) {
        ref.kind = TP_REF_ADDRESS; ref.bytes = 1;
        offset = extend((((instruction >> 5) & 0x7ffff) << 2) | ((instruction >> 29) & 3), 21);
    } else if ((instruction & 0x3f000000) == 0x18000000 && (instruction >> 30) != 3) {
        ref.kind = TP_REF_LITERAL; ref.bytes = (instruction >> 30) == 1 ? 8 : 4;
        offset = extend((instruction >> 5) & 0x7ffff, 19) * 4;
    }
    /* ADRP preserves a page base only. Subsequent address uses require separate
     * caller proof and are deliberately not inferred from adjacent instructions. */
    if (ref.kind != TP_REF_NONE && (!add_offset(pc, offset, &ref.address) ||
        ref.address > UINT64_MAX - ref.bytes)) return TP_A64_OVERFLOW;
    *result = ref;
    return TP_A64_OK;
}
/* Computes a signed scaled displacement without signed subtraction overflow. */
static enum tp_a64_status displacement(uint64_t from, uint64_t to,
                                       unsigned bits, unsigned shift, uint32_t *result) {
    uint64_t distance = to >= from ? to - from : from - to;
    uint64_t unit = UINT64_C(1) << shift;
    if ((distance & (unit - 1)) != 0) return TP_A64_ALIGNMENT;
    uint64_t magnitude = distance >> shift;
    uint64_t limit = UINT64_C(1) << (bits - 1);
    if (magnitude > limit || (to >= from && magnitude == limit)) return TP_A64_RANGE;
    uint64_t encoded = to >= from ? magnitude : (UINT64_C(1) << bits) - magnitude;
    *result = (uint32_t)(encoded & ((UINT64_C(1) << bits) - 1));
    return TP_A64_OK;
}
enum tp_a64_status tp_a64_branch(uint64_t from, uint64_t to, uint32_t *word) {
    if (word == NULL) return TP_A64_ARGUMENT;
    if (!aligned(from) || !aligned(to)) return TP_A64_ALIGNMENT;
    uint32_t immediate;
    enum tp_a64_status status = displacement(from, to, 26, 2, &immediate);
    if (status != TP_A64_OK) return status;
    *word = UINT32_C(0x14000000) | immediate;
    return TP_A64_OK;
}
static enum tp_a64_status relative_branch(uint32_t instruction, unsigned bits,
                                         unsigned field_shift, uint64_t pc, uint64_t at,
                                         uint64_t source, uint64_t end, uint64_t destination,
                                         uint32_t *output) {
    uint32_t mask = (UINT32_C(1) << bits) - 1;
    int64_t offset = extend((instruction >> field_shift) & mask, bits) * 4;
    uint64_t target;
    if (!add_offset(pc, offset, &target)) return TP_A64_OVERFLOW;
    if (target >= source && target < end) target = destination + (target - source);
    uint32_t immediate;
    enum tp_a64_status status = displacement(at, target, bits, 2, &immediate);
    if (status != TP_A64_OK) return status;
    *output = (instruction & ~(mask << field_shift)) | (immediate << field_shift);
    return TP_A64_OK;
}
static enum tp_a64_status relocate_one(uint32_t instruction, uint64_t pc, uint64_t at,
                                      uint64_t source, uint64_t end, uint64_t destination,
                                      uint32_t *output) {
    if (instruction == TP_A64_NOP || instruction == TP_A64_BTI_C ||
        instruction == TP_A64_BTI_J || instruction == TP_A64_BTI_JC ||
        /* A normal LR return exits the original trampoline directly; its
         * appended B is unreachable. No non-LR/authenticated return admitted. */
        instruction == UINT32_C(0xd65f03c0) ||
        /* MOVN/MOVZ/MOVK (opc=1 reserved), logical shifted-register and
         * ADD/SUB shifted-register. Register 31 is ZR, not SP, in these forms. */
        ((instruction & UINT32_C(0x1f800000)) == UINT32_C(0x12800000) &&
         ((instruction >> 29) & 3) != 1) ||
        (instruction & UINT32_C(0x1f000000)) == UINT32_C(0x0a000000) ||
        (instruction & UINT32_C(0x1f200000)) == UINT32_C(0x0b000000) ||
        /* Scalar FP and Advanced SIMD data processing: no memory, PC, SP or
         * implicit LR operand. No SVE/SME/unknown system instruction class. */
        (instruction & UINT32_C(0x7f000000)) == UINT32_C(0x1e000000) ||
        (instruction & UINT32_C(0x9f000000)) == UINT32_C(0x0e000000) ||
        (instruction & UINT32_C(0x9f000000)) == UINT32_C(0x0f000000) ||
        /* Unsigned-offset load/store, including SIMD: no base write-back. */
        (instruction & UINT32_C(0x3b000000)) == UINT32_C(0x39000000) ||
        /* ADD/SUB immediate, fixed bit 23 == 0. No PC or implicit LR operand. */
        (instruction & UINT32_C(0x1f800000)) == UINT32_C(0x11000000)) {
        *output = instruction;
        return TP_A64_OK;
    }
    if ((instruction & UINT32_C(0xfc000000)) == UINT32_C(0x14000000))
        return relative_branch(instruction, 26, 0, pc, at, source, end, destination, output);
    if ((instruction & UINT32_C(0xff000010)) == UINT32_C(0x54000000) &&
        (instruction & 15) < 14)
        return relative_branch(instruction, 19, 5, pc, at, source, end, destination, output);
    if ((instruction & UINT32_C(0x7e000000)) == UINT32_C(0x34000000))
        return relative_branch(instruction, 19, 5, pc, at, source, end, destination, output);
    if ((instruction & UINT32_C(0x7e000000)) == UINT32_C(0x36000000))
        return relative_branch(instruction, 14, 5, pc, at, source, end, destination, output);
    uint32_t address_kind = instruction & UINT32_C(0x9f000000);
    if (address_kind == UINT32_C(0x10000000) || address_kind == UINT32_C(0x90000000)) {
        bool page = address_kind == UINT32_C(0x90000000);
        uint32_t encoded = (((instruction >> 5) & UINT32_C(0x7ffff)) << 2) |
                           ((instruction >> 29) & 3);
        int64_t offset = extend(encoded, 21) * (page ? 4096 : 1);
        uint64_t base = page ? pc & ~UINT64_C(4095) : pc, target;
        if (!add_offset(base, offset, &target)) return TP_A64_OVERFLOW;
        if (!page && target >= source && target < end) return TP_A64_SELF_REFERENCE;
        uint32_t immediate;
        enum tp_a64_status status = displacement(page ? at & ~UINT64_C(4095) : at,
                                                  target, 21, page ? 12 : 0, &immediate);
        if (status != TP_A64_OK) return status;
        *output = (instruction & ~UINT32_C(0x60ffffe0)) |
                  ((immediate & 3) << 29) | ((immediate >> 2) << 5);
        return TP_A64_OK;
    }
    /* Only scalar LDR W/X and LDRSW. No SIMD literal or PRFM. */
    if ((instruction & UINT32_C(0x3f000000)) == UINT32_C(0x18000000) &&
        (instruction >> 30) != 3) {
        uint64_t target;
        int64_t offset = extend((instruction >> 5) & UINT32_C(0x7ffff), 19) * 4;
        if (!add_offset(pc, offset, &target)) return TP_A64_OVERFLOW;
        uint64_t bytes = (instruction >> 30) == 1 ? 8 : 4;
        if (target > UINT64_MAX - bytes) return TP_A64_OVERFLOW;
        if (target < end && target + bytes > source) return TP_A64_SELF_REFERENCE;
        uint32_t immediate;
        enum tp_a64_status status = displacement(at, target, 19, 2, &immediate);
        if (status != TP_A64_OK) return status;
        *output = (instruction & ~UINT32_C(0x00ffffe0)) | (immediate << 5);
        return TP_A64_OK;
    }
    return TP_A64_UNSUPPORTED;
}
enum tp_a64_status tp_a64_relocate(const uint32_t *input, size_t count,
                                  uint64_t source, uint64_t destination,
                                  uint32_t *output) {
    if (input == NULL || output == NULL || count == 0 || count > TP_A64_MAX_WORDS)
        return TP_A64_ARGUMENT;
    if (!aligned(source) || !aligned(destination)) return TP_A64_ALIGNMENT;
    uint64_t bytes = count * 4;
    if (source > UINT64_MAX - bytes || destination > UINT64_MAX - bytes)
        return TP_A64_OVERFLOW;
    if (source != destination && source < destination + bytes && destination < source + bytes)
        return TP_A64_OVERLAP;
    uint32_t temporary[TP_A64_MAX_WORDS];
    for (size_t i = 0; i < count; i++) {
        struct tp_reference ref;
        enum tp_a64_status checked = tp_a64_reference(input[i], source + i * 4, &ref);
        if (checked != TP_A64_OK) return checked;
        bool internal = ref.kind == TP_REF_BRANCH && ref.address >= source && ref.address < source + bytes;
        if (source != destination && ref.kind != TP_REF_NONE && !internal &&
            ref.address < destination + bytes && ref.address + ref.bytes > destination)
            return TP_A64_SELF_REFERENCE;
        enum tp_a64_status status = relocate_one(input[i], source + i * 4, destination + i * 4,
                                                 source, source + bytes, destination, &temporary[i]);
        if (status != TP_A64_OK) return status;
    }
    memcpy(output, temporary, count * sizeof(uint32_t));
    return TP_A64_OK;
}
