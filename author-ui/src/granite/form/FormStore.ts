import type { ContentNode, JcrValue, PostEntry } from '../types';
import { readContent } from '../node';
import { prefixEntry } from './slingPost';

/** What a mounted field contributes to the form. */
export interface FieldRegistration {
  /** Granite field name (`./jcr:title`). */
  name: string;
  /** Label for error messages. */
  label?: string;
  /** Sling POST entries for the current value. */
  entries(value: unknown): PostEntry[];
  /** Plain values, used by simple multifields to collect their items. */
  values?(value: unknown): string[];
  /** Returns an error message, or null when valid. */
  validate?(value: unknown): string | null;
  /** False while the field is hidden by show/hide logic; hidden fields are not validated. */
  isVisible?(): boolean;
  /** For fields holding nested state (multifield items). */
  isDirty?(): boolean;
}

export interface ValidationError {
  name: string;
  label?: string;
  message: string;
}

/**
 * State of one Granite form: current values keyed by field name, the content they came from, and the fields
 * currently mounted. Only mounted fields are submitted, which is how Granite behaves too (fields removed by
 * `granite:hide` or a render condition are never part of the request).
 *
 * Framework agnostic; React binds to it through `useSyncExternalStore`.
 */
export class FormStore {
  private readonly values = new Map<string, unknown>();
  private readonly initialValues = new Map<string, unknown>();
  private readonly registrations = new Map<number, FieldRegistration>();
  private readonly listeners = new Set<() => void>();
  private nextId = 1;
  private version = 0;

  constructor(readonly content: ContentNode = {}) {}

  // ---------------------------------------------------------------------------------------------- values

  /** Raw content value for a field name, e.g. `./image/alt` -> content.image.alt. */
  contentValue(name: string): JcrValue | ContentNode | undefined {
    return readContent(this.content, name);
  }

  has(name: string): boolean {
    return this.values.has(name);
  }

  get<T = unknown>(name: string): T | undefined {
    return this.values.get(name) as T | undefined;
  }

  /** Sets the starting value of a field once; later calls are ignored. */
  init(name: string, value: unknown): void {
    if (this.values.has(name)) return;
    this.values.set(name, value);
    this.initialValues.set(name, value);
  }

  /**
   * Replaces the starting value of a field nobody changed yet (options that arrive after the field initialised,
   * e.g. a datasource's default); the form stays clean.
   */
  resetInitial(name: string, value: unknown): void {
    if (!sameValue(this.values.get(name), this.initialValues.get(name))) return;
    this.values.set(name, value);
    this.initialValues.set(name, value);
    this.emit();
  }

  set(name: string, value: unknown): void {
    if (Object.is(this.values.get(name), value)) return;
    this.values.set(name, value);
    this.emit();
  }

  isDirty(): boolean {
    for (const [name, value] of this.values) {
      if (!sameValue(value, this.initialValues.get(name))) return true;
    }
    for (const reg of this.registrations.values()) {
      if (reg.isDirty?.()) return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------- fields

  register(registration: FieldRegistration): () => void {
    const id = this.nextId++;
    this.registrations.set(id, registration);
    // Values are initialised during render without notifying; announce them once the field is mounted so
    // show/hide targets rendered before their controller pick up the controller's value.
    this.emit();
    return () => {
      this.registrations.delete(id);
    };
  }

  /** Mounted field names, in mount order. */
  fieldNames(): string[] {
    return [...this.registrations.values()].map((r) => r.name);
  }

  /** The Sling POST entries of every mounted field, optionally re-rooted under `prefix`. */
  serialize(prefix = ''): PostEntry[] {
    const out: PostEntry[] = [];
    const seen = new Set<string>();
    for (const reg of this.registrations.values()) {
      // Two mounted fields with the same name would post twice; Granite would too, but it is never intended.
      if (seen.has(reg.name)) continue;
      seen.add(reg.name);
      for (const entry of reg.entries(this.values.get(reg.name))) {
        out.push(prefix ? prefixEntry(entry, prefix) : entry);
      }
    }
    return out;
  }

  /** Values of the given field, as plain strings (for simple multifield items). */
  plainValues(name: string): string[] {
    for (const reg of this.registrations.values()) {
      if (reg.name === name && reg.values) return reg.values(this.values.get(name));
    }
    return [];
  }

  validate(): ValidationError[] {
    const errors: ValidationError[] = [];
    for (const reg of this.registrations.values()) {
      if (!reg.validate) continue;
      if (reg.isVisible && !reg.isVisible()) continue;
      const message = reg.validate(this.values.get(reg.name));
      if (message) errors.push({ name: reg.name, label: reg.label, message });
    }
    return errors;
  }

  // ---------------------------------------------------------------------------------------------- subscription

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getVersion = (): number => this.version;

  /** Lets nested stores (multifield items) bubble their changes up. */
  emit(): void {
    this.version++;
    for (const l of [...this.listeners]) l();
  }
}

function sameValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => sameValue(v, b[i]));
  return false;
}
