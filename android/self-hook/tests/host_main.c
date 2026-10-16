#include "core_checks.h"
#include <stdio.h>
int main(void) {
    struct tp_check_result result = tp_core_checks();
    printf("{\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"liveCodePatched\":false}\n",
           result.checks, result.failures, result.first_failure == NULL ? "" : result.first_failure);
    return result.failures == 0 ? 0 : 1;
}
