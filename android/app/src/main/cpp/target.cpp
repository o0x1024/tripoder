// Intentionally independent of the agent. Frida must patch this function's code.
extern "C" __attribute__((visibility("default"), noinline))
int retry_delay_ms(int base) {
    return base + 7;
}
