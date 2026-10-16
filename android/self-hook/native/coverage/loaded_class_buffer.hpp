#ifndef TP_LOADED_CLASS_BUFFER_HPP
#define TP_LOADED_CLASS_BUFFER_HPP
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <type_traits>

namespace tp_loaded {
constexpr uint32_t max_classes = 65536;
/* Release ObjPtr<T> is a trivially-copyable, one-pointer value. The actual
 * platform's callback loads vtable+16 and passes that value in x1. */
struct ClassRef { uintptr_t value; };
static_assert(sizeof(ClassRef) == 8 && std::is_trivially_copyable_v<ClassRef>);
class ClassVisitorAbi {
public:
    virtual ~ClassVisitorAbi() = default;
    virtual bool operator()(ClassRef klass) = 0;
    // Instances are never heap-deleted through ART. This keeps the bounded
    // observer independent of libc++ allocation and rejects unexpected deletion.
    static void operator delete(void *) noexcept { std::abort(); }
};
static_assert(sizeof(ClassVisitorAbi) == sizeof(uintptr_t));

struct RootScope {
    uintptr_t link;
    uint32_t capacity;
    uint32_t size;
    volatile uint32_t refs[max_classes];
};
static_assert(offsetof(RootScope, capacity) == 8 && offsetof(RootScope, size) == 12
    && offsetof(RootScope, refs) == 16 && alignof(RootScope) == 8);

class BoundedVisitor final : public ClassVisitorAbi {
public:
    explicit BoundedVisitor(RootScope &scope) : scope_(scope) {}
    bool operator()(ClassRef klass) override {
        if (exhausted || invalid_reference) return false;
        // No dereference of klass; ART's table/read-barrier path supplied it.
        seen++;
        if (!klass.value || klass.value > UINT32_MAX || (klass.value & 7)) {
            invalid_reference = true; return false;
        }
        if (scope_.size == scope_.capacity) { exhausted = true; return false; }
        if (scope_.size > scope_.capacity || scope_.capacity > max_classes) {
            invalid_reference = true; return false;
        }
        scope_.refs[scope_.size] = static_cast<uint32_t>(klass.value);
        scope_.size++;
        return true;
    }
    uint32_t seen = 0;
    bool exhausted = false, invalid_reference = false;
private:
    RootScope &scope_;
};
}
#endif
