import { requireThat } from './errors.js';

export interface JavaObjectRef { readonly javaObjectReference: unique symbol }
const name = '[^<>.;/\\[\\]\\x00-\\x20\\x7f]+';
const binary = `${name}(?:\\.${name})*`;
const references = new RegExp(`^(?:${binary}|\\[+(?:[ZBSCIJFD]|L${binary};))$`);
const values = new Set(['boolean','byte','short','int','long','char','float','double','java.lang.String','[B','byte[]']);
const reserved=new Set(['void','boolean','byte','short','int','long','char','float','double','int32','uint32','int64','uint64']);
export const isJavaReferenceType = (type: string): boolean => !reserved.has(type)&&references.test(type);
export const validJavaType = (type: string, result = false): boolean => type.length <= 512 && (values.has(type) || isJavaReferenceType(type) || result && type === 'void');

/** Explicit host fixture, never a claim that a JSON value is an Android object. */
export interface SimulatedJavaObject {
  readonly className: string;
  readonly assignableTo: readonly string[];
  readonly snapshot?: Readonly<Record<string, unknown>>;
}
interface Reference { frame: JavaObjectFrame; value: SimulatedJavaObject | undefined }
const refs = new WeakMap<object, Reference>();
const frames: JavaObjectFrame[] = [];
export function isJavaObjectRef(value: unknown): value is JavaObjectRef {return typeof value==='object'&&value!==null&&refs.has(value);}
export function liveJavaObjectRef(value: JavaObjectRef): JavaObjectRef {JavaObjectFrame.info(value);return value;}
export class JavaObjectFrame {
  #closed = false;
  readonly #references = new Map<SimulatedJavaObject, JavaObjectRef>();
  constructor(){ frames.push(this); }
  #live(): void { requireThat(!this.#closed && frames.at(-1) === this, 'HOOK_OBJECT_REFERENCE_EXPIRED', 'Java reference belongs to another or completed invocation'); }
  wrap(type: string, value: SimulatedJavaObject | null): JavaObjectRef | null {
    this.#live(); if(value === null) return null;
    requireThat(value.className === type || value.assignableTo.includes(type), 'HOOK_OBJECT_TYPE_MISMATCH', type);
    let ref = this.#references.get(value);
    if(!ref){const token=Object.create(null) as JavaObjectRef;refs.set(token,{frame:this,value});ref=Object.freeze(token);this.#references.set(value,ref);}
    return ref;
  }
  unwrap(type: string, ref: unknown): SimulatedJavaObject | null {
    this.#live();if(ref === null)return null;
    const entry = typeof ref === 'object' && ref !== null ? refs.get(ref) : undefined;
    requireThat(entry?.frame === this && entry.value, 'HOOK_OBJECT_REFERENCE_REQUIRED', type);
    requireThat(entry.value.className === type || entry.value.assignableTo.includes(type), 'HOOK_OBJECT_TYPE_MISMATCH', type);
    return entry.value;
  }
  static valid(type: string, value: unknown): boolean {
    const frame=frames.at(-1);if(!frame)return false;
    frame.unwrap(type,value);return true;
  }
  static info(ref: JavaObjectRef): Readonly<Record<string, unknown>> {
    const entry=refs.get(ref);requireThat(entry, 'HOOK_OBJECT_REFERENCE_REQUIRED', 'An invocation reference is required');entry.frame.#live();requireThat(entry.value,'HOOK_OBJECT_REFERENCE_EXPIRED','Object root released');
    return Object.freeze({className:entry.value.className,loader:'SIMULATED_FIXTURE'});
  }
  static snapshot(ref: JavaObjectRef, type: 'java.lang.Thread' | 'java.lang.Throwable'): Readonly<Record<string, unknown>> {
    const frame=frames.at(-1);requireThat(frame, 'HOOK_OBJECT_OUTSIDE_INVOCATION', 'Snapshot requires an invocation');
    const value=frame.unwrap(type,ref);requireThat(value?.snapshot, 'SIMULATED_SNAPSHOT_REQUIRED', 'Provide an explicit host fixture snapshot');
    return Object.freeze(structuredClone(value.snapshot));
  }
  /** Direct field read for simulation: looks up fieldName in the fixture's .snapshot, any className. */
  static field(ref: JavaObjectRef, fieldName: string): unknown {
    const frame=frames.at(-1);requireThat(frame, 'HOOK_OBJECT_OUTSIDE_INVOCATION', 'readField requires an invocation');
    const entry=refs.get(ref);requireThat(entry, 'HOOK_OBJECT_REFERENCE_REQUIRED', 'An invocation reference is required');
    requireThat(entry.frame === frame, 'HOOK_OBJECT_SCOPE_MISMATCH', 'Reference belongs to another invocation');
    const value=entry.value;requireThat(value, 'HOOK_OBJECT_REFERENCE_EXPIRED', 'Object root released');
    requireThat(value.snapshot, 'SIMULATED_SNAPSHOT_REQUIRED', 'Provide an explicit host fixture snapshot');
    requireThat(Object.hasOwn(value.snapshot, fieldName), 'HOOK_FIELD_NOT_FOUND', fieldName);
    return structuredClone(value.snapshot[fieldName]);
  }
  /** Shallow container snapshot for simulation: returns bounded entries from the fixture's .snapshot. */
  static container(ref: JavaObjectRef, maxEntries = 16): Readonly<Record<string, unknown>> {
    const frame=frames.at(-1);requireThat(frame, 'HOOK_OBJECT_OUTSIDE_INVOCATION', 'snapshotContainer requires an invocation');
    const entry=refs.get(ref);requireThat(entry, 'HOOK_OBJECT_REFERENCE_REQUIRED', 'An invocation reference is required');
    requireThat(entry.frame === frame, 'HOOK_OBJECT_SCOPE_MISMATCH', 'Reference belongs to another invocation');
    const value=entry.value;requireThat(value, 'HOOK_OBJECT_REFERENCE_EXPIRED', 'Object root released');
    requireThat(value.snapshot, 'SIMULATED_SNAPSHOT_REQUIRED', 'Provide an explicit host fixture snapshot');
    const allEntries=Object.entries(value.snapshot);
    const bounded=Object.fromEntries(allEntries.slice(0, maxEntries));
    return Object.freeze({
      className: value.className,
      containerKind: "SimulatedContainer",
      size: allEntries.length,
      entries: Object.freeze(structuredClone(bounded)),
      truncated: allEntries.length > maxEntries,
    });
  }
  close(): void {this.#live();this.#closed=true;for(const ref of this.#references.values()){const entry=refs.get(ref);if(entry)entry.value=undefined;}this.#references.clear();frames.pop();}
}

export function assertSnapshotData(value: unknown, path = new Set<object>()): void {
  if(value === null || typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number' && Number.isFinite(value)) return;
  requireThat(typeof value === 'object' && value !== null && !refs.has(value), 'HOOK_EVENT_VALUE_UNSUPPORTED', 'Emit an explicit JSON snapshot, not an object reference');
  requireThat(!path.has(value), 'HOOK_EVENT_VALUE_UNSUPPORTED', 'Cyclic snapshot');path.add(value);
  for(const child of Object.values(value))assertSnapshotData(child,path);path.delete(value);
}

export function eventSnapshot(value:unknown,path=new Set<object>(),count={value:0}):unknown {
  requireThat(++count.value<=8000&&path.size<80,'HOOK_EVENT_VALUE_UNSUPPORTED','Event size/depth budget exceeded');
  if(typeof value==='bigint')return value.toString();
  if(value&&typeof value==='object'&&!isJavaObjectRef(value)){requireThat(!path.has(value),'HOOK_EVENT_VALUE_UNSUPPORTED','Cyclic event');path.add(value);try{return Array.isArray(value)?value.map(item=>eventSnapshot(item,path,count)):Object.fromEntries(Object.entries(value).map(([key,item])=>[key,eventSnapshot(item,path,count)]));}finally{path.delete(value);}}
  assertSnapshotData(value);return value;
}
