#ifndef TRIPODER_PARK_DIAGNOSTIC_H
#define TRIPODER_PARK_DIAGNOSTIC_H
#include <stdbool.h>
#include <stdint.h>
bool tp_park_blocked_mask(int directory, int tid, uint64_t *mask);
#endif
