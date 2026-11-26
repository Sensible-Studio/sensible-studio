import type { PostEntry } from '../types';

/**
 * How Granite fields turn into a Sling POST request.
 *
 * The rules mirror what the Granite/Coral form posts in AEM so content written by Sensible Studio is identical:
 * - a field posts `name=value`; an empty value makes Sling remove the property,
 * - a checkbox adds `name@Delete` (unless `deleteHint=false`) so unchecking removes the property,
 *   or posts its `uncheckedValue` when one is configured,
 * - multi-value fields repeat `name` and add `name@TypeHint=String[]` so a single value stays an array,
 * - `typeHint` on a field becomes `name@TypeHint`.
 */

export function scalarEntries(name: string, value: string, typeHint?: string): PostEntry[] {
  const out: PostEntry[] = [{ name, value }];
  if (typeHint && value !== '') out.push({ name: `${name}@TypeHint`, value: typeHint });
  return out;
}

export interface CheckboxPostOptions {
  /** Posted when checked. Granite default: "true". */
  value: string;
  /** Posted when unchecked, if defined. */
  uncheckedValue?: string;
  /** Granite default true: post `name@Delete` so an unchecked box removes the property. */
  deleteHint: boolean;
  typeHint?: string;
}

export function checkboxEntries(name: string, checked: boolean, opts: CheckboxPostOptions): PostEntry[] {
  const out: PostEntry[] = [];
  if (checked) {
    out.push({ name, value: opts.value });
  } else if (opts.uncheckedValue !== undefined) {
    out.push({ name, value: opts.uncheckedValue });
  } else if (opts.deleteHint) {
    out.push({ name: `${name}@Delete`, value: '' });
  }
  if (opts.typeHint && out.length && !out[0].name.endsWith('@Delete')) {
    out.push({ name: `${name}@TypeHint`, value: opts.typeHint });
  }
  return out;
}

export function multiEntries(name: string, values: string[], typeHint = 'String[]'): PostEntry[] {
  const nonEmpty = values.filter((v) => v !== '');
  if (nonEmpty.length === 0) return [{ name: `${name}@Delete`, value: '' }];
  return [
    ...nonEmpty.map((value) => ({ name, value })),
    { name: `${name}@TypeHint`, value: typeHint },
  ];
}

/**
 * Re-roots an entry posted by a field inside a composite multifield item.
 * `./link` under `./items/item0` becomes `./items/item0/link`.
 */
export function prefixEntry(entry: PostEntry, prefix: string): PostEntry {
  if (!prefix) return entry;
  const base = prefix.endsWith('/') ? prefix : `${prefix}/`;
  let name = entry.name;
  if (name.startsWith('./')) name = name.slice(2);
  return { name: `${base}${name}`, value: entry.value };
}

/**
 * A checkbox declared with typed values (`value="{Boolean}true"`) stores a JCR Boolean; one declared with
 * plain strings stores a String. Our dialog JSON keeps the declared type, so we can tell them apart.
 */
export function inferCheckboxTypeHint(value: unknown, uncheckedValue: unknown): string | undefined {
  if (typeof value === 'boolean' || typeof uncheckedValue === 'boolean') return 'Boolean';
  if (typeof value === 'number' || typeof uncheckedValue === 'number') return 'Long';
  return undefined;
}

export function toUrlEncoded(entries: PostEntry[]): URLSearchParams {
  const params = new URLSearchParams();
  params.append('_charset_', 'utf-8');
  for (const e of entries) params.append(e.name, e.value);
  return params;
}
