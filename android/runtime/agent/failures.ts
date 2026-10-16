import Java from "frida-java-bridge";

/** Observe the failure before an app-owned handler can terminate; preserve dispatch/exit. */
export function installFailureDiagnostics(Bridge: any) {
  const Thread = (Java.classFactory as any).use("java.lang.Thread", { members: "methods" });
  const dispatch = Thread.dispatchUncaughtException.overload("java.lang.Throwable");
  dispatch.implementation = function(this: any, error: any) {
    try { Bridge.failureDiagnostic("uncaught", error); }
    finally { return dispatch.call(this, error); }
  };
  // Coroutine runtimes may call the ThreadGroup handler directly, without dispatchUncaughtException.
  const Group = (Java.classFactory as any).use("java.lang.ThreadGroup", { members: "methods" });
  const uncaught = Group.uncaughtException.overload("java.lang.Thread", "java.lang.Throwable");
  uncaught.implementation = function(this: any, thread: any, error: any) {
    try { Bridge.failureDiagnostic("thread-group-uncaught", error); }
    finally { return uncaught.call(this, thread, error); }
  };
  const Runtime = (Java.classFactory as any).use("java.lang.Runtime", { members: "methods" });
  for (const name of ["exit", "halt"]) {
    const method = Runtime[name].overload("int");
    method.implementation = function(this: any, status: number) {
      try { Bridge.failureDiagnostic(name+":"+status, Java.use("java.lang.Throwable").$new()); }
      finally { return method.call(this, status); }
    };
  }
}
