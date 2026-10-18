#define _GNU_SOURCE
#include "elf_plt_hook.h"
#include <errno.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/mman.h>

#ifndef R_AARCH64_GLOB_DAT
#define R_AARCH64_GLOB_DAT 1025
#endif
#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT 1026
#endif

static uintptr_t resolve_dyn_ptr(uintptr_t base, uintptr_t raw) {
    if (raw == 0) return 0;
    return raw >= base ? raw : base + raw;
}

static bool search_rela_table(uintptr_t base, const Elf64_Rela *rela, size_t rela_bytes,
                              const Elf64_Sym *symtab, const char *strtab, size_t strsz,
                              const char *symbol_name, uintptr_t *slot_addr_out) {
    if (!rela || rela_bytes < sizeof(Elf64_Rela) || (rela_bytes % sizeof(Elf64_Rela)) != 0) return false;
    size_t count = rela_bytes / sizeof(Elf64_Rela);
    size_t wanted_len = strlen(symbol_name);
    for (size_t i = 0; i < count; i++) {
        uint32_t type = (uint32_t)ELF64_R_TYPE(rela[i].r_info);
        uint32_t sym_idx = (uint32_t)ELF64_R_SYM(rela[i].r_info);
        if ((type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) || sym_idx == 0) continue;
        const Elf64_Sym *sym = &symtab[sym_idx];
        if (sym->st_name == 0 || (size_t)sym->st_name + wanted_len >= strsz) continue;
        const char *candidate = strtab + sym->st_name;
        if (memcmp(candidate, symbol_name, wanted_len) == 0 && candidate[wanted_len] == '\0') {
            *slot_addr_out = base + rela[i].r_offset;
            return true;
        }
    }
    return false;
}

int tp_elf_plt_find_slot(uintptr_t base, const Elf64_Phdr *phdr, uint16_t phnum,
                         const char *symbol_name, uintptr_t *slot_addr_out) {
    if (!base || !phdr || !phnum || !symbol_name || !*symbol_name || !slot_addr_out) return -EINVAL;
    const Elf64_Dyn *dyn = NULL;
    size_t dyn_count = 0;
    for (uint16_t i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC && phdr[i].p_memsz >= sizeof(Elf64_Dyn)) {
            dyn = (const Elf64_Dyn *)(base + phdr[i].p_vaddr);
            dyn_count = phdr[i].p_memsz / sizeof(Elf64_Dyn);
            break;
        }
    }
    if (!dyn || dyn_count == 0) return -ENOENT;
    const Elf64_Sym *symtab = NULL;
    const char *strtab = NULL;
    size_t strsz = 0, syment = sizeof(Elf64_Sym);
    const Elf64_Rela *jmprel = NULL, *rela = NULL;
    size_t pltrelsz = 0, relasz = 0, relaent = sizeof(Elf64_Rela);
    int64_t pltrel_type = DT_RELA;

    for (size_t i = 0; i < dyn_count && dyn[i].d_tag != DT_NULL; i++) {
        switch (dyn[i].d_tag) {
            case DT_SYMTAB: symtab = (const Elf64_Sym *)resolve_dyn_ptr(base, (uintptr_t)dyn[i].d_un.d_ptr); break;
            case DT_STRTAB: strtab = (const char *)resolve_dyn_ptr(base, (uintptr_t)dyn[i].d_un.d_ptr); break;
            case DT_STRSZ: strsz = (size_t)dyn[i].d_un.d_val; break;
            case DT_SYMENT: syment = (size_t)dyn[i].d_un.d_val; break;
            case DT_JMPREL: jmprel = (const Elf64_Rela *)resolve_dyn_ptr(base, (uintptr_t)dyn[i].d_un.d_ptr); break;
            case DT_PLTRELSZ: pltrelsz = (size_t)dyn[i].d_un.d_val; break;
            case DT_PLTREL: pltrel_type = (int64_t)dyn[i].d_un.d_val; break;
            case DT_RELA: rela = (const Elf64_Rela *)resolve_dyn_ptr(base, (uintptr_t)dyn[i].d_un.d_ptr); break;
            case DT_RELASZ: relasz = (size_t)dyn[i].d_un.d_val; break;
            case DT_RELAENT: relaent = (size_t)dyn[i].d_un.d_val; break;
            default: break;
        }
    }
    if (!symtab || !strtab || strsz == 0 || syment != sizeof(Elf64_Sym) || relaent != sizeof(Elf64_Rela)) return -EINVAL;
    if (pltrel_type == DT_RELA && search_rela_table(base, jmprel, pltrelsz, symtab, strtab, strsz, symbol_name, slot_addr_out)) return 0;
    if (search_rela_table(base, rela, relasz, symtab, strtab, strsz, symbol_name, slot_addr_out)) return 0;
    return -ENOENT;
}

int tp_elf_plt_patch_slot(uintptr_t slot_addr, uintptr_t new_target, uintptr_t *old_target_out) {
    if (!slot_addr || (slot_addr & 7) != 0 || !new_target || !old_target_out) return -EINVAL;
    uintptr_t page = slot_addr & ~(uintptr_t)4095;
    if (mprotect((void *)page, 4096, PROT_READ | PROT_WRITE) != 0) return -errno;
    _Atomic uintptr_t *slot = (_Atomic uintptr_t *)slot_addr;
    *old_target_out = atomic_exchange_explicit(slot, new_target, memory_order_acq_rel);
    __builtin___clear_cache((char *)slot_addr, (char *)(slot_addr + sizeof(uintptr_t)));
    (void)mprotect((void *)page, 4096, PROT_READ);
    return 0;
}

int tp_elf_plt_restore_slot(uintptr_t slot_addr, uintptr_t expected_target, uintptr_t original_target) {
    if (!slot_addr || (slot_addr & 7) != 0 || !expected_target || !original_target) return -EINVAL;
    uintptr_t page = slot_addr & ~(uintptr_t)4095;
    if (mprotect((void *)page, 4096, PROT_READ | PROT_WRITE) != 0) return -errno;
    _Atomic uintptr_t *slot = (_Atomic uintptr_t *)slot_addr;
    uintptr_t expected = expected_target;
    bool ok = atomic_compare_exchange_strong_explicit(slot, &expected, original_target,
                                                      memory_order_acq_rel, memory_order_acquire);
    __builtin___clear_cache((char *)slot_addr, (char *)(slot_addr + sizeof(uintptr_t)));
    (void)mprotect((void *)page, 4096, PROT_READ);
    return ok ? 0 : -ESTALE;
}
