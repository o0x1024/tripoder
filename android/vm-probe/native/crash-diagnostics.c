/* Read-only fault evidence; restore Android's handler and re-fault normally. */
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <ucontext.h>

static struct sigaction previous_ill;
static int maps_fd = -1;
static uintptr_t code_begin, code_end;

void tripoder_crash_diagnostics_code_region(void *base, size_t size) {
    code_begin = (uintptr_t)base;
    code_end = code_begin + size;
    fprintf(stderr, "TRIPODER_TCG_CODE_REGION base=%p size=%zu\n", base, size);
}

static void literal(const char *text, size_t size) {
    while (size) {
        ssize_t count = write(STDERR_FILENO, text, size);
        if (count <= 0) return;
        text += count;
        size -= (size_t)count;
    }
}

#define MESSAGE(text) literal(text, sizeof(text) - 1)

static void number(uint64_t value) {
    static const char digits[] = "0123456789abcdef";
    char line[] = "0x0000000000000000\n";
    for (unsigned i = 0; i < 16; ++i) {
        line[17 - i] = digits[value & 15];
        value >>= 4;
    }
    literal(line, sizeof(line) - 1);
}

static void illegal_instruction(int signal, siginfo_t *info, void *context) {
    /* Do not resume, emulate, skip, or weaken the faulting instruction. */
    sigaction(signal, &previous_ill, NULL);
    ucontext_t *state = context;
    uintptr_t pc = state->uc_mcontext.pc;
    MESSAGE("TRIPODER_SIGILL code="); number((uint64_t)info->si_code);
    MESSAGE("pc="); number(pc);
    MESSAGE("sp="); number(state->uc_mcontext.sp);
    MESSAGE("pstate="); number(state->uc_mcontext.pstate);
    MESSAGE("registers x0..x30:\n");
    for (unsigned i = 0; i < 31; ++i) number(state->uc_mcontext.regs[i]);
    /* Only inspect our recorded RWX TCG allocation, on the faulting page.
     * Do not probe arbitrary addresses or protected Android process memory. */
    uintptr_t begin = pc & ~(uintptr_t)511;
    size_t count = begin >= code_begin && begin + 512 <= code_end ? 512 : 0;
    MESSAGE("instruction_window_begin="); number(begin);
    MESSAGE("instruction_window_bytes="); number((uint64_t)count);
    if (count > 0) {
        volatile const uint32_t *words = (const uint32_t *)begin;
        for (size_t i = 0; i < count / sizeof(*words); ++i) number(words[i]);
    }
    MESSAGE("TRIPODER_PROCESS_MAPS_BEGIN\n");
    char buffer[4096];
    ssize_t bytes;
    if (lseek(maps_fd, 0, SEEK_SET) >= 0) {
        while ((bytes = read(maps_fd, buffer, sizeof(buffer))) > 0) literal(buffer, (size_t)bytes);
    }
    MESSAGE("TRIPODER_PROCESS_MAPS_END\n");
    /* Returning repeats the original synchronous fault with Android's handler. */
}

void tripoder_crash_diagnostics_init(void) {
    maps_fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    fprintf(stderr, "TRIPODER_FAULT_DIAGNOSTICS own_tcg_code=1 maps=%d\n", maps_fd >= 0);
    struct sigaction action = {0};
    action.sa_sigaction = illegal_instruction;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGILL, &action, &previous_ill) != 0) abort();
}
