export class HookError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "HookError";
  }
}

export function requireThat(condition: unknown, code: string, message: string): asserts condition {
  if (!condition) throw new HookError(code, message);
}
