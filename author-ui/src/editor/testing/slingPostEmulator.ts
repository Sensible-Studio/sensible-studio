import type { ContentNode, JcrValue, PostEntry } from '../../granite';
import { isContentNode } from '../../granite/node';

/**
 * A small in-memory interpretation of the Sling POST servlet, used by the offline client and by tests to
 * check that what the dialogs post produces the content AEM would store.
 * Supported: properties, nested `./a/b`, `@Delete`, `@TypeHint` (Boolean, Long, Double, Date, String[] and
 * friends), repeated names, `:operation=delete`, `:order`.
 */
export function applySlingPost(repo: ContentNode, targetPath: string, entries: PostEntry[]): void {
  const operation = entries.find((e) => e.name === ':operation')?.value;
  if (operation === 'delete') {
    deleteNode(repo, targetPath);
    return;
  }
  const order = entries.find((e) => e.name === ':order')?.value;

  const target = resolve(repo, targetPath, true)!;
  const deletes: string[] = [];
  const hints = new Map<string, string>();
  const values = new Map<string, string[]>();

  for (const { name, value } of entries) {
    if (name.startsWith(':') || name === '_charset_') continue;
    const at = name.indexOf('@');
    if (at >= 0) {
      const base = relative(name.slice(0, at));
      const suffix = name.slice(at + 1);
      if (suffix === 'Delete') deletes.push(base);
      else if (suffix === 'TypeHint') hints.set(base, value);
      continue;
    }
    const key = relative(name);
    values.set(key, [...(values.get(key) ?? []), value]);
  }

  // Sling processes deletes before writing.
  for (const path of deletes) removeItem(target, path);

  for (const [path, list] of values) {
    const segments = path.split('/');
    const propName = segments.pop()!;
    const parent = walk(target, segments, true)!;
    const hint = hints.get(path);
    const converted = convert(list, hint);
    if (converted === undefined) delete parent[propName];
    else parent[propName] = converted;
  }

  if (order) applyOrder(repo, targetPath, order);
}

/** Creates a child with a unique name derived from `nameHint`, like `POST /container/*`. */
export function createChild(repo: ContentNode, parentPath: string, nameHint: string, props: ContentNode): string {
  const parent = resolve(repo, parentPath, true)!;
  const base = nameHint.replace(/[^A-Za-z0-9_-]/g, '_') || 'node';
  let name = base;
  for (let i = 0; parent[name] !== undefined; i++) name = `${base}_${i}`;
  parent[name] = { ...props };
  return `${parentPath.replace(/\/$/, '')}/${name}`;
}

export function resolve(repo: ContentNode, path: string, create = false): ContentNode | undefined {
  return walk(repo, path.split('/').filter(Boolean), create);
}

function walk(node: ContentNode, segments: string[], create: boolean): ContentNode | undefined {
  let current = node;
  for (const s of segments) {
    let next = current[s];
    if (!isContentNode(next)) {
      if (!create) return undefined;
      next = { 'jcr:primaryType': 'nt:unstructured' };
      current[s] = next;
    }
    current = next;
  }
  return current;
}

function relative(name: string): string {
  return name.replace(/^\.\//, '').replace(/^\//, '');
}

function removeItem(target: ContentNode, path: string): void {
  const segments = path.split('/');
  const last = segments.pop()!;
  const parent = walk(target, segments, false);
  if (parent) delete parent[last];
}

function deleteNode(repo: ContentNode, path: string): void {
  const segments = path.split('/').filter(Boolean);
  const last = segments.pop()!;
  const parent = walk(repo, segments, false);
  if (parent) delete parent[last];
}

function convert(list: string[], hint?: string): JcrValue | undefined {
  const isArray = hint?.endsWith('[]') || list.length > 1;
  const type = hint?.replace('[]', '');
  const one = (v: string): JcrValue => {
    switch (type) {
      case 'Boolean':
        return v === 'true';
      case 'Long':
        return Number.parseInt(v, 10);
      case 'Double':
      case 'Decimal':
        return Number.parseFloat(v);
      default:
        return v;
    }
  };
  if (isArray) {
    const nonEmpty = list.filter((v) => v !== '');
    return nonEmpty.length ? nonEmpty.map(one) : undefined;
  }
  if (list[0] === '') return undefined; // Sling removes a property posted empty
  return one(list[0]);
}

function applyOrder(repo: ContentNode, path: string, order: string): void {
  const segments = path.split('/').filter(Boolean);
  const name = segments.pop()!;
  const parent = walk(repo, segments, false);
  if (!parent || !(name in parent)) return;
  const keys = Object.keys(parent);
  const without = keys.filter((k) => k !== name);
  let index = without.length;
  const [op, ref] = order.split(/\s+/, 2);
  if (op === 'first') index = 0;
  else if (op === 'last') index = without.length;
  else if (op === 'before' && ref) index = Math.max(0, without.indexOf(ref));
  else if (op === 'after' && ref) index = without.indexOf(ref) + 1;
  const reordered = [...without.slice(0, index), name, ...without.slice(index)];
  const copy = { ...parent };
  for (const k of keys) delete parent[k];
  for (const k of reordered) parent[k] = copy[k];
}
