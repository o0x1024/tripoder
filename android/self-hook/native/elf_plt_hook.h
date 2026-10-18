#ifndef TRIPODER_ELF_PLT_HOOK_H
#define TRIPODER_ELF_PLT_HOOK_H

#include <elf.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

int tp_elf_plt_find_slot(uintptr_t base, const Elf64_Phdr *phdr, uint16_t phnum,
                         const char *symbol_name, uintptr_t *slot_addr_out);
int tp_elf_plt_patch_slot(uintptr_t slot_addr, uintptr_t new_target, uintptr_t *old_target_out);
int tp_elf_plt_restore_slot(uintptr_t slot_addr, uintptr_t expected_target, uintptr_t original_target);

#endif
