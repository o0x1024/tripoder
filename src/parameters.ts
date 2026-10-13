import { requireThat } from "./errors.js";

export type ParameterValue = string | boolean | number;
export interface ParameterSchema {
  readonly type: "string" | "boolean" | "number" | "integer";
  readonly default: ParameterValue;
  readonly title?: string;
  readonly minimum?: number;
  readonly maximum?: number;
  readonly maxLength?: number;
}
export interface Parameter<T extends ParameterValue = ParameterValue> {
  readonly schema: ParameterSchema & { readonly default: T };
}
export type Parameters = Readonly<Record<string, Parameter>>;
export type ParameterValues<S extends Parameters> = {
  readonly [K in keyof S]: S[K]["schema"]["default"];
};

function parameter<T extends ParameterValue>(schema: ParameterSchema & { default: T }): Parameter<T> {
  requireThat(schema.minimum === undefined || Number.isFinite(schema.minimum), "PARAM_SCHEMA", "minimum must be finite");
  requireThat(schema.maximum === undefined || Number.isFinite(schema.maximum), "PARAM_SCHEMA", "maximum must be finite");
  requireThat(schema.minimum === undefined || schema.maximum === undefined || schema.minimum <= schema.maximum,
    "PARAM_SCHEMA", "minimum must not exceed maximum");
  requireThat(schema.maxLength === undefined || (Number.isSafeInteger(schema.maxLength) && schema.maxLength >= 0),
    "PARAM_SCHEMA", "maxLength must be a non-negative integer");
  validateValue("default", schema, schema.default);
  return Object.freeze({ schema: Object.freeze({ ...schema }) });
}

export const param = {
  string(value: string, options: { title?: string; maxLength?: number } = {}): Parameter<string> {
    return parameter({ ...options, type: "string", default: value });
  },
  boolean(value: boolean, options: { title?: string } = {}): Parameter<boolean> {
    return parameter({ ...options, type: "boolean", default: value });
  },
  number(value: number, options: { title?: string; minimum?: number; maximum?: number } = {}): Parameter<number> {
    return parameter({ ...options, type: "number", default: value });
  },
  integer(value: number, options: { title?: string; minimum?: number; maximum?: number } = {}): Parameter<number> {
    return parameter({ ...options, type: "integer", default: value });
  },
};

function validateValue(name: string, schema: ParameterSchema, value: unknown): asserts value is ParameterValue {
  const expected = schema.type === "integer" ? "number" : schema.type;
  requireThat(typeof value === expected, "PARAM_TYPE", `${name}: expected ${schema.type}`);
  if (typeof value === "number") {
    requireThat(Number.isFinite(value), "PARAM_RANGE", `${name}: must be finite`);
    requireThat(schema.type !== "integer" || Number.isSafeInteger(value), "PARAM_RANGE", `${name}: must be a safe integer`);
    requireThat(schema.minimum === undefined || value >= schema.minimum, "PARAM_RANGE", `${name}: below minimum`);
    requireThat(schema.maximum === undefined || value <= schema.maximum, "PARAM_RANGE", `${name}: above maximum`);
  }
  if (typeof value === "string" && schema.maxLength !== undefined) {
    requireThat([...value].length <= schema.maxLength, "PARAM_RANGE", `${name}: too long`);
  }
}

export function resolveParameters<S extends Parameters>(spec: S, input: Readonly<Record<string, unknown>> = {}): ParameterValues<S> {
  for (const key of Object.keys(input)) {
    requireThat(Object.hasOwn(spec, key), "UNKNOWN_PARAM", `Unknown parameter: ${key}`);
  }
  const entries = Object.entries(spec).map(([key, parameter]) => {
    const value = Object.hasOwn(input, key) ? input[key] : parameter.schema.default;
    validateValue(key, parameter.schema, value);
    return [key, value];
  });
  return Object.freeze(Object.fromEntries(entries)) as ParameterValues<S>;
}

export function parameterJsonSchema(spec: Parameters) {
  return {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    additionalProperties: false,
    properties: Object.fromEntries(Object.entries(spec).map(([name, p]) => [name, { ...p.schema }])),
  } as const;
}
