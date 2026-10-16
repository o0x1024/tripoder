#include "loaded_class_buffer.hpp"
#include <cstdio>
#include <cstdlib>
#include <cstring>

static unsigned checks;
static void check(bool condition, const char *name) {
    checks++;
    if (!condition) { std::fprintf(stderr, "FAIL:%s\n", name); std::exit(1); }
}
extern "C" __attribute__((noinline)) bool tp_loaded_visit_abi(tp_loaded::ClassVisitorAbi *visitor, tp_loaded::ClassRef ref) {
    return (*visitor)(ref);
}
int main() {
    auto *scope = static_cast<tp_loaded::RootScope *>(std::calloc(1, sizeof(tp_loaded::RootScope)));
    check(scope != nullptr, "owned root buffer allocation");
    scope->capacity = 3;
    tp_loaded::BoundedVisitor visitor(*scope);
    check(tp_loaded_visit_abi(&visitor, {0x1000}), "virtual callback with one-word ObjPtr");
    check(tp_loaded_visit_abi(&visitor, {0x2000}) && tp_loaded_visit_abi(&visitor, {0x3000}), "exact budget retained");
    check(scope->size == 3 && visitor.seen == 3 && !visitor.exhausted, "exact budget does not imply truncation");
    check(!tp_loaded_visit_abi(&visitor, {0x4000}) && visitor.exhausted && scope->size == 3 && visitor.seen == 4,
        "budget plus one stops and preserves lower bound");
    check(!tp_loaded_visit_abi(&visitor, {0x5000}) && visitor.seen == 4, "stopped visitor cannot overwrite buffer");
    check(scope->refs[0] == 0x1000 && scope->refs[2] == 0x3000, "ordered roots preserved");
    uintptr_t invalids[] = {0, 0x1003, UINT64_C(0x100000000)};
    for (uintptr_t invalid : invalids) {
        scope->size = 0; scope->capacity = 3;
        tp_loaded::BoundedVisitor rejected(*scope);
        check(!tp_loaded_visit_abi(&rejected, {invalid}) && rejected.invalid_reference && scope->size == 0,
            "invalid or debug-poisoned object pointer refused");
    }
    scope->capacity = tp_loaded::max_classes + 1; scope->size = 0;
    tp_loaded::BoundedVisitor invalid_capacity(*scope);
    check(!tp_loaded_visit_abi(&invalid_capacity, {0x1000}) && invalid_capacity.invalid_reference,
        "oversized scope rejected before access");
    scope->capacity = 2; scope->size = 3;
    tp_loaded::BoundedVisitor invalid_size(*scope);
    check(!tp_loaded_visit_abi(&invalid_size, {0x1000}) && invalid_size.invalid_reference,
        "corrupted scope size refused");

    // Simulate the documented GC contract, not an actual ART GC. The test's
    // collector rewrites registered root cells between publication steps.
    scope->capacity = 128; scope->size = 0;
    tp_loaded::BoundedVisitor movable(*scope);
    for (unsigned i = 0; i < 128; i++) check(tp_loaded_visit_abi(&movable, {0x1000 + uintptr_t{i} * 8}), "mock root admission");
    uintptr_t fake_thread_top = 0x12345678;
    scope->link = fake_thread_top; fake_thread_top = reinterpret_cast<uintptr_t>(scope);
    unsigned moves = 0;
    for (unsigned i = 0; i < scope->size; i++) {
        auto *registered = reinterpret_cast<tp_loaded::RootScope *>(fake_thread_top);
        for (unsigned j = 0; j < registered->size; j++) registered->refs[j] = registered->refs[j] + 0x1000;
        moves++;
        uint32_t published = scope->refs[i]; // Reread the root, never a cached raw Class pointer.
        check(published == 0x1000 + i * 8 + moves * 0x1000, "mock moving root remains current at publication");
    }
    fake_thread_top = scope->link;
    check(fake_thread_top == 0x12345678 && scope->size == 128, "scope link restored after publication");
    std::free(scope);
    std::printf("{\"checks\":%u,\"mockRootMoves\":%u,\"actualArtGcVerified\":false,\"deviceExecuted\":false}\n", checks, moves);
}
