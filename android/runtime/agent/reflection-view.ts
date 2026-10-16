import Java from "frida-java-bridge";

/** Native observation keeps Field's original caller-sensitive ART frame and access checks. */
export function installInitialApplicationView(initialField: any) {
  const Field = (Java.classFactory as any).use("java.lang.reflect.Field", { members: "methods" });
  const resolve = (globalThis as any).__tripoderArtNativeEntry;
  if (typeof resolve !== "function") throw new Error("ART_NATIVE_ENTRY_RESOLVER_MISSING");
  const get = resolve(Field.get.overload("java.lang.Object").handle);
  const set = resolve(Field.set.overload("java.lang.Object", "java.lang.Object").handle);
  if (get.entry.equals(set.entry)) throw new Error("REFLECTION_NATIVE_ENTRIES_COLLIDE");
  // Field ID, thread, physical Application, logical Application, Application class,
  // SecurityException class and message. All JNI object handles are global refs.
  const config = Memory.alloc(7 * Process.pointerSize);
  for (let i = 0; i < 7; i++) config.add(i * Process.pointerSize).writePointer(ptr(0));
  const module = new CModule(`
    #include <gum/guminterceptor.h>
    typedef void *Object;
    typedef const void **Env;
    extern void *configuration[];
    typedef struct { Env *env; int mapped; int invalid; } State;
    static void *function(Env *env, int index) { return (void *)(*env)[index]; }
    static int same(Env *env, Object a, Object b) {
      return ((unsigned char (*)(Env *, Object, Object)) function(env, 24))(env, a, b);
    }
    static int exception(Env *env) {
      return ((unsigned char (*)(Env *)) function(env, 228))(env);
    }
    static void deny(Env *env) {
      ((int (*)(Env *, Object, const char *)) function(env, 14))(env, configuration[5], configuration[6]);
    }
    static State *enter(GumInvocationContext *ic) {
      State *state = gum_invocation_context_get_listener_invocation_data(ic, sizeof(State));
      state->env = gum_invocation_context_get_nth_argument(ic, 0);
      state->mapped = 0; state->invalid = 0;
      if (configuration[3] == 0) return state;
      Object field = gum_invocation_context_get_nth_argument(ic, 1);
      Object owner = gum_invocation_context_get_nth_argument(ic, 2);
      void *id = ((void *(*)(Env *, Object)) function(state->env, 8))(state->env, field);
      state->mapped = id == configuration[0] && same(state->env, owner, configuration[1]);
      return state;
    }
    void get_enter(GumInvocationContext *ic) { enter(ic); }
    void get_leave(GumInvocationContext *ic) {
      State *state = gum_invocation_context_get_listener_invocation_data(ic, sizeof(State));
      if (!state->mapped || exception(state->env)) return;
      Object original = gum_invocation_context_get_return_value(ic);
      if (!same(state->env, original, configuration[2])) {
        deny(state->env); gum_invocation_context_replace_return_value(ic, 0); return;
      }
      Object logical = ((Object (*)(Env *, Object)) function(state->env, 25))(state->env, configuration[3]);
      gum_invocation_context_replace_return_value(ic, logical);
    }
    void set_enter(GumInvocationContext *ic) {
      State *state = enter(ic); if (!state->mapped) return;
      Object value = gum_invocation_context_get_nth_argument(ic, 3);
      // Preserve ART's original type-error ordering before its access check.
      if (value != 0 && !((unsigned char (*)(Env *, Object, Object)) function(state->env, 32))(state->env, value, configuration[4])) {
        state->mapped = 0; return;
      }
      state->invalid = !same(state->env, value, configuration[3]);
      gum_invocation_context_replace_nth_argument(ic, 3, configuration[2]);
    }
    void set_leave(GumInvocationContext *ic) {
      State *state = gum_invocation_context_get_listener_invocation_data(ic, sizeof(State));
      if (state->mapped && state->invalid && !exception(state->env)) deny(state->env);
    }
  `, { configuration: config });
  const listeners = [Interceptor.attach(get.entry, { onEnter: module.get_enter, onLeave: module.get_leave }),
    Interceptor.attach(set.entry, { onEnter: module.set_enter, onLeave: module.set_leave })];
  Interceptor.flush();
  let configured = false;
  const configure = (thread: any, host: any, guest: any) => {
    if (configured) throw new Error("INITIAL_APPLICATION_VIEW_ALREADY_CONFIGURED");
    const env: any = Java.vm.getEnv();
    const refs = [env.fromReflectedField(initialField.$h), env.newGlobalRef(thread.$h), env.newGlobalRef(host.$h),
      env.newGlobalRef(guest.$h), env.newGlobalRef(Java.use("android.app.Application").class.$h),
      env.newGlobalRef(Java.use("java.lang.SecurityException").class.$h), Memory.allocUtf8String("INITIAL_APPLICATION_IDENTITY_IMMUTABLE")];
    for (const i of [0, 1, 2, 4, 5, 6, 3]) config.add(i * Process.pointerSize).writePointer(refs[i]);
    configured = true;
    (globalThis as any).__tripoderReflectionView = { module, config, listeners, refs,
      get: { flags: get.flags, offset: get.offset }, set: { flags: set.flags, offset: set.offset } };
  };
  // Keep native code and listener storage alive even before Application construction.
  (globalThis as any).__tripoderReflectionView = { module, config, listeners };
  return configure;
}
