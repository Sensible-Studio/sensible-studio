import type { CfField, CfValue } from './api';

/**
 * Pure logic of the fragment form: normalising stored values for inputs, checking them like the server does,
 * and computing what changed. Kept free of React so it is tested directly against the WKND models.
 */

/** Value shapes the inputs work with. */
export type FormValue = string | number | boolean | null | string[];

export const isMulti = (f: CfField) => f.multiple || f.valueType.endsWith('[]');

/** Stored value -> input value. */
export function toFormValue(field: CfField, stored: CfValue | undefined): FormValue {
  const list = stored === undefined || stored === null ? [] : Array.isArray(stored) ? stored : [stored];
  switch (field.kind) {
    case 'number': {
      if (isMulti(field)) return list.map(String);
      const n = list.length ? Number(list[0]) : NaN;
      return Number.isFinite(n) ? n : null;
    }
    case 'boolean':
      return list.length ? list[0] === true || String(list[0]) === 'true' : false;
    case 'tags':
      return list.map(String);
    case 'enumeration':
      return isMulti(field) ? list.map(String) : list.length ? String(list[0]) : '';
    default:
      if (isMulti(field)) return list.map(String);
      return list.length ? String(list[0]) : '';
  }
}

/** Input value -> what the API stores (null removes). */
export function toApiValue(field: CfField, value: FormValue): CfValue {
  if (value === null || value === undefined) return null;
  if (Array.isArray(value)) {
    const items = value.map((v) => v.trim()).filter((v) => v !== '');
    if (!items.length) return null;
    if (field.kind === 'number') return items.map(Number);
    return items;
  }
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'boolean') return value;
  return value.trim() === '' ? null : value;
}

export function isEmpty(value: FormValue): boolean {
  if (value === null || value === undefined) return true;
  if (Array.isArray(value)) return value.every((v) => v.trim() === '');
  if (typeof value === 'string') return value.trim() === '';
  if (typeof value === 'number') return !Number.isFinite(value);
  return false;
}

/** Same rules as the server (FragmentStore.validate), so authors see problems before saving. */
export function validate(field: CfField, value: FormValue): string | null {
  if (isEmpty(value)) return field.required ? 'This field is required.' : null;
  const v = field.validation ?? {};
  const strings = Array.isArray(value) ? value : typeof value === 'string' ? [value] : [];
  if (v.regex) {
    let re: RegExp | null = null;
    try {
      re = new RegExp(`^(?:${v.regex.replace(/^\^/, '').replace(/\$$/, '')})$`);
    } catch {
      re = null; // a broken pattern in the model must not block authors
    }
    if (re && strings.some((s) => s && !re!.test(s))) return v.message ?? 'The value does not match the required format.';
  }
  if (field.maxLength && strings.some((s) => s.length > field.maxLength!)) return `At most ${field.maxLength} characters.`;
  const numbers = typeof value === 'number' ? [value] : field.kind === 'number' && Array.isArray(value) ? value.map(Number) : [];
  if (numbers.some((n) => !Number.isFinite(n))) return 'Enter a number.';
  if (v.min !== undefined && numbers.some((n) => n < v.min!)) return v.message ?? `Must be at least ${v.min}.`;
  if (v.max !== undefined && numbers.some((n) => n > v.max!)) return v.message ?? `Must be at most ${v.max}.`;
  if (field.kind === 'json' && typeof value === 'string') {
    try {
      JSON.parse(value);
    } catch {
      return 'Not valid JSON.';
    }
  }
  return null;
}

export function sameValue(a: FormValue, b: FormValue): boolean {
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => v === b[i]);
  if (typeof a === 'number' && typeof b === 'number') return a === b || (Number.isNaN(a) && Number.isNaN(b));
  return a === b;
}

/** Elements whose input value differs from the loaded one, as API values. */
export function changes(fields: CfField[], initial: Record<string, FormValue>, current: Record<string, FormValue>): Record<string, CfValue> {
  const out: Record<string, CfValue> = {};
  for (const f of fields) {
    if (f.kind === 'tab' || f.kind === 'unknown') continue;
    if (!sameValue(initial[f.name] ?? null, current[f.name] ?? null)) out[f.name] = toApiValue(f, current[f.name] ?? null);
  }
  return out;
}

/** The fields of a model split at its tab placeholders (AEM shows each part as a tab). */
export function tabsOf(fields: CfField[]): Array<{ title: string; fields: CfField[] }> {
  const tabs: Array<{ title: string; fields: CfField[] }> = [];
  let current = { title: 'Content', fields: [] as CfField[] };
  for (const f of fields) {
    if (f.kind === 'tab') {
      if (current.fields.length) tabs.push(current);
      current = { title: f.label || 'Tab', fields: [] };
    } else {
      current.fields.push(f);
    }
  }
  if (current.fields.length || !tabs.length) tabs.push(current);
  return tabs;
}

/** Name for a new fragment or variation, derived from its title the way the server does. */
export function slugify(title: string): string {
  const s = title
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return s.slice(0, 60) || 'fragment';
}
