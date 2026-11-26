import type { CfField, FieldKind } from './api';

/** The data types of the model editor's palette, with AEM's names, in AEM's order. */
export interface DataType {
  kind: FieldKind;
  title: string;
  description: string;
}

export const DATA_TYPES: DataType[] = [
  { kind: 'text', title: 'Single line text', description: 'Short text, one or more values' },
  { kind: 'longtext', title: 'Multi line text', description: 'Rich text, Markdown or plain text' },
  { kind: 'number', title: 'Number', description: 'Integer or fraction' },
  { kind: 'boolean', title: 'Boolean', description: 'A checkbox' },
  { kind: 'date', title: 'Date and time', description: 'Date, date and time, or time' },
  { kind: 'enumeration', title: 'Enumeration', description: 'One or more values from a list' },
  { kind: 'tags', title: 'Tags', description: 'Tags from the tag library' },
  { kind: 'content-reference', title: 'Content Reference', description: 'A file, page or other content' },
  { kind: 'fragment-reference', title: 'Fragment Reference', description: 'Another content fragment' },
  { kind: 'json', title: 'JSON Object', description: 'Structured data as JSON' },
  { kind: 'tab', title: 'Tab Placeholder', description: 'Starts a tab in the content fragment editor' },
];

export const typeTitle = (kind: FieldKind) => DATA_TYPES.find((t) => t.kind === kind)?.title ?? kind;

/** Kinds that can hold several values. */
export const MULTI_KINDS: FieldKind[] = ['text', 'number', 'content-reference', 'fragment-reference'];

/** An element name from a label, as AEM proposes it: lower camel case, letters and digits. */
export function propertyName(label: string): string {
  const words = label
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^A-Za-z0-9]+/g, ' ')
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  const name = words.map((w, i) => (i === 0 ? w.charAt(0).toLowerCase() + w.slice(1) : w.charAt(0).toUpperCase() + w.slice(1))).join('');
  return /^[0-9]/.test(name) ? `_${name}` : name;
}

/** A unique element name: `base`, `base2`, ... */
export function uniqueName(base: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  const root = base || 'element';
  if (!used.has(root)) return root;
  let i = 2;
  while (used.has(`${root}${i}`)) i++;
  return `${root}${i}`;
}

/** A new field of a data type, with AEM's defaults. */
export function newField(kind: FieldKind, taken: Iterable<string>, rootPath = '/content/dam'): CfField {
  const title = DATA_TYPES.find((t) => t.kind === kind)?.title ?? 'Field';
  const base: CfField = { kind, name: '', label: title, valueType: 'string', required: false, multiple: false };
  switch (kind) {
    case 'text':
      return { ...base, name: uniqueName('text', taken), label: 'Text', maxLength: 255, translatable: true };
    case 'longtext':
      return { ...base, name: uniqueName('body', taken), label: 'Body', mimeType: 'text/html', translatable: true };
    case 'number':
      return { ...base, name: uniqueName('number', taken), label: 'Number', valueType: 'long', step: '1' };
    case 'boolean':
      return { ...base, name: uniqueName('flag', taken), label: 'Flag', valueType: 'boolean', defaultValue: false };
    case 'date':
      return { ...base, name: uniqueName('date', taken), label: 'Date', valueType: 'calendar/datetime', dateType: 'datetime' };
    case 'enumeration':
      return { ...base, name: uniqueName('choice', taken), label: 'Choice', options: [{ value: 'option1', label: 'Option 1' }] };
    case 'tags':
      return { ...base, name: uniqueName('tags', taken), label: 'Tags', valueType: 'string[]', multiple: true };
    case 'content-reference':
      return { ...base, name: uniqueName('reference', taken), label: 'Reference', valueType: 'string/reference', rootPath };
    case 'fragment-reference':
      return { ...base, name: uniqueName('fragment', taken), label: 'Fragment', valueType: 'string/content-fragment', rootPath, allowedModels: [] };
    case 'json':
      return { ...base, name: uniqueName('data', taken), label: 'Data' };
    case 'tab':
      return { ...base, name: '', label: 'Tab' };
    default:
      return base;
  }
}

/** Server error keys (`fields.<i>.<prop>`) by field index. */
export function errorsByField(errors: { field: string; message: string }[]): { model: Record<string, string>; fields: Record<number, Record<string, string>> } {
  const out = { model: {} as Record<string, string>, fields: {} as Record<number, Record<string, string>> };
  for (const e of errors) {
    const m = e.field.match(/^fields\.(\d+)\.(.+)$/);
    if (m) (out.fields[Number(m[1])] ??= {})[m[2]] = e.message;
    else out.model[e.field] = e.message;
  }
  return out;
}

/** Client checks that mirror the server's (names, duplicates, labels, options, regex, min/max). */
export function checkFields(fields: CfField[]): { field: string; message: string }[] {
  const errors: { field: string; message: string }[] = [];
  const names = new Map<string, number>();
  fields.forEach((f, i) => {
    if (!f.label?.trim()) errors.push({ field: `fields.${i}.label`, message: 'A label is required.' });
    if (f.kind === 'tab') return;
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(f.name ?? '')) {
      errors.push({ field: `fields.${i}.name`, message: "Use letters, digits and '_', starting with a letter or '_'." });
    } else if (names.has(f.name)) {
      errors.push({ field: `fields.${i}.name`, message: `Another element is named ${f.name}.` });
    }
    names.set(f.name, i);
    if (f.kind === 'enumeration' && !(f.options ?? []).some((o) => o.value.trim())) {
      errors.push({ field: `fields.${i}.options`, message: 'Add at least one option.' });
    }
    const regex = f.validation?.regex;
    if (regex) {
      try {
        new RegExp(regex);
      } catch {
        errors.push({ field: `fields.${i}.validation.regex`, message: 'Not a valid regular expression.' });
      }
    }
    const { min, max } = f.validation ?? {};
    if (min != null && max != null && min > max) errors.push({ field: `fields.${i}.validation.max`, message: 'The maximum is below the minimum.' });
  });
  return errors;
}
